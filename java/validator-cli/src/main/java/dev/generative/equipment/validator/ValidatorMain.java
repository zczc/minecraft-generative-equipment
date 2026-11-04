package dev.generative.equipment.validator;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.generative.equipment.core.*;
import dev.generative.equipment.protocol.*;
import dev.generative.equipment.runtime.QuickJsRuntime;
import java.nio.file.*;
import java.util.*;

/** 单次隔离验证入口。编译与场景运行复用正式运行器；报告只证明这些检查，不宣称长期平衡。 */
public final class ValidatorMain {
  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--probe")) {
      try (var runtime = new QuickJsRuntime()) {
        dev.generative.equipment.runtime.RuntimeWarmup.run(runtime);
        System.out.println("{\"ready\":true,\"engineBuildId\":\"" + QuickJsRuntime.ENGINE + "\"}");
      }
      return;
    }
    var report = MAPPER.createObjectNode().put("passed", false);
    var checks = report.putArray("checks");
    long start = System.nanoTime();
    try {
      if (args.length != 2 || !args[0].equals("--request"))
        throw new IllegalArgumentException("Usage: --request <path>");
      Path file = Path.of(args[1]);
      if (!Files.isRegularFile(file) || Files.size(file) > 1048576)
        throw new IllegalArgumentException("Invalid request file");
      var request = MAPPER.readTree(Files.readAllBytes(file));
      for (String key :
          List.of("validationId", "sourceHash", "engineBuildId", "capabilityProfileId"))
        report.set(key, request.path(key));
      if (!QuickJsRuntime.ENGINE.equals(request.path("engineBuildId").asText()))
        throw new IllegalArgumentException("Engine mismatch");
      var source = request.path("source");
      JsonContracts.prewarm();
      JsonContracts.validate("GeneratedSource", source);
      var equipment = source.path("equipment");
      HostCapabilities.equipment(equipment, request.path("profile"));
      pass(checks, "source-contract");
      var schema = new StateSchema(equipment.path("stateSchema"));
      schema.validate(equipment.path("initialState"));
      pass(checks, "state-schema");
      try (var runtime = new QuickJsRuntime()) {
        var ref = new Contracts.DefinitionRef("candidate", 1L);
        var program =
            runtime.prepare(
                new VerifiedPackage(
                    ref,
                    request.path("sourceHash").asText(),
                    QuickJsRuntime.ENGINE,
                    MAPPER.createObjectNode(),
                    source.path("behaviorSource").asText()));
        pass(checks, "quickjs-compilation");
        var definition =
            new LoadedDefinition(program, equipment, schema, MAPPER.createObjectNode());
        var scenarios = equipment.path("scenarios");
        if (!scenarios.isArray() || scenarios.size() < 2 || scenarios.size() > 8)
          throw new IllegalArgumentException("Between 2 and 8 executable scenarios required");
        Set<String> scenarioIds = new HashSet<>();
        boolean reachesWorld = false;
        for (var scenario : scenarios) {
          if (!scenarioIds.add(scenario.path("id").asText())
              || scenario.path("events").isEmpty()
              || scenario.path("events").size() > 64)
            throw new IllegalArgumentException("Invalid scenario id or events");
          int operations = scenario(runtime, definition, scenario, false);
          // 不允许用任意中间状态或伪造回执证明能力可达；至少一条完整使用路径必须走到世界操作。
          if (operations > 0
              && scenario.path("initialState").equals(equipment.path("initialState"))
              && scenario.path("events").get(0).path("kind").asText().equals("item.activated")
              && (scenario.path("expectedOperationCapabilities").size() > 0
                  || scenario.path("platformProbe").asBoolean(false))) reachesWorld = true;
        }
        if (!reachesWorld)
          throw new IllegalArgumentException(
              "Need a scenario from initialState and item.activated that reaches a world operation"
                  + "; platform probes try use/move/hit/wait through 240 ticks");
        for (var constraint : equipment.path("constraints"))
          for (var id : constraint.path("scenarioIds"))
            if (!scenarioIds.contains(id.asText()))
              throw new IllegalArgumentException("Constraint references unknown scenario");
        pass(checks, "declared-scenarios");
        // 平台另跑拒绝世界操作场景，要求能接受真实拒绝且保持状态合法；不沿用成功断言。
        for (var scenario : scenarios) scenario(runtime, definition, scenario, true);
        pass(checks, "rejected-world-operations");
        for (var entry : equipment.path("presentation").path("cues").properties()) {
          var cue = entry.getValue();
          if (cue.has("recipe")) continue;
          var lease =
              new ActivationLease(
                  UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "main_hand", 1, ref);
          for (int elapsed : new int[] {0, 1, 10}) {
            var frame =
                MAPPER
                    .createObjectNode()
                    .put("cueId", entry.getKey())
                    .put("elapsedTicks", elapsed)
                    .putNull("progress");
            frame.putNull("activity");
            frame
                .putObject("anchors")
                .putObject("actor")
                .put("worldId", "test-world")
                .put("x", 0)
                .put("y", 64)
                .put("z", 0);
            frame.putArray("receipts");
            frame.putObject("data");
            var output =
                runtime.render(
                    program,
                    new RenderInvocation(
                        lease,
                        equipment.path("initialState"),
                        equipment.path("parameters"),
                        frame,
                        elapsed),
                    new ExecutionBudget(8388608, System.nanoTime() + 100_000_000, 0, 0));
            if (output.ok()) VisualPolicy.validate(output.frame());
            if (!output.ok())
              throw new IllegalArgumentException("render failed for cue " + entry.getKey());
          }
        }
        pass(checks, "presentation-contract");
      }
      report.put("passed", true);
    } catch (Throwable e) {
      var check = checks.addObject().put("name", "candidate-validation").put("status", "FAIL");
      check
          .putArray("diagnostics")
          .add(
              String.valueOf(e.getMessage())
                  .substring(0, Math.min(512, String.valueOf(e.getMessage()).length())));
    }
    report.putArray("diagnostics");
    for (var check : checks)
      for (var diag : check.path("diagnostics")) ((ArrayNode) report.path("diagnostics")).add(diag);
    report.putObject("metrics").put("durationMs", (System.nanoTime() - start) / 1e6);
    report.putNull("compiledArtifact");
    System.out.println(MAPPER.writeValueAsString(report));
    System.exit(report.path("passed").asBoolean() ? 0 : 2);
  }

  private static <T> Iterable<T> iterable(Iterator<T> iterator) {
    return () -> iterator;
  }

  private static void pass(ArrayNode checks, String name) {
    checks.addObject().put("name", name).put("status", "PASS").putArray("diagnostics");
  }

  private static int scenario(
      QuickJsRuntime runtime, LoadedDefinition definition, JsonNode test, boolean reject)
      throws Exception {
    var lease =
        new ActivationLease(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "main_hand",
            1,
            definition.program().ref());
    JsonNode[] state = {
      test.has("initialState")
          ? test.path("initialState").deepCopy()
          : definition.equipment().path("initialState").deepCopy()
    };
    long[] revision = {0};
    var calls = new ArrayList<String>();
    var issuedRefs = new HashSet<String>(Set.of("self"));
    JsonNode[] currentEvent = {MAPPER.createObjectNode()};
    for (String query : iterable(test.path("queries").fieldNames()))
      if (!RuleEngine.contains(definition.equipment().path("requires").path("queries"), query))
        throw new IllegalArgumentException("Scenario mock uses undeclared query " + query);
    definition.stateSchema().validate(state[0]);
    try {
      var engine =
          new RuleEngine(
              runtime,
              definition,
              lease,
              new HostPorts.ItemStatePort() {
                public ItemSnapshot read(ActivationLease l) {
                  return new ItemSnapshot(revision[0], state[0]);
                }

                public WriteResult write(ActivationLease l, long expected, JsonNode next) {
                  state[0] = next;
                  return new WriteResult(true, ++revision[0], null);
                }
              },
              (name, args, l) -> {
                var q = test.path("queries").path(name);
                // 模拟宿主的引用签发规则，不能让虚构的 target / eventHandle 在测试里假成功。
                if (q.path("ok").asBoolean()) {
                  var data = q.path("data");
                  if (data.has("ref")) issuedRefs.add(data.path("ref").asText());
                  if (data.path("entity").has("ref"))
                    issuedRefs.add(data.path("entity").path("ref").asText());
                  for (var entity : data.path("entities"))
                    if (entity.has("ref")) issuedRefs.add(entity.path("ref").asText());
                }
                return q.isMissingNode()
                    ? new QueryResult(false, NullNode.instance, "NO_MOCK")
                    : new QueryResult(
                        q.path("ok").asBoolean(),
                        q.path("data"),
                        q.path("code").isNull() ? null : q.path("code").asText());
              },
              (op, l) -> {
                if (op.capability().equals("combat.damage.set")) {
                  if (!currentEvent[0].path("kind").asText().equals("combat.damage.prepare")
                      || !op.args()
                          .path("eventHandle")
                          .equals(currentEvent[0].path("payload").path("eventHandle")))
                    throw new IllegalArgumentException(
                        "combat.damage.set needs the current combat.damage.prepare eventHandle");
                } else if (!issuedRefs.contains(op.args().path("target").asText())) {
                  throw new IllegalArgumentException(
                      "World operation target was not issued by a query");
                }
                calls.add(op.capability());
                return new Contracts.OperationReceipt(
                    UUID.randomUUID().toString(),
                    op.key(),
                    op.capability(),
                    reject
                        ? "REJECTED"
                        : op.capability().equals("entity.velocity.add") ? "APPLIED" : "PROPOSED",
                    reject ? "REGION_DENIED" : "SIMULATED",
                    MAPPER.createObjectNode(),
                    1L);
              },
              () -> true,
              () -> {
                var n =
                    MAPPER
                        .createObjectNode()
                        .put("ref", "self")
                        .put("health", 20)
                        .put("onGround", true)
                        .put("sneaking", false);
                n.putObject("position")
                    .put("worldId", "test-world")
                    .put("x", 0)
                    .put("y", 64)
                    .put("z", 0);
                n.putObject("velocity").put("x", 0).put("y", 0).put("z", 0);
                return n;
              },
              new RuleEngine.Feedback() {
                public void show(JsonNode signal, List<JsonNode> receipts) {}

                public void stop(String key) {}
              });
      engine.budgetNanos(100_000_000);
      long previous = -1;
      for (var event : test.path("events")) {
        JsonContracts.validate("RuleEvent", event);
        long tick = event.path("tick").asLong();
        if (tick < previous) throw new IllegalArgumentException("Scenario ticks must not decrease");
        if (tick > 1200) throw new IllegalArgumentException("Scenario exceeds 1200 ticks");
        String kind = event.path("kind").asText();
        if (Set.of("timer.fired", "operation.results").contains(kind))
          throw new IllegalArgumentException(
              "Do not forge internal events; use test.advance to run real timers/receipts");
        if (!kind.equals("test.advance")
            && !RuleEngine.contains(HostCapabilities.DOCUMENT.path("events"), kind))
          throw new IllegalArgumentException("Unknown scenario event " + kind);
        currentEvent[0] = MAPPER.createObjectNode();
        for (long now = previous + 1; now <= tick; now++) engine.advance(now);
        previous = tick;
        currentEvent[0] = event;
        if (kind.equals("combat.damage.prepare"))
          issuedRefs.add(event.path("payload").path("target").asText());
        engine.onEvent(lease, MAPPER.treeToValue(event, Contracts.RuleEvent.class));
      }
      if (!reject) {
        if (test.has("expectedState") && !state[0].equals(test.path("expectedState")))
          throw new IllegalArgumentException(
              "Scenario " + test.path("id").asText() + " state mismatch: actual=" + state[0]);
        if (test.has("expectedOperationCapabilities")) {
          var expected = new ArrayList<String>();
          test.path("expectedOperationCapabilities").forEach(x -> expected.add(x.asText()));
          if (!calls.equals(expected))
            throw new IllegalArgumentException("Scenario operation sequence mismatch");
        }
      }
      engine.stop();
      return calls.size();
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Scenario " + test.path("id").asText() + " (reject=" + reject + "): " + e.getMessage(),
          e);
    }
  }
}
