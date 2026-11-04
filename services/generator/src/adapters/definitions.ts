import { mkdir, writeFile, rename, readFile } from "node:fs/promises";
import { join } from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { zipSync, strToU8 } from "fflate";
import {
  hashJson,
  validate,
  type GeneratedSource,
  type PackageManifest,
  type DefinitionRef,
  type DeliveryEvent,
  type Observation,
} from "@ge/protocol";
import type {
  GenerationInput,
  ModelResult,
  ValidationReport,
} from "../ports/index.js";
import { SqliteStore, type TaskRow } from "./sqlite-store.js";
import { ApiFault } from "../errors.js";
export const sha = (data: Uint8Array | string) =>
  createHash("sha256").update(data).digest("hex");
export type DefinitionView = {
  definitionRef: DefinitionRef;
  bundleHash: string;
  bytes: number;
  downloadPath: string;
  manifest: PackageManifest;
};
/** 写文件完成后才在同一事务发布定义和 READY；取消赢得竞争时不允许发布旧结果。 */
export class DefinitionRepository {
  constructor(
    private store: SqliteStore,
    private dir: string,
  ) {}
  async publish(
    row: TaskRow,
    input: GenerationInput,
    result: ModelResult,
    report: ValidationReport,
  ): Promise<void> {
    const source = result.source;
    if (!source) throw new Error("Cannot publish an unparsed model response");
    const sourceHash = hashJson(source);
    if (
      !report.passed ||
      report.sourceHash !== sourceHash ||
      report.capabilityProfileId !== input.capabilityProfile.profileId ||
      report.engineBuildId !== input.capabilityProfile.engineBuildId ||
      report.checks.some((c) => c.status !== "PASS") ||
      ![
        "source-contract",
        "state-schema",
        "quickjs-compilation",
        "declared-scenarios",
        "rejected-world-operations",
        "presentation-contract",
      ].every((name) =>
        report.checks.some((c) => c.name === name && c.status === "PASS"),
      )
    )
      throw new Error("Only matching verified artifacts may publish");
    const definitionRef = { definitionId: randomUUID(), version: 1 };
    const provenance = {
      origin: result.origin ?? "model",
      taskId: row.task_id,
      createdAt: row.created_at,
      publishedAt: new Date().toISOString(),
      inputHash: row.input_hash,
      request: input.request,
      materials: input.materials,
      worldConfigHash: input.worldConfigHash,
      model: result.model ?? null,
      sourceFormat: result.format ?? "legacy",
      modelTextHash: result.rawText === undefined ? null : sha(result.rawText),
    };
    const e = source.equipment;
    const files: Record<string, Uint8Array> = {
      "equipment.json": strToU8(JSON.stringify(e)),
      "behavior.js": strToU8(source.behaviorSource),
      "definition.json": strToU8(
        JSON.stringify({
          name: e.name,
          description: e.description,
          baseItem: e.baseItem,
          activationSlots: e.activationSlots,
          subscriptions: e.subscriptions,
          requires: e.requires,
          constraints: e.constraints ?? [],
        }),
      ),
      "parameters.json": strToU8(JSON.stringify(e.parameters)),
      "state-schema.json": strToU8(
        JSON.stringify({ schema: e.stateSchema, initialState: e.initialState }),
      ),
      "presentation.json": strToU8(
        JSON.stringify(e.presentation ?? { cues: {}, bindings: [] }),
      ),
      "scenarios.json": strToU8(JSON.stringify(e.scenarios ?? [])),
      "provenance.json": strToU8(JSON.stringify(provenance)),
      "validation-report.json": strToU8(JSON.stringify(report)),
    };
    const manifest: PackageManifest = {
      packageFormatVersion: 1,
      definitionRef,
      protocolVersion: "1",
      capabilityProfileId: input.capabilityProfile.profileId,
      engineBuildId: input.capabilityProfile.engineBuildId,
      worldRuleRef: input.request.worldRuleRef,
      sourceHash,
      files: Object.entries(files).map(([path, data]) => ({
        path,
        sha256: sha(data),
        bytes: data.length,
      })),
      subscriptions: e.subscriptions,
      requiredCapabilities: [...e.requires.queries, ...e.requires.operations],
      activationSlots: e.activationSlots,
      origin: result.origin ?? "model",
    };
    validate("PackageManifest", manifest);
    files["manifest.json"] = strToU8(JSON.stringify(manifest));
    const bundle = Buffer.from(zipSync(files, { level: 6 }));
    if (bundle.length > 1048576) throw new Error("Bundle exceeds one MiB");
    const folder = join(this.dir, "definitions");
    await mkdir(folder, { recursive: true });
    const path = join(folder, `${definitionRef.definitionId}.zip`);
    await writeFile(path + ".tmp", bundle, { flag: "wx", mode: 0o600 });
    await rename(path + ".tmp", path);
    const view: DefinitionView = {
      definitionRef,
      bundleHash: "sha256:" + sha(bundle),
      bytes: bundle.length,
      downloadPath: `/v1/definitions/${definitionRef.definitionId}/versions/1/bundle`,
      manifest,
    };
    this.store.db.exec("BEGIN IMMEDIATE");
    try {
      const current = this.store.get(row.task_id, row.host_id);
      if (current?.status !== "VALIDATING" || current.revision !== row.revision)
        throw new ApiFault(
          409,
          "STATE_CONFLICT",
          "Task changed before publication",
        );
      this.store.db
        .prepare("INSERT INTO definitions VALUES(?,?,?,?,?,?,?,?)")
        .run(
          definitionRef.definitionId,
          1,
          row.task_id,
          row.host_id,
          JSON.stringify(definitionRef),
          JSON.stringify(view),
          path,
          JSON.stringify(provenance),
        );
      // READY 与定义必须一起提交，不留下空的成功任务。
      this.store.db
        .prepare(
          "UPDATE generation_tasks SET status='READY',revision=revision+1,error_json=NULL WHERE task_id=?",
        )
        .run(row.task_id);
      this.store.db.exec("COMMIT");
    } catch (e) {
      this.store.db.exec("ROLLBACK");
      throw e;
    }
  }
  get(id: string, version: number, host: string) {
    return this.store.db
      .prepare(
        "SELECT * FROM definitions WHERE definition_id=? AND version=? AND host_id=?",
      )
      .get(id, version, host) as
      | {
          view_json: string;
          bundle_path: string;
          provenance_json: string;
          task_id: string;
        }
      | undefined;
  }
  async bundle(row: { bundle_path: string; view_json: string }) {
    const data = await readFile(row.bundle_path);
    if ("sha256:" + sha(data) !== JSON.parse(row.view_json).bundleHash)
      throw new Error("Stored bundle hash mismatch");
    return data;
  }
  async saveCandidate(
    taskId: string,
    attempt: number,
    result: ModelResult,
    report: ValidationReport,
  ) {
    const folder = join(this.dir, "candidates", sha(taskId));
    await mkdir(folder, { recursive: true });
    if (result.rawText !== undefined)
      await writeFile(join(folder, `${attempt}.raw.txt`), result.rawText, {
        mode: 0o600,
      });
    await writeFile(
      join(folder, `${attempt}.source.json`),
      JSON.stringify(result.source, null, 2),
      { mode: 0o600 },
    );
    await writeFile(
      join(folder, `${attempt}.report.json`),
      JSON.stringify(report, null, 2),
      { mode: 0o600 },
    );
  }
  attempt(
    taskId: string,
    attempt: number,
    result: ModelResult | null,
    report: ValidationReport | null,
  ) {
    this.store.db
      .prepare(
        "INSERT OR REPLACE INTO generation_attempts VALUES(?,?,?,?,?,?,?)",
      )
      .run(
        taskId,
        attempt,
        result ? hashJson(result.source ?? { rawText: result.rawText }) : null,
        result?.usage ? JSON.stringify(result.usage) : null,
        result?.model ?? null,
        report ? JSON.stringify(report) : null,
        new Date().toISOString(),
      );
  }
  /** 交付事件只记录宿主证据。PREPARED 之后网络失败时不能换 deliveryId 再发一次。 */
  delivery(event: DeliveryEvent, host: string) {
    const task = this.store.get(event.taskId, host);
    if (!task) throw new ApiFault(404, "TASK_NOT_FOUND", "Task not found");
    if (task.status !== "READY")
      throw new ApiFault(409, "STATE_CONFLICT", "Task not ready");
    const hash = hashJson(event);
    const old = this.store.db
      .prepare("SELECT event_hash FROM delivery_events WHERE event_id=?")
      .get(event.eventId) as { event_hash: string } | undefined;
    if (old) {
      if (old.event_hash !== hash)
        throw new ApiFault(409, "IDEMPOTENCY_CONFLICT", "Event changed");
      return { duplicate: true };
    }
    const last = this.store.db
      .prepare(
        "SELECT * FROM delivery_events WHERE task_id=? ORDER BY sequence_no DESC LIMIT 1",
      )
      .get(event.taskId) as
      | { delivery_id: string; sequence_no: number; state: string }
      | undefined;
    if (last && last.delivery_id !== event.deliveryId)
      throw new ApiFault(409, "STATE_CONFLICT", "Task already has a delivery");
    const allowed: Record<string, string[]> = {
      NOT_STARTED: ["WAITING_PLAYER", "PREPARED"],
      WAITING_PLAYER: ["PREPARED"],
      PREPARED: ["APPLIED", "RECOVERY_REQUIRED"],
      APPLIED: ["ACKNOWLEDGED", "RECOVERY_REQUIRED"],
      RECOVERY_REQUIRED: [],
      ACKNOWLEDGED: [],
      REFUNDED: [],
    };
    if (
      event.sequenceNo !== (last?.sequence_no ?? 0) + 1 ||
      !allowed[last?.state ?? "NOT_STARTED"]?.includes(event.state)
    )
      throw new ApiFault(409, "STATE_CONFLICT", "Invalid delivery transition");
    this.store.db
      .prepare("INSERT INTO delivery_events VALUES(?,?,?,?,?,?,?,?)")
      .run(
        event.eventId,
        host,
        event.taskId,
        event.deliveryId,
        event.sequenceNo,
        event.state,
        hash,
        JSON.stringify(event),
      );
    return { duplicate: false };
  }
  observe(event: Observation, host: string) {
    const def = this.get(
      event.definitionRef.definitionId,
      event.definitionRef.version,
      host,
    );
    if (!def)
      throw new ApiFault(404, "DEFINITION_NOT_FOUND", "Definition not found");
    if (JSON.parse(def.view_json).manifest.sourceHash !== event.codeHash)
      throw new ApiFault(
        422,
        "SOURCE_MISMATCH",
        "Observation code hash mismatch",
      );
    const hash = hashJson(event);
    const old = this.store.db
      .prepare("SELECT event_hash FROM observations WHERE event_id=?")
      .get(event.eventId) as { event_hash: string } | undefined;
    if (old) {
      if (old.event_hash !== hash)
        throw new ApiFault(409, "IDEMPOTENCY_CONFLICT", "Observation changed");
      return "duplicate";
    }
    this.store.db
      .prepare("INSERT INTO observations VALUES(?,?,?,?,?,?)")
      .run(
        event.eventId,
        host,
        event.definitionRef.definitionId,
        event.definitionRef.version,
        hash,
        JSON.stringify(event),
      );
    return "accepted";
  }
}
