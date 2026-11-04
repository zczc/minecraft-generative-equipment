package dev.generative.equipment.runtime;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.core.*;
import dev.generative.equipment.protocol.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
  static class Harness implements AutoCloseable {
    final QuickJsRuntime runtime = new QuickJsRuntime();
    final ActivationLease lease = QuickJsRuntimeTest.LEASE;
    final LoadedDefinition def;
    final RuleEngine engine;
    long revision = 0;
    JsonNode state;
    boolean active = true, accept = true;
    int operations = 0, clears = 0;
    final List<String> cues = new ArrayList<>();

    Harness(String replacement) throws Exception {
      var src =
          MAPPER.readTree(
              Files.readString(
                  Path.of(
                      System.getProperty("ge.repo"),
                      "tests/fixtures/equipment/pulse.source.json")));
      if (replacement != null)
        ((com.fasterxml.jackson.databind.node.ObjectNode) src).put("behaviorSource", replacement);
      var equipment = src.path("equipment");
      HostCapabilities.equipment(
          equipment, HostCapabilities.profile("test", QuickJsRuntime.ENGINE));
      state = equipment.path("initialState").deepCopy();
      def =
          new LoadedDefinition(
              runtime.prepare(
                  new VerifiedPackage(
                      lease.definition(),
                      "test",
                      QuickJsRuntime.ENGINE,
                      MAPPER.createObjectNode(),
                      src.path("behaviorSource").asText())),
              equipment,
              new StateSchema(equipment.path("stateSchema")),
              MAPPER.createObjectNode());
      engine =
          new RuleEngine(
              runtime,
              def,
              lease,
              new HostPorts.ItemStatePort() {
                public ItemSnapshot read(ActivationLease l) {
                  return new ItemSnapshot(revision, state);
                }

                public WriteResult write(ActivationLease l, long expected, JsonNode next) {
                  if (!active || expected != revision)
                    return new WriteResult(false, revision, "CONFLICT");
                  state = next;
                  return new WriteResult(true, ++revision, null);
                }
              },
              (name, args, l) -> {
                var data = MAPPER.createObjectNode();
                data.putArray("entities")
                    .addObject()
                    .put("ref", "mob")
                    .putObject("position")
                    .put("worldId", "world")
                    .put("x", 2)
                    .put("y", 64)
                    .put("z", 0);
                return new QueryResult(true, data, null);
              },
              (op, l) -> {
                operations++;
                return new Contracts.OperationReceipt(
                    UUID.randomUUID().toString(),
                    op.key(),
                    op.capability(),
                    accept ? "APPLIED" : "REJECTED",
                    accept ? "OK" : "REGION_DENIED",
                    MAPPER.createObjectNode(),
                    1L);
              },
              () -> active,
              () -> {
                var n = MAPPER.createObjectNode();
                n.putObject("position")
                    .put("worldId", "world")
                    .put("x", 0)
                    .put("y", 64)
                    .put("z", 0);
                return n;
              },
              new RuleEngine.Feedback() {
                public void show(JsonNode f, List<JsonNode> receipts) {
                  cues.add(f.path("cueId").asText());
                }

                public void stop(String key) {
                  clears++;
                }
              });
      engine.budgetNanos(1_000_000_000);
    }

    void event(String kind, long tick, JsonNode payload) {
      engine.onEvent(
          lease,
          new Contracts.RuleEvent(UUID.randomUUID().toString(), kind, tick, "internal", payload));
    }

    void event(String kind, long tick) {
      event(kind, tick, MAPPER.createObjectNode());
    }

    public void close() {
      engine.stop();
      runtime.close();
    }
  }

  // 在冷 JVM 中先初始化 schema/JNI；这是加载阶段成本，不混入实时事件预算。
  static {
    try (var r = new QuickJsRuntime()) {
      var helper = new QuickJsRuntimeTest();
      var d = helper.compile(r, "export function onEvent(){return {}}");
      for (int i = 0; i < 100; i++)
        r.invoke(d, helper.input(), (n, a, l) -> null, helper.budget(1000));
      JsonContracts.validate("Decision", MAPPER.createObjectNode());
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Test
  void chargesThenAppliesAndCommitsReceiptDependentState() throws Exception {
    try (var h = new Harness(null)) {
      h.event("item.used", 1);
      assertEquals(0, h.operations);
      assertEquals(1, h.engine.activitySnapshot().size());
      h.engine.advance(13);
      assertEquals(1, h.operations);
      assertEquals(1, h.state.path("uses").asInt());
      assertEquals(30, h.state.path("cooldown").asInt());
      assertEquals(0, h.engine.activitySnapshot().size());
      assertTrue(h.cues.contains("release"));
    }
  }

  @Test
  void rejectedWorldOperationDoesNotReportSuccess() throws Exception {
    try (var h = new Harness(null)) {
      h.accept = false;
      h.event("item.used", 1);
      h.engine.advance(13);
      assertEquals(0, h.state.path("uses").asInt());
      assertFalse(h.cues.contains("release"));
      assertTrue(h.cues.contains("blocked"));
    }
  }

  @Test
  void movementAndUnequipStopPendingTimersWithoutResettingState() throws Exception {
    try (var h = new Harness(null)) {
      h.event("item.used", 1);
      var payload = MAPPER.createObjectNode();
      payload.putObject("from").put("x", 0).put("z", 0);
      payload.putObject("to").put("x", 1).put("z", 0);
      h.event("actor.move.sampled", 2, payload);
      h.engine.advance(30);
      assertEquals(0, h.operations);
      h.event("item.used", 31);
      h.active = false;
      h.engine.stop();
      h.engine.advance(100);
      assertEquals(0, h.operations);
      assertEquals(0, h.state.path("uses").asInt());
      assertTrue(h.clears > 0);
    }
  }

  @Test
  void validatesEntireDecisionBeforeAnyMutation() throws Exception {
    try (var h =
        new Harness(
            "export function onEvent(){return"
                + " {nextState:{cooldown:1,uses:1},operations:[{key:'ok',capability:'entity.velocity.add',args:{target:'self',deltaVelocity:{x:0,y:1,z:0}}},{key:'bad',capability:'entity.velocity.add',args:{target:'self',deltaVelocity:{x:999,y:0,z:0}}}]}}")) {
      assertThrows(IllegalArgumentException.class, () -> h.event("item.used", 1));
      assertEquals(0, h.operations);
      assertEquals(0, h.revision);
      assertTrue(h.engine.stopped());
    }
  }

  @Test
  void stateSchemaRejectsRemoteRefsAndUnknownFields() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StateSchema(MAPPER.readTree("{\"$ref\":\"https://example.com\"}")));
    try (var h = new Harness(null)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              h.def
                  .stateSchema()
                  .validate(
                      MAPPER
                          .createObjectNode()
                          .put("cooldown", 0)
                          .put("uses", 1)
                          .put("hidden", 1)));
    }
  }
}
