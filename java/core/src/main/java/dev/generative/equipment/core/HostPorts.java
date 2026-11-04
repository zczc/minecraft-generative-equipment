package dev.generative.equipment.core;

import static dev.generative.equipment.core.RuntimeTypes.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.protocol.Contracts;

public final class HostPorts {
  private HostPorts() {}

  /** 同步脚本边界。正式调用计入游戏刻预算，不可等待网络或数据库。 */
  public interface RuleRuntime extends AutoCloseable {
    PreparedDefinition prepare(VerifiedPackage source);

    InvocationResult invoke(
        PreparedDefinition definition, Invocation input, QueryPort queries, ExecutionBudget budget);

    RenderResult render(
        PreparedDefinition definition, RenderInvocation input, ExecutionBudget budget);

    @Override
    void close();
  }

  /** 仅由主线程适配器读取允许的世界快照，不能把 Java 对象交给脚本。 */
  public interface QueryPort {
    QueryResult query(String capability, JsonNode args, ActivationLease lease);
  }

  /** 校验阶段、权限和目标后应用请求；回执不承诺多操作事务。 */
  public interface WorldOperationPort {
    Contracts.OperationReceipt apply(Contracts.OperationRequest request, ActivationLease lease);
  }

  /** 核对激活凭据和修订号后写回实际物品，防止异步结果写到另一件装备。 */
  public interface ItemStatePort {
    ItemSnapshot read(ActivationLease lease);

    WriteResult write(ActivationLease lease, long expectedRevision, JsonNode nextState);
  }

  /** 只接收已校验表现，资源归属当前活动，失活时必须清理。 */
  public interface PresentationPort {
    void accept(JsonNode verifiedFeedback);

    void renderTick(long tick);

    void clear(ActivationLease lease);
  }

  /** 非阻塞记录已发生事实；不能凭请求就记录为最终成功。 */
  public interface ObservationSink {
    void offer(Contracts.Observation observation);
  }
}
