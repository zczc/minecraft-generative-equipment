import { registerRoutes } from "./http/routes/index.js";
import Fastify from "fastify";
import { timingSafeEqual } from "node:crypto";
import { ContractError } from "@ge/protocol";
import { generationConfig, type Config } from "./config.js";
import { ApiFault } from "./errors.js";
import { SqliteStore } from "./adapters/sqlite-store.js";
import { WorldCatalog } from "./adapters/world-catalog.js";
import { AliyunModelProvider } from "./adapters/aliyun/model.js";
import { ValidatorProcess } from "./adapters/validator-process.js";
import { DefinitionRepository } from "./adapters/definitions.js";
import { GenerationWorker } from "./application/generation-worker.js";
/**
 * 生成服务组装入口。HTTP 只处理后台任务，不直接操作 Minecraft 世界。
 * 真正验证通过后才发布；密钥或运行器缺失时 ready 明确报告不可用。
 */
export async function createApp(config: Config, { startWorker = true } = {}) {
  const app = Fastify({
    bodyLimit: 65536,
    logger: config.logger ? { redact: ["req.headers.authorization"] } : false,
  });
  const store = new SqliteStore(config.dataDir),
    worlds = new WorldCatalog(config.worldsDir);
  store.recover();
  const generation = config.generation ?? generationConfig({});
  const validator = new ValidatorProcess(generation);
  const definitions = new DefinitionRepository(store, config.dataDir);
  const dependencies = {
    database: true,
    model: !!generation.apiKey,
    validator: await validator.available(),
    publisher: true,
  };
  const worker = new GenerationWorker(
    store,
    new AliyunModelProvider(generation),
    validator,
    definitions,
  );
  // 凭证绑定到配置的 hostId；请求正文不能借换一个 hostId 越权。
  app.addHook("preHandler", async (request) => {
    if (request.routeOptions.url === "/health/live") return;
    const supplied = request.headers.authorization ?? "";
    const expected = `Bearer ${config.token}`;
    const a = Buffer.from(supplied),
      b = Buffer.from(expected);
    if (a.length !== b.length || !timingSafeEqual(a, b))
      throw new ApiFault(
        401,
        "UNAUTHORIZED",
        "Internal host authentication required",
      );
  });
  app.setErrorHandler((err, req, reply) => {
    const e =
      err instanceof ContractError
        ? new ApiFault(
            422,
            "INVALID_REQUEST",
            "Request does not match the protocol",
            err.details,
          )
        : err;
    if (e instanceof ApiFault)
      return reply.status(e.status).send({
        schemaVersion: 1,
        requestId: req.id,
        error: {
          code: e.code,
          message: e.message,
          retryable: e.retryable,
          ...(e.details === undefined ? {} : { details: e.details }),
        },
      });
    const status = (e as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500)
      return reply.status(status).send({
        schemaVersion: 1,
        requestId: req.id,
        error: {
          code: "INVALID_REQUEST",
          message: "Malformed or oversized request",
          retryable: false,
        },
      });
    req.log.error({ err: e }, "request failed");
    return reply.status(500).send({
      schemaVersion: 1,
      requestId: req.id,
      error: {
        code: "INTERNAL_ERROR",
        message: "Unexpected service error",
        retryable: false,
      },
    });
  });
  app.setNotFoundHandler((req, reply) =>
    reply.code(404).send({
      schemaVersion: 1,
      requestId: req.id,
      error: {
        code: "NOT_FOUND",
        message: "Route not found",
        retryable: false,
      },
    }),
  );
  registerRoutes(app, {
    config,
    store,
    worlds,
    definitions,
    dependencies,
    cancel: (id) => worker.cancel(id),
  });
  const timer = startWorker
    ? setInterval(() => {
        void worker
          .runOnce()
          .catch((err) => app.log.error({ err }, "worker failed"));
      }, 250)
    : null;
  timer?.unref();
  app.addHook("onClose", async () => {
    if (timer) clearInterval(timer);
    await worker.stop();
    store.close();
  });
  return { app, store, worker, worlds, definitions };
}
