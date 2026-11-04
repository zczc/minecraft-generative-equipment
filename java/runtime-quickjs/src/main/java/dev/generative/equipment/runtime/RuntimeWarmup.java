package dev.generative.equipment.runtime;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import dev.generative.equipment.protocol.*;
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
  }
}
