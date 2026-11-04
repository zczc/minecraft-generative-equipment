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
import type { Config } from "../../config.js";
import { ApiFault, notImplemented } from "../../errors.js";
import { SqliteStore, taskView } from "../../adapters/sqlite-store.js";
import { WorldCatalog } from "../../adapters/world-catalog.js";

/** HTTP 入口只校验、调用应用模块并转换响应；不运行装备代码或触碰 Minecraft 对象。 */
export function registerRoutes(
  app: FastifyInstance,
  {
    config,
    store,
    worlds,
  }: { config: Config; store: SqliteStore; worlds: WorldCatalog },
): void {
  app.get("/health/live", async () => ({ status: "alive", stage: "S0" }));
  app.get("/v1/health/ready", async (_req, reply) =>
    reply.code(503).send({
      ready: false,
      stage: "S0",
      dependencies: {
        database: true,
        model: false,
        validator: false,
        publisher: false,
      },
    }),
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
      return taskView(row);
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
    app.get(`/v1/definitions/:id/versions/:version${suffix}`, async () => {
      throw new ApiFault(
        404,
        "DEFINITION_NOT_FOUND",
        "No verified definitions are published in S0",
      );
    });
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
      throw notImplemented("Material and item delivery");
    },
  );
  app.post("/v1/observations/batch", async (req) => {
    validate<ObservationBatch>("ObservationBatch", req.body);
    throw notImplemented("Runtime observation persistence");
  });
  app.get("/v1/observations", async () => {
    throw notImplemented("Runtime observation queries");
  });
}
