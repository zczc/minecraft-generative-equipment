import { SqliteStore } from "../adapters/sqlite-store.js";
import type { ModelProvider, CandidateValidator } from "../ports/index.js";
import { ApiFault, notImplemented } from "../errors.js";
/**
 * 后台任务编排入口：只负责状态和依赖调用，模型与验证逻辑通过 port 注入。
 * 当前发布模块尚未实现，即使测试替身返回通过，也绝不产生 READY 或发放物品。
 */
export class GenerationWorker {
  private busy = false;
  private controller = new AbortController();
  private pending: Promise<void> | null = null;
  constructor(
    private store: SqliteStore,
    private model: ModelProvider,
    private validator: CandidateValidator,
  ) {}
  /** 合并并发唤醒；同一个进程不能同时跑两轮任务扫描。 */
  runOnce(): Promise<void> {
    if (this.pending) return this.pending;
    this.pending = this.run().finally(() => {
      this.pending = null;
    });
    return this.pending;
  }
  private async run() {
    if (this.busy || this.controller.signal.aborted) return;
    this.busy = true;
    try {
      for (const row of this.store.queued()) {
        if (this.controller.signal.aborted) break;
        const active = this.store.transition(
          row.task_id,
          row.host_id,
          row.revision,
          "QUEUED",
          "GENERATING",
        );
        try {
          const candidate = await this.model.generate(
            JSON.parse(row.resolved_json),
            this.controller.signal,
          );
          const current = this.store.get(row.task_id, row.host_id)!;
          // 等待模型期间任务可能被取消；先复核状态，再进入验证。
          if (current.status !== "GENERATING") continue;
          const validating = this.store.transition(
            row.task_id,
            row.host_id,
            current.revision,
            "GENERATING",
            "VALIDATING",
          );
          const report = await this.validator.validate(
            candidate.source,
            this.controller.signal,
          );
          if (!report.passed)
            throw new ApiFault(
              422,
              "VALIDATION_FAILED",
              "Candidate failed validation",
            );
          void validating;
          throw notImplemented("Publishing validated definitions");
        } catch (e) {
          const current = this.store.get(active.task_id, active.host_id);
          if (
            current &&
            !["CANCELLED", "FAILED", "READY"].includes(current.status)
          )
            this.store.transition(
              current.task_id,
              current.host_id,
              current.revision,
              current.status,
              "FAILED",
              {
                code: e instanceof ApiFault ? e.code : "GENERATION_FAILED",
                message:
                  e instanceof ApiFault
                    ? e.message
                    : "Generation did not complete.",
              },
            );
        }
      }
    } finally {
      this.busy = false;
    }
  }
  async stop() {
    this.controller.abort();
    await this.pending;
  }
}
