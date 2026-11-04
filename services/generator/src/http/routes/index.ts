import type { FastifyInstance } from "fastify";
import {
  hashJson,
  validate,
  type CapabilityProfile,
  type GenerationRequest,
  type CancelRequest,
  type DeliveryEvent,
  type ObservationBatch,
} from "@ge/protocol";
import { DefinitionRepository } from "../../adapters/definitions.js";
import type { Config } from "../../config.js";
import { ApiFault } from "../../errors.js";
import { SqliteStore, taskView } from "../../adapters/sqlite-store.js";
import { WorldCatalog } from "../../adapters/world-catalog.js";

/** HTTP 入口只校验、调用应用模块并转换响应；不运行装备代码或触碰 Minecraft 对象。 */
export function registerRoutes(
  app: FastifyInstance,
  {
    config,
    store,
    worlds,
    definitions,
    dependencies,
    cancel,
  }: {
    config: Config;
    store: SqliteStore;
    worlds: WorldCatalog;
    definitions: DefinitionRepository;
    dependencies: Record<string, boolean>;
    cancel: (id: string) => void;
  },
): void {
  app.get("/health/live", async () => ({ status: "alive", stage: "alpha" }));
  app.get("/v1/health/ready", async (_req, reply) => {
    const ready = Object.values(dependencies).every(Boolean);
    return reply
      .code(ready ? 200 : 503)
      .send({ ready, stage: "alpha", dependencies });
  });
  app.get<{ Querystring: { recipientPlayerId?: string } }>(
    "/v1/generations",
    async (req) => {
      const rows = store.db
        .prepare(
          "SELECT task_id,input_json FROM generation_tasks WHERE host_id=? ORDER BY created_at DESC LIMIT 100",
        )
        .all(config.hostId) as { task_id: string; input_json: string }[];
      return rows
        .filter(
          (r) =>
            !req.query.recipientPlayerId ||
            JSON.parse(r.input_json).recipientPlayerId ===
              req.query.recipientPlayerId,
        )
        .map((r) => taskView(store.get(r.task_id, config.hostId)!));
    },
  );
  app.post<{ Params: { hostId: string } }>(
    "/v1/hosts/:hostId/capability-profiles",
    async (req, reply) => {
      const profile = validate<CapabilityProfile>(
        "CapabilityProfile",
        req.body,
      );
      if (
        req.params.hostId !== config.hostId ||
        profile.hostId !== config.hostId
      )
        throw new ApiFault(
          403,
          "HOST_FORBIDDEN",
          "Host does not match credential",
        );
      const created = store.saveProfile(profile);
      return reply
        .code(created ? 201 : 200)
        .send({ profileId: profile.profileId, profileHash: hashJson(profile) });
    },
  );
  app.get<{ Params: { worldId: string } }>(
    "/v1/worlds/:worldId/materials",
    async (req) => {
      const snapshot = await worlds.load(req.params.worldId);
      return {
        worldRuleRef: {
          id: snapshot.world.id,
          version: snapshot.world.version,
        },
        catalogVersion: snapshot.materials.version,
        materials: snapshot.materials.materials.filter((m) => m.enabled),
        policy: snapshot.policy,
      };
    },
  );
  app.post("/v1/generations", async (req, reply) => {
    const input = validate<GenerationRequest>("GenerationRequest", req.body);
    if (input.hostId !== config.hostId)
      throw new ApiFault(
        403,
        "HOST_FORBIDDEN",
        "Host does not match credential",
      );
    if (req.headers["idempotency-key"] !== input.taskId)
      throw new ApiFault(
        400,
        "IDEMPOTENCY_KEY_REQUIRED",
        "Idempotency-Key must equal taskId",
      );
    // 重试先读旧任务，避免配置热更新改变同一次生成的材料含义。
    const previous = store.get(input.taskId, config.hostId);
    if (previous) {
      if (previous.input_hash !== hashJson(input))
        throw new ApiFault(
          409,
          "IDEMPOTENCY_CONFLICT",
          "Task id already has different input",
        );
      return taskView(previous);
    }
    const profile = store.profile(input.capabilityProfileId, config.hostId);
    if (!profile)
      throw new ApiFault(
        422,
        "UNKNOWN_CAPABILITY_PROFILE",
        "Register a capability profile first",
      );
    const resolved = await worlds.resolveInput(input);
    const { created, row } = store.create(input, {
      ...resolved,
      capabilityProfile: profile,
    });
    return reply.code(created ? 202 : 200).send(taskView(row));
  });
  app.get<{ Params: { taskId: string } }>(
    "/v1/generations/:taskId",
    async (req) => {
      const row = store.get(req.params.taskId, config.hostId);
      if (!row) throw new ApiFault(404, "TASK_NOT_FOUND", "Task not found");
      return {
        ...taskView(row),
        recipientPlayerId: JSON.parse(row.input_json).recipientPlayerId,
      };
    },
  );
  // 取消这里只终止生成任务；材料退款与物品交付尚未接入，不隐式执行。
  app.post<{ Params: { taskId: string } }>(
    "/v1/generations/:taskId/cancel",
    async (req) => {
      validate<CancelRequest>("CancelRequest", req.body);
      const row = store.get(req.params.taskId, config.hostId);
      if (!row) throw new ApiFault(404, "TASK_NOT_FOUND", "Task not found");
      if (row.status === "CANCELLED") return taskView(row);
      cancel(row.task_id);
      return taskView(
        store.transition(
          row.task_id,
          row.host_id,
          row.revision,
          row.status,
          "CANCELLED",
        ),
      );
    },
  );
  for (const suffix of ["", "/bundle", "/provenance"])
    app.get<{ Params: { id: string; version: string } }>(
      `/v1/definitions/:id/versions/:version${suffix}`,
      async (req, reply) => {
        const row = definitions.get(
          req.params.id,
          Number(req.params.version),
          config.hostId,
        );
        if (!row)
          throw new ApiFault(
            404,
            "DEFINITION_NOT_FOUND",
            "Definition not found",
          );
        if (suffix === "/bundle")
          return reply
            .type("application/zip")
            .send(await definitions.bundle(row));
        return JSON.parse(
          suffix === "/provenance" ? row.provenance_json : row.view_json,
        );
      },
    );
  app.post<{ Params: { taskId: string } }>(
    "/v1/generations/:taskId/delivery-events",
    async (req) => {
      const body = validate<DeliveryEvent>("DeliveryEvent", req.body);
      if (body.hostId !== config.hostId)
        throw new ApiFault(
          403,
          "HOST_FORBIDDEN",
          "Host does not match credential",
        );
      if (body.taskId !== req.params.taskId)
        throw new ApiFault(422, "INVALID_REQUEST", "Task ids do not match");
      return definitions.delivery(body, config.hostId);
    },
  );
  app.post("/v1/observations/batch", async (req) => {
    const batch = validate<ObservationBatch>("ObservationBatch", req.body);
    const result: {
      accepted: string[];
      duplicate: string[];
      invalid: string[];
    } = { accepted: [], duplicate: [], invalid: [] };
    for (const event of batch) {
      try {
        result[definitions.observe(event, config.hostId)].push(event.eventId);
      } catch {
        result.invalid.push(event.eventId);
      }
    }
    return result;
  });
  app.get<{
    Querystring: {
      definitionId: string;
      version: string;
      cursor?: string;
      limit?: string;
    };
  }>("/v1/observations", async (req) => {
    const q = req.query;
    const limit = Math.max(1, Math.min(100, Number(q.limit) || 20)),
      cursor = Math.max(0, Number(q.cursor) || 0);
    if (!definitions.get(q.definitionId, Number(q.version), config.hostId))
      throw new ApiFault(404, "DEFINITION_NOT_FOUND", "Definition not found");
    const rows = store.db
      .prepare(
        "SELECT rowid AS cursor,event_json FROM observations WHERE host_id=? AND definition_id=? AND version=? AND rowid>? ORDER BY rowid LIMIT ?",
      )
      .all(config.hostId, q.definitionId, Number(q.version), cursor, limit) as {
      cursor: number;
      event_json: string;
    }[];
    return {
      items: rows.map((r) => JSON.parse(r.event_json)),
      nextCursor: rows.length === limit ? String(rows.at(-1)!.cursor) : null,
    };
  });
}
