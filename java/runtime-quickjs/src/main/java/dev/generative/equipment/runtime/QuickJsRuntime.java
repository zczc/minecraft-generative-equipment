package dev.generative.equipment.runtime;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.generative.equipment.core.HostPorts.*;
import dev.generative.equipment.protocol.*;
import java.nio.charset.StandardCharsets;

/** 每次事件使用新上下文；字节码只由本机固定引擎从已校验源码编译，不共享 JS 全局状态。 */
public final class QuickJsRuntime implements RuleRuntime {
  public static final String ENGINE = "quickjs-2026-06-04-ge1";
  private volatile boolean closed;

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Runtime closed");
  }

  public PreparedDefinition prepare(VerifiedPackage source) {
    requireOpen();
    if (!ENGINE.equals(source.engineBuildId()))
      throw new IllegalArgumentException("Engine version mismatch");
    return new PreparedDefinition(
        source.ref(),
        source.sourceHash(),
        ENGINE,
        NativeBridge.compile(source.source().getBytes(StandardCharsets.UTF_8)));
  }

  public InvocationResult invoke(
      PreparedDefinition definition, Invocation input, QueryPort queries, ExecutionBudget budget) {
    try {
      requireOpen();
      var ctx = context(input.lease(), input.state(), input.parameters(), input.event().tick());
      ctx.set("self", input.self());
      ctx.set("activities", input.activities());
      ctx.put("seed", input.event().eventId().hashCode() | 1);
      byte[] output =
          execute(
              definition,
              ctx,
              MAPPER.valueToTree(input.event()),
              false,
              (name, args) -> {
                try {
                  var result =
                      queries.query(
                          new String(name, StandardCharsets.UTF_8),
                          MAPPER.readTree(args),
                          input.lease());
                  return MAPPER.writeValueAsBytes(result);
                } catch (Exception e) {
                  return "{\"ok\":false,\"code\":\"QUERY_FAILED\",\"data\":null}"
                      .getBytes(StandardCharsets.UTF_8);
                }
              },
              budget);
      JsonNode decision = MAPPER.readTree(output);
      JsonContracts.validate("Decision", decision);
      if (decision.path("operations").size() > budget.maxOperations())
        throw new IllegalArgumentException("Operation budget exceeded");
      return new InvocationSuccess(MAPPER.treeToValue(decision, Contracts.Decision.class));
    } catch (Exception e) {
      return new InvocationFailure("EXECUTION_FAILED", String.valueOf(e.getMessage()));
    }
  }

  /** 表现没有查询桥；返回结构只能是 VisualFrame，世界操作等字段会被拒绝。 */
  public RenderResult render(
      PreparedDefinition definition, RenderInvocation input, ExecutionBudget budget) {
    try {
      requireOpen();
      var ctx = context(input.lease(), input.state(), input.parameters(), input.tick());
      var frame = MAPPER.readTree(execute(definition, ctx, input.frame(), true, null, budget));
      JsonContracts.validate("VisualFrame", frame);
      return new RenderResult(true, frame, null);
    } catch (Exception e) {
      return new RenderResult(false, null, "RENDER_FAILED");
    }
  }

  private byte[] execute(
      PreparedDefinition def,
      JsonNode ctx,
      JsonNode event,
      boolean render,
      NativeBridge.Queries query,
      ExecutionBudget budget)
      throws Exception {
    if (!ENGINE.equals(def.engineBuildId())) throw new IllegalArgumentException("Engine mismatch");
    long remaining = budget.deadlineNanos() - System.nanoTime();
    if (remaining <= 0) throw new IllegalArgumentException("Deadline exceeded");
    byte[] output =
        NativeBridge.execute(
            def.bytecode(),
            MAPPER.writeValueAsBytes(ctx),
            MAPPER.writeValueAsBytes(event),
            render,
            query,
            budget.memoryBytes(),
            remaining,
            budget.maxQueries());
    if (System.nanoTime() > budget.deadlineNanos())
      throw new IllegalArgumentException("Full invocation deadline exceeded");
    return output;
  }

  private ObjectNode context(
      ActivationLease lease, JsonNode state, JsonNode parameters, long tick) {
    var ctx = MAPPER.createObjectNode();
    ctx.set("state", state);
    ctx.set("parameters", parameters);
    ctx.putObject("clock")
        .put("worldSessionId", lease.worldSessionId().toString())
        .put("tick", tick);
    return ctx;
  }

  public void close() {
    closed = true;
  }
}
