package dev.generative.equipment.runtime;
import dev.generative.equipment.core.HostPorts.*;
import dev.generative.equipment.core.UnavailableFeatureException;
import static dev.generative.equipment.core.RuntimeTypes.*;
/** S0 运行器适配器：隔离与中断能力未验证前，所有执行请求明确失败，不运行生成代码。 */
public final class QuickJsRuntime implements RuleRuntime {
    private boolean closed;
    private void requireOpen() { if (closed) throw new IllegalStateException("Runtime closed"); }
    public PreparedDefinition prepare(VerifiedPackage source) { requireOpen(); throw new UnavailableFeatureException("QuickJS compilation"); }
    public InvocationResult invoke(PreparedDefinition definition, Invocation input, QueryPort queries, ExecutionBudget budget) {
        requireOpen(); return new InvocationFailure("NOT_IMPLEMENTED", "QuickJS execution is unavailable in S0");
    }
    public RenderResult render(PreparedDefinition definition, RenderInvocation input, ExecutionBudget budget) {
        requireOpen(); return new RenderResult(false, null, "NOT_IMPLEMENTED");
    }
    public void close() { closed = true; }
}
