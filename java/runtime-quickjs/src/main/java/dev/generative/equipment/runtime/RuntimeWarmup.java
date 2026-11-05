package dev.generative.equipment.runtime;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.core.*;
import dev.generative.equipment.protocol.*;
import java.util.List;
import java.util.UUID;

/** 只运行平台自带的无世界操作程序，在后台初始化 JNI、序列化和 schema 缓存。 */
public final class RuntimeWarmup {
  private RuntimeWarmup() {}

  public static void run(QuickJsRuntime runtime) {
    JsonContracts.prewarm();
    var ref = new Contracts.DefinitionRef("platform-warmup", 1L);
    var program =
        runtime.prepare(
            new VerifiedPackage(
                ref,
                "platform",
                QuickJsRuntime.ENGINE,
                MAPPER.createObjectNode(),
                "export function onEvent(c){return"
                    + " {nextState:c.state,activities:[],timers:[],operations:[],feedback:[]}}"));
    var lease =
        new ActivationLease(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "main_hand", 1, ref);
    for (int i = 0; i < 50; i++) {
      var event =
          new Contracts.RuleEvent(
              "warmup-" + i, "item.used", (long) i, "internal", MAPPER.createObjectNode());
      var result =
          runtime.invoke(
              program,
              new Invocation(
                  lease,
                  event,
                  MAPPER.createObjectNode(),
                  MAPPER.createObjectNode(),
                  MAPPER.createArrayNode()),
              (name, args, l) -> new QueryResult(false, MAPPER.nullNode(), "UNAVAILABLE"),
              new ExecutionBudget(8388608, System.nanoTime() + 1_000_000_000, 32, 0));
      if (!(result instanceof InvocationSuccess))
        throw new IllegalStateException("QuickJS startup probe failed: " + result);
    }
    warmEngine(runtime);
  }

  /**
   * 预热完整编排路径，而不只调用 JS。首次 DTO 转换、状态校验和回执递归不能落在玩家的 5 毫秒预算里。 程序为平台常量，物品、查询、世界操作和表现全部在内存中模拟，不读取或修改
   * Minecraft 世界。
   */
  private static void warmEngine(QuickJsRuntime runtime) {
    try {
      var equipment =
          MAPPER.readTree(
              """
              {"subscriptions":["item.activated","item.used","timer.fired","operation.results"],
               "requires":{"queries":["actor.status"],"operations":["entity.velocity.add"]},
               "parameters":{},"initialState":{"次数":0},
               "stateSchema":{"type":"object","properties":{"次数":{"type":"integer","minimum":0,"maximum":1000}},
                 "required":["次数"],"additionalProperties":false},
               "presentation":{"cues":{"warm":{"category":"ongoing","durationTicks":2}},"bindings":[]}}
              """);
      var ref = new Contracts.DefinitionRef("platform-engine-warmup", 1L);
      var program =
          runtime.prepare(
              new VerifiedPackage(
                  ref,
                  "platform",
                  QuickJsRuntime.ENGINE,
                  MAPPER.createObjectNode(),
                  """
                  export function onEvent(c,e){
                    if(e.kind==='item.activated')return {nextState:c.state};
                    if(e.kind==='item.used'){
                      c.query('actor.status',{target:'self'});
                      return {nextState:{次数:c.state.次数+1},
                        activities:[{action:'begin',key:'warm',phase:'active',data:{}}],
                        timers:[{action:'set',key:'warm',afterTicks:1,activityKey:'warm',payload:{}}],
                        operations:[],feedback:[{cueId:'warm'}]};
                    }
                    if(e.kind==='timer.fired')return {operations:[{key:'warm',capability:'entity.velocity.add',
                        args:{target:'self',deltaVelocity:{x:0,y:0.1,z:0}}}]};
                    if(e.kind==='operation.results')return {activities:[{action:'end',key:'warm',reason:'done'}]};
                    return {};
                  }
                  export function render(c,f){return {primitives:[{kind:'text',key:'warm',channel:'actionbar',text:'ready',ttlTicks:1}]};}
                  """));
      var definition =
          new LoadedDefinition(
              program,
              equipment,
              new StateSchema(equipment.path("stateSchema")),
              MAPPER.createObjectNode());
      var lease =
          new ActivationLease(
              UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "main_hand", 1, ref);
      JsonNode[] state = {equipment.path("initialState").deepCopy()};
      long[] revision = {0};
      var self = MAPPER.createObjectNode().put("ref", "self");
      self.putObject("position").put("worldId", "warmup").put("x", 0).put("y", 64).put("z", 0);
      var engine =
          new RuleEngine(
              runtime,
              definition,
              lease,
              new HostPorts.ItemStatePort() {
                public ItemSnapshot read(ActivationLease ignored) {
                  return new ItemSnapshot(revision[0], state[0]);
                }

                public WriteResult write(ActivationLease ignored, long expected, JsonNode next) {
                  if (expected != revision[0])
                    throw new IllegalStateException("Warmup revision mismatch");
                  state[0] = next;
                  return new WriteResult(true, ++revision[0], null);
                }
              },
              (name, args, ignored) -> new QueryResult(true, self, null),
              (operation, ignored) ->
                  new Contracts.OperationReceipt(
                      UUID.randomUUID().toString(),
                      operation.key(),
                      operation.capability(),
                      "REJECTED",
                      "WARMUP_ONLY",
                      MAPPER.createObjectNode(),
                      1L),
              () -> true,
              () -> self,
              new RuleEngine.Feedback() {
                public void show(JsonNode signal, List<JsonNode> receipts) {}

                public void stop(String key) {}
              });
      engine.budgetNanos(1_000_000_000);
      try {
        for (int i = 0; i < 100; i++) {
          long tick = i * 3L;
          engine.onEvent(
              lease,
              new Contracts.RuleEvent(
                  "warmup-activate-" + i,
                  "item.activated",
                  tick,
                  "internal",
                  MAPPER.createObjectNode()));
          engine.onEvent(
              lease,
              new Contracts.RuleEvent(
                  "warmup-use-" + i, "item.used", tick + 1, "internal", MAPPER.createObjectNode()));
          engine.advance(tick + 2);
          var rendered =
              runtime.render(
                  program,
                  new RenderInvocation(
                      lease,
                      state[0],
                      equipment.path("parameters"),
                      MAPPER.createObjectNode(),
                      tick),
                  new ExecutionBudget(8388608, System.nanoTime() + 1_000_000_000, 0, 0));
          if (!rendered.ok()) throw new IllegalStateException("Presentation warmup failed");
          VisualPolicy.validate(rendered.frame());
        }
      } finally {
        engine.stop();
      }
      if (state[0].path("次数").asInt() != 100)
        throw new IllegalStateException("Incomplete engine warmup");
    } catch (Exception e) {
      throw new IllegalStateException("Engine warmup failed", e);
    }
  }
}
