import { DatabaseSync } from "node:sqlite";
import { readFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import {
  hashJson,
  type CapabilityProfile,
  type GenerationRequest,
  type GenerationView,
} from "@ge/protocol";
import { ApiFault } from "../errors.js";
export type TaskRow = {
  task_id: string;
  host_id: string;
  input_hash: string;
  input_json: string;
  resolved_json: string;
  status: GenerationView["status"];
  revision: number;
  created_at: string;
  error_json: string | null;
};
/**
 * 仅供后台生成进程使用的 SQLite 仓储，不能放进 Paper 实时事件路径。
 * 原始请求决定幂等性，resolved_json 保存材料与规则展开后的冻结快照。
 */
export class SqliteStore {
  readonly db: DatabaseSync;
  constructor(dir: string) {
    mkdirSync(dir, { recursive: true });
    this.db = new DatabaseSync(join(dir, "generator.sqlite"));
    this.db.exec(
      "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;",
    );
    this.db.exec(
      readFileSync(
        new URL("../../migrations/001-initial.sql", import.meta.url),
        "utf8",
      ),
    );
  }
  close() {
    this.db.close();
  }
  profile(id: string, hostId: string): CapabilityProfile | null {
    const row = this.db
      .prepare(
        "SELECT profile_json FROM capability_profiles WHERE profile_id=? AND host_id=?",
      )
      .get(id, hostId) as { profile_json: string } | undefined;
    return row ? JSON.parse(row.profile_json) : null;
  }
  /** 能力清单不可覆盖；修改宿主能力必须使用新的 profileId。 */
  saveProfile(profile: CapabilityProfile): boolean {
    const hash = hashJson(profile);
    const row = this.db
      .prepare(
        "SELECT host_id,profile_hash FROM capability_profiles WHERE profile_id=?",
      )
      .get(profile.profileId) as
      | { host_id: string; profile_hash: string }
      | undefined;
    if (row) {
      if (row.host_id !== profile.hostId || row.profile_hash !== hash)
        throw new ApiFault(
          409,
          "IDEMPOTENCY_CONFLICT",
          "Profile id already has different content",
        );
      return false;
    }
    this.db
      .prepare("INSERT INTO capability_profiles VALUES(?,?,?,?)")
      .run(profile.profileId, profile.hostId, hash, JSON.stringify(profile));
    return true;
  }
  get(id: string, hostId: string): TaskRow | null {
    return (
      (this.db
        .prepare("SELECT * FROM generation_tasks WHERE task_id=? AND host_id=?")
        .get(id, hostId) as TaskRow | undefined) ?? null
    );
  }
  /** 同一 taskId 的相同请求返回已有任务，不重新读取或覆盖冻结输入。 */
  create(
    request: GenerationRequest,
    resolved: unknown,
  ): { created: boolean; row: TaskRow } {
    const hash = hashJson(request);
    const previous = this.db
      .prepare("SELECT * FROM generation_tasks WHERE task_id=?")
      .get(request.taskId) as TaskRow | undefined;
    if (previous) {
      if (previous.host_id !== request.hostId || previous.input_hash !== hash)
        throw new ApiFault(
          409,
          "IDEMPOTENCY_CONFLICT",
          "Task id already has different input",
        );
      return { created: false, row: previous };
    }
    this.db
      .prepare("INSERT INTO generation_tasks VALUES(?,?,?,?,?,?,1,?,NULL)")
      .run(
        request.taskId,
        request.hostId,
        hash,
        JSON.stringify(request),
        JSON.stringify(resolved),
        "QUEUED",
        new Date().toISOString(),
      );
    return { created: true, row: this.get(request.taskId, request.hostId)! };
  }
  /** 同时检查来源状态和修订号，阻止取消后的旧 worker 结果覆盖新状态。 */
  transition(
    id: string,
    hostId: string,
    revision: number,
    from: string,
    to: string,
    error: unknown = null,
  ): TaskRow {
    const allowed: Record<string, string[]> = {
      QUEUED: ["GENERATING", "CANCELLED", "FAILED"],
      GENERATING: ["VALIDATING", "FAILED", "CANCELLED"],
      VALIDATING: ["REPAIRING", "FAILED", "CANCELLED"],
      REPAIRING: ["VALIDATING", "FAILED", "CANCELLED"],
    };
    if (!allowed[from]?.includes(to))
      throw new ApiFault(409, "STATE_CONFLICT", "Invalid task transition");
    const result = this.db
      .prepare(
        "UPDATE generation_tasks SET status=?,revision=revision+1,error_json=? WHERE task_id=? AND host_id=? AND revision=? AND status=?",
      )
      .run(
        to,
        error === null ? null : JSON.stringify(error),
        id,
        hostId,
        revision,
        from,
      );
    if (result.changes !== 1)
      throw new ApiFault(409, "STATE_CONFLICT", "Task changed concurrently");
    return this.get(id, hostId)!;
  }
  queued(): TaskRow[] {
    return this.db
      .prepare(
        "SELECT * FROM generation_tasks WHERE status='QUEUED' ORDER BY created_at LIMIT 8",
      )
      .all() as TaskRow[];
  }
  /** S0 不猜测模型调用是否成功；重启中断任务明确失败，避免重复计费或假完成。 */
  recover() {
    this.db
      .prepare(
        "UPDATE generation_tasks SET status='FAILED',revision=revision+1,error_json=? WHERE status IN ('GENERATING','VALIDATING','REPAIRING')",
      )
      .run(
        JSON.stringify({
          code: "SERVICE_RESTARTED",
          message:
            "Interrupted execution requires a new explicit request; no automatic model retry.",
        }),
      );
  }
}
export function taskView(row: TaskRow): GenerationView {
  return {
    schemaVersion: 1,
    taskId: row.task_id,
    status: row.status,
    revision: row.revision,
    inputHash: row.input_hash,
    definitionRef: null,
    error: row.error_json ? JSON.parse(row.error_json) : null,
    createdAt: row.created_at,
  };
}
