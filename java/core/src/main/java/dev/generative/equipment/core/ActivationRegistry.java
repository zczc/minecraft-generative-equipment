package dev.generative.equipment.core;

import static dev.generative.equipment.core.RuntimeTypes.*;

import java.util.*;
import java.util.function.Consumer;

/** 管理本次激活的执行归属，全部数据只在内存；不代表所有权或全服唯一性。 */
public final class ActivationRegistry {
  private final Map<UUID, ActivationLease> active = new HashMap<>();

  /** 新激活不能复用已有编号；物品复制也应获得独立的运行期凭据。 */
  public void register(ActivationLease lease) {
    if (active.putIfAbsent(lease.activationId(), lease) != null)
      throw new IllegalStateException("Duplicate activation");
  }

  public boolean isActive(ActivationLease lease) {
    return lease.equals(active.get(lease.activationId()));
  }

  public int size() {
    return active.size();
  }

  public void stopPlayer(UUID player, Consumer<ActivationLease> cleanup) {
    stop(active.values().stream().filter(x -> x.playerId().equals(player)).toList(), cleanup);
  }

  public void stopAll(Consumer<ActivationLease> cleanup) {
    stop(List.copyOf(active.values()), cleanup);
  }

  /** 先撤销凭据，再逐一清理；某次清理失败也不能阻止其余凭据失效。 */
  private void stop(List<ActivationLease> leases, Consumer<ActivationLease> cleanup) {
    RuntimeException failure = null;
    for (var lease : leases) {
      active.remove(lease.activationId());
      try {
        cleanup.accept(lease);
      } catch (RuntimeException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }
}
