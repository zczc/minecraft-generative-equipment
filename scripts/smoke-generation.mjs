import { createApp, loadConfig } from "../services/generator/dist/index.js";
import { javaHome } from "./java.mjs";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
const config = loadConfig();
const home = await javaHome();
if (home) config.generation.javaCommand = join(home, "bin/java");
config.logger = false;
const h = await createApp(config, { startWorker: false });
try {
  const capabilities = JSON.parse(
    await readFile("contracts/capabilities/v1/host.json", "utf8"),
  );
  const profile = {
    profileId: `paper-alpha1-${config.hostId}`,
    hostId: config.hostId,
    hostBuildId: capabilities.buildId,
    protocolVersion: "1",
    engineBuildId: "quickjs-2026-06-04-ge1",
    events: capabilities.events,
    queries: capabilities.queries,
    operations: capabilities.operations,
    visuals: capabilities.visuals,
    limits: capabilities.limits,
  };
  h.store.saveProfile(profile);
  const taskId = randomUUID();
  const input = {
    schemaVersion: 1,
    taskId,
    hostId: config.hostId,
    recipientPlayerId: "smoke-test-player",
    worldRuleRef: { id: "mist-isle", version: "1" },
    capabilityProfileId: profile.profileId,
    intentText: process.argv[2] ?? "借风蓄势",
    materials: [
      { definitionId: "minecraft:feather", count: 2 },
      { definitionId: "minecraft:amethyst_shard", count: 1 },
    ],
    sourceEquipment: [],
    generationContext: {
      dimension: "minecraft:overworld",
      biome: "minecraft:forest",
      position: { worldId: "test-world", x: 0, y: 64, z: 0 },
      worldTimeTicks: 14000,
      weather: { worldRain: false, exposedToRain: false },
      nearbyBlocks: {
        samplingVersion: "1",
        samples: [],
        missingReason: "NOT_SAMPLED",
      },
    },
  };
  const response = await h.app.inject({
    method: "POST",
    url: "/v1/generations",
    headers: {
      authorization: `Bearer ${config.token}`,
      "idempotency-key": taskId,
    },
    payload: input,
  });
  if (response.statusCode !== 202)
    throw new Error(`Submit failed: ${response.statusCode} ${response.body}`);
  const startedAt = new Date().toISOString();
  const start = Date.now();
  console.log(
    `Real model generation started: ${taskId} (${config.generation.model}, max_tokens=${config.generation.maxTokens})`,
  );
  await h.worker.runOnce();
  const row = h.store.get(taskId, config.hostId);
  const attempts = h.store.db
    .prepare(
      "SELECT attempt_no,model,usage_json,report_json FROM generation_attempts WHERE task_id=? ORDER BY attempt_no",
    )
    .all(taskId);
  const summary = {
    taskId,
    startedAt,
    elapsedMs: Date.now() - start,
    model: config.generation.model,
    enableThinking: config.generation.enableThinking,
    reasoningEffort: config.generation.reasoningEffort ?? null,
    maxTokens: config.generation.maxTokens,
    status: row.status,
    error: row.error_json ? JSON.parse(row.error_json) : null,
    definitionRef: row.definition_ref ? JSON.parse(row.definition_ref) : null,
    attempts: attempts.map((a) => ({
      attempt: a.attempt_no,
      model: a.model,
      usage: a.usage_json ? JSON.parse(a.usage_json) : null,
      validation: a.report_json ? JSON.parse(a.report_json) : null,
    })),
  };
  await mkdir(config.dataDir, { recursive: true });
  await writeFile(
    join(config.dataDir, `smoke-${taskId}.json`),
    JSON.stringify(summary, null, 2),
  );
  await writeFile(
    join(config.dataDir, "latest-smoke.json"),
    JSON.stringify(summary, null, 2),
  );
  console.log(JSON.stringify(summary, null, 2));
  if (row.status !== "READY") process.exitCode = 1;
} finally {
  await h.app.close();
}
