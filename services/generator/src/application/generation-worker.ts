import { SqliteStore } from "../adapters/sqlite-store.js";
import { DefinitionRepository } from "../adapters/definitions.js";
import type {
  ModelProvider,
  CandidateValidator,
  GenerationInput,
  ModelResult,
  ValidationReport,
} from "../ports/index.js";
import { ApiFault } from "../errors.js";
import { hashJson } from "@ge/protocol";
/** 串行后台生成；每次验证失败才进行有上限的修复，取消信号同时传到 HTTP 和验证子进程。 */
export class GenerationWorker {
  private pending: Promise<void> | null = null;
  private stopping = false;
  private active = new Map<string, AbortController>();
  constructor(
    private store: SqliteStore,
    private model: ModelProvider,
    private validator: CandidateValidator,
    private definitions: DefinitionRepository,
  ) {}
  runOnce() {
    if (this.pending) return this.pending;
    this.pending = this.run().finally(() => {
      this.pending = null;
    });
    return this.pending;
  }
  cancel(taskId: string) {
    this.active.get(taskId)?.abort();
  }
  private async run() {
    for (const row of this.store.queued()) {
      if (this.stopping) break;
      const controller = new AbortController();
      this.active.set(row.task_id, controller);
      try {
        this.store.transition(
          row.task_id,
          row.host_id,
          row.revision,
          "QUEUED",
          "GENERATING",
        );
        const input = JSON.parse(row.resolved_json) as GenerationInput;
        let result: ModelResult | null = null;
        let diagnostics: string[] = [];
        let previousHash: string | null = null;
        for (
          let attempt = 0;
          attempt <= Math.min(2, input.policy.repairLimit);
          attempt++
        ) {
          controller.signal.throwIfAborted();
          result = await this.model.generate(
            input,
            controller.signal,
            attempt
              ? {
                  ...(result?.source ? { source: result.source } : {}),
                  ...(result?.rawText !== undefined
                    ? { rawText: result.rawText }
                    : {}),
                  diagnostics,
                }
              : undefined,
          );
          let current = this.store.get(row.task_id, row.host_id)!;
          if (["CANCELLED", "FAILED"].includes(current.status)) break;
          current = this.store.transition(
            row.task_id,
            row.host_id,
            current.revision,
            current.status,
            "VALIDATING",
          );
          const report: ValidationReport = result.source
            ? await this.validator.validate(
                result.source,
                controller.signal,
                input.capabilityProfile,
              )
            : {
                passed: false,
                sourceHash: hashJson({ rawText: result.rawText }),
                engineBuildId: input.capabilityProfile.engineBuildId,
                capabilityProfileId: input.capabilityProfile.profileId,
                checks: [{ name: "module-output", status: "FAIL" }],
                diagnostics: result.diagnostics ?? [
                  "Model module could not be parsed",
                ],
              };
          await this.definitions.saveCandidate(
            row.task_id,
            attempt,
            result,
            report,
          );
          this.definitions.attempt(row.task_id, attempt, result, report);
          controller.signal.throwIfAborted();
          current = this.store.get(row.task_id, row.host_id)!;
          if (current.status !== "VALIDATING") break;
          if (report.passed) {
            await this.definitions.publish(current, input, result, report);
            break;
          }
          diagnostics = report.diagnostics.slice(0, 8);
          // 没有正文通常是思考耗尽输出预算；重复同一个请求不能修复不存在的程序。
          if (!result.source && result.rawText?.trim() === "")
            throw new ApiFault(
              422,
              "MODEL_OUTPUT_EMPTY",
              "模型没有返回程序，请调整思考模式或输出预算",
              diagnostics,
            );
          // 完全相同的失败候选再次出现时停止付费重试；仍保存本次真实用量和诊断。
          if (previousHash === report.sourceHash)
            throw new ApiFault(
              422,
              "REPAIR_UNCHANGED",
              "模型重复返回相同的失败程序",
              diagnostics,
            );
          previousHash = report.sourceHash;
          if (attempt === Math.min(2, input.policy.repairLimit))
            throw new ApiFault(
              422,
              "VALIDATION_FAILED",
              "装备验证未通过",
              diagnostics,
            );
          this.store.transition(
            row.task_id,
            row.host_id,
            current.revision,
            "VALIDATING",
            "REPAIRING",
          );
        }
      } catch (e) {
        const current = this.store.get(row.task_id, row.host_id);
        if (
          current &&
          !["CANCELLED", "FAILED", "READY"].includes(current.status)
        )
          this.store.transition(
            row.task_id,
            row.host_id,
            current.revision,
            current.status,
            "FAILED",
            {
              code: e instanceof ApiFault ? e.code : "GENERATION_FAILED",
              message:
                e instanceof ApiFault ? e.message : "生成或验证没有完成。",
            },
          );
      } finally {
        this.active.delete(row.task_id);
      }
    }
  }
  async stop() {
    this.stopping = true;
    for (const controller of this.active.values()) controller.abort();
    await this.pending;
  }
}
