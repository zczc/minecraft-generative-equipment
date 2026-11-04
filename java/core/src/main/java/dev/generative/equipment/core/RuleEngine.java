package dev.generative.equipment.core;

import static dev.generative.equipment.core.HostPorts.*;
import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.generative.equipment.protocol.*;
import java.util.*;
import java.util.function.*;

/** 单次装备激活的同步编排器。只处理数据和回执，世界、实际槽位和绘制交给宿主。 */
public final class RuleEngine {
  public interface Feedback {
    void show(JsonNode signal, List<JsonNode> receipts);

    void stop(String activityKey);
  }

  private final RuleRuntime runtime;
  private final LoadedDefinition definition;
  private final ActivationLease lease;
  private final ItemStatePort items;
  private final QueryPort queries;
  private final WorldOperationPort world;
  private final BooleanSupplier active;
  private final Supplier<JsonNode> self;
  private final Feedback feedback;
  private final Map<String, ObjectNode> activities = new LinkedHashMap<>();
  private final Map<String, ObjectNode> timers = new LinkedHashMap<>();
  private boolean stopped, busy;
  private long tick;
  private long budgetNanos = 5_000_000;

  public RuleEngine(
      RuleRuntime runtime,
      LoadedDefinition definition,
      ActivationLease lease,
      ItemStatePort items,
      QueryPort queries,
      WorldOperationPort world,
      BooleanSupplier active,
      Supplier<JsonNode> self,
      Feedback feedback) {
    this.runtime = runtime;
    this.definition = definition;
    this.lease = lease;
    this.items = items;
    this.queries = queries;
    this.world = world;
    this.active = active;
    this.self = self;
    this.feedback = feedback;
  }

  public LoadedDefinition definition() {
    return definition;
  }

  public ActivationLease lease() {
    return lease;
  }

  public void budgetNanos(long value) {
    budgetNanos = Math.max(100_000, Math.min(value, 1_000_000_000));
  }

  public JsonNode activitySnapshot() {
    var out = MAPPER.createArrayNode();
    for (var a : activities.values()) {
      var x = a.deepCopy();
      x.put("elapsedTicks", tick - x.remove("startedTick").asLong());
      x.put("phaseElapsedTicks", tick - x.remove("phaseTick").asLong());
      out.add(x);
    }
    return out;
  }

  public JsonNode state() {
    return items.read(lease).state();
  }

  /** 外部事件不能重入；操作产生的同步伤害事件保留原版处理。单次链最多 4 层、32 个操作。 */
  public void onEvent(ActivationLease requested, Contracts.RuleEvent event) {
    if (!lease.equals(requested) || busy || stopped || !active.getAsBoolean()) return;
    if (!contains(definition.equipment().path("subscriptions"), event.kind())
        && !Set.of("timer.fired", "operation.results").contains(event.kind())) return;
    busy = true;
    tick = event.tick();
    try {
      process(event, new ArrayList<>(), 0, new int[] {0}, System.nanoTime() + budgetNanos);
    } catch (RuntimeException e) {
      stop();
      throw e;
    } finally {
      busy = false;
    }
  }

  private void process(
      Contracts.RuleEvent event,
      List<JsonNode> available,
      int depth,
      int[] operationCount,
      long deadline) {
    requireActive();
    if (depth >= 4) throw new IllegalArgumentException("Event chain limit exceeded");
    var snapshot = items.read(lease);
    definition.stateSchema().validate(snapshot.state());
    InvocationResult result =
        runtime.invoke(
            definition.program(),
            new Invocation(
                lease,
                event,
                snapshot.state(),
                definition.equipment().path("parameters"),
                activitySnapshot(),
                self.get()),
            (name, args, l) -> {
              if (!contains(definition.equipment().path("requires").path("queries"), name))
                return new QueryResult(false, NullNode.instance, "UNDECLARED_QUERY");
              requireActive();
              return queries.query(name, args, l);
            },
            new ExecutionBudget(8388608, deadline, 32 - operationCount[0], 16));
    if (result instanceof InvocationFailure failure)
      throw new IllegalArgumentException(failure.code() + ": " + failure.message());
    var d = MAPPER.<JsonNode>valueToTree(((InvocationSuccess) result).decision());
    // DTO 的 null 字段在这里跳过；结构和整份状态先校验，避免执行一半才发现无效输出。
    var normalized = (ObjectNode) d;
    for (String key : List.of("nextState", "activities", "timers", "operations", "feedback"))
      if (normalized.path(key).isNull()) normalized.remove(key);
    JsonContracts.validate("Decision", normalized);
    validateDecision(normalized, available, operationCount[0]);
    requireActive();
    if (System.nanoTime() >= deadline)
      throw new IllegalArgumentException("Validation deadline exceeded");
    if (normalized.has("nextState")
        && !items.write(lease, snapshot.revision(), normalized.get("nextState")).written())
      throw new IllegalStateException("Actual item changed");
    for (var change : normalized.path("activities")) applyActivity(change, available);
    for (var timer : normalized.path("timers")) {
      String key = timer.path("key").asText();
      if (timer.path("action").asText().equals("cancel")) timers.remove(key);
      else {
        ObjectNode entry = timer.deepCopy();
        entry.put("due", tick + timer.path("afterTicks").asLong());
        timers.put(key, entry);
      }
    }
    List<JsonNode> receipts = new ArrayList<>();
    for (var op : normalized.path("operations")) {
      requireActive();
      if (System.nanoTime() >= deadline)
        throw new IllegalArgumentException("World operation budget exceeded");
      operationCount[0]++;
      try {
        var receipt = world.apply(MAPPER.treeToValue(op, Contracts.OperationRequest.class), lease);
        var json = MAPPER.<JsonNode>valueToTree(receipt);
        JsonContracts.validate("OperationReceipt", json);
        receipts.add(json);
      } catch (Exception e) {
        throw new IllegalStateException("World operation failed after possible side effect", e);
      }
    }
    for (var f : normalized.path("feedback")) feedback.show(f, available);
    for (var receipt : receipts) bind("operation.receipt", receipt, receipts);
    if (!receipts.isEmpty()) {
      var payload = MAPPER.createObjectNode();
      payload.set("receipts", MAPPER.valueToTree(receipts));
      process(
          new Contracts.RuleEvent(
              UUID.randomUUID().toString(), "operation.results", tick, "internal", payload),
          receipts,
          depth + 1,
          operationCount,
          deadline);
    }
  }

  private void validateDecision(JsonNode d, List<JsonNode> available, int count) {
    if (d.has("nextState")) definition.stateSchema().validate(d.path("nextState"));
    Set<String> keys = new HashSet<>();
    if (count + d.path("operations").size() > 32)
      throw new IllegalArgumentException("Operation limit");
    var futureActivities = new HashSet<>(activities.keySet());
    var futureTimers = new HashSet<>(timers.keySet());
    for (var c : d.path("activities")) {
      String key = c.path("key").asText(), action = c.path("action").asText();
      if (action.equals("begin")) {
        if (!futureActivities.add(key)) throw new IllegalArgumentException("Duplicate activity");
      } else if (!futureActivities.contains(key))
        throw new IllegalArgumentException("Unknown activity");
      if (action.equals("end")) {
        futureActivities.remove(key);
        for (var e : timers.entrySet())
          if (e.getValue().path("activityKey").asText().equals(key))
            futureTimers.remove(e.getKey());
      }
    }
    for (var t : d.path("timers")) {
      if (t.path("action").asText().equals("cancel")) futureTimers.remove(t.path("key").asText());
      else {
        if (t.path("afterTicks").asLong() > 72000)
          throw new IllegalArgumentException("Timer exceeds active lifetime limit");
        if (t.has("activityKey") && !futureActivities.contains(t.path("activityKey").asText()))
          throw new IllegalArgumentException("Timer activity missing");
        futureTimers.add(t.path("key").asText());
      }
    }
    if (futureActivities.size() > 16 || futureTimers.size() > 16)
      throw new IllegalArgumentException("Activity or timer limit");
    for (var op : d.path("operations")) {
      HostCapabilities.operation(op.path("capability").asText(), op.path("args"));
      if (!keys.add(op.path("key").asText())
          || !contains(
              definition.equipment().path("requires").path("operations"),
              op.path("capability").asText()))
        throw new IllegalArgumentException("Duplicate or undeclared operation");
      if (op.has("activityKey") && !futureActivities.contains(op.path("activityKey").asText()))
        throw new IllegalArgumentException("Operation activity missing");
    }
    for (var f : d.path("feedback")) {
      var cue =
          definition.equipment().path("presentation").path("cues").path(f.path("cueId").asText());
      if (cue.isMissingNode()) throw new IllegalArgumentException("Unknown cue");
      if (cue.path("category").asText().equals("result")) {
        boolean found = false;
        for (var id : f.path("receiptIds"))
          for (var receipt : available)
            if (receipt.path("receiptId").asText().equals(id.asText())
                && contains(cue.path("requiredReceiptStatus"), receipt.path("status").asText()))
              found = true;
        if (!found) throw new IllegalArgumentException("Result cue requires matching host receipt");
      }
    }
  }

  private void applyActivity(JsonNode c, List<JsonNode> receipts) {
    String key = c.path("key").asText(), action = c.path("action").asText();
    if (action.equals("end")) {
      activities.remove(key);
      timers.values().removeIf(t -> t.path("activityKey").asText().equals(key));
      feedback.stop(key);
      return;
    }
    ObjectNode a = activities.get(key);
    if (action.equals("begin")) {
      a =
          MAPPER
              .createObjectNode()
              .put("activityId", UUID.randomUUID().toString())
              .put("key", key)
              .put("startedTick", tick)
              .put("phaseTick", tick);
      activities.put(key, a);
    }
    boolean phaseChanged = c.has("phase") && !c.path("phase").equals(a.path("phase"));
    if (c.has("phase")) {
      a.set("phase", c.path("phase"));
      if (phaseChanged) a.put("phaseTick", tick);
    }
    if (c.has("data")) a.set("data", c.path("data"));
    if (phaseChanged) {
      feedback.stop(key);
      bind("activity.phase", a, receipts);
    }
  }

  private void bind(String trigger, JsonNode actual, List<JsonNode> receipts) {
    for (var b : definition.equipment().path("presentation").path("bindings")) {
      if (!b.path("trigger").asText().equals(trigger)) continue;
      boolean matches = true;
      for (var entry : b.path("match").properties())
        if (!entry.getValue().equals(actual.path(entry.getKey()))) matches = false;
      if (matches) {
        var f = MAPPER.createObjectNode().put("cueId", b.path("cueId").asText());
        if (actual.has("key") && trigger.equals("activity.phase"))
          f.put("activityKey", actual.path("key").asText());
        // 自动绑定也必须核对实际回执，不能绕过显式 feedback 的成功证据校验。
        var cue =
            definition.equipment().path("presentation").path("cues").path(b.path("cueId").asText());
        if (cue.path("category").asText().equals("result")) {
          if (!trigger.equals("operation.receipt")
              || !contains(cue.path("requiredReceiptStatus"), actual.path("status").asText()))
            continue;
          f.putArray("receiptIds").add(actual.path("receiptId").asText());
        }
        feedback.show(f, receipts);
      }
    }
  }

  /** 时间来自宿主游戏刻；未激活时计时器不继续执行，也不保存离线任务。 */
  public void advance(long now) {
    if (stopped || !active.getAsBoolean()) return;
    tick = now;
    var due =
        timers.entrySet().stream()
            .filter(e -> e.getValue().path("due").asLong() <= now)
            .map(Map.Entry::getKey)
            .toList();
    long deadline = System.nanoTime() + budgetNanos;
    long original = budgetNanos;
    for (String key : due) {
      if (System.nanoTime() >= deadline) break;
      budgetNanos = Math.min(original, deadline - System.nanoTime());
      var timer = timers.remove(key);
      if (timer == null) continue;
      timer.remove("due");
      timer.remove("action");
      timer.remove("afterTicks");
      onEvent(
          lease,
          new Contracts.RuleEvent(
              UUID.randomUUID().toString(), "timer.fired", now, "internal", timer));
    }
    budgetNanos = original;
  }

  public void stop() {
    stopped = true;
    timers.clear();
    activities.clear();
    feedback.stop(null);
  }

  public boolean stopped() {
    return stopped;
  }

  private void requireActive() {
    if (stopped || !active.getAsBoolean())
      throw new IllegalStateException("Activation is no longer valid");
  }

  public static boolean contains(JsonNode array, String value) {
    for (var x : array) if (x.asText().equals(value)) return true;
    return false;
  }
}
