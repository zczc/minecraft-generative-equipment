import { mkdtemp, cp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createApp } from "../services/generator/src/app.js";
export const token = "test-token-with-at-least-32-characters";
export const auth = { authorization: `Bearer ${token}` };
export const profile = {
  profileId: "profile-test",
  hostId: "local-paper",
  hostBuildId: "s0",
  protocolVersion: "1",
  engineBuildId: "unavailable-s0",
  events: [],
  queries: [],
  operations: [],
  visuals: [],
  limits: {},
};
export function input(taskId = "task-test") {
  return {
    schemaVersion: 1,
    taskId,
    hostId: "local-paper",
    recipientPlayerId: "player-test",
    worldRuleRef: { id: "mist-isle", version: "1" },
    capabilityProfileId: "profile-test",
    intentText: "借 echo 的力量",
    materials: [{ definitionId: "minecraft:amethyst_shard", count: 2 }],
    sourceEquipment: [],
    generationContext: {
      dimension: "minecraft:overworld",
      biome: "minecraft:forest",
      position: { worldId: "world-test", x: 1, y: 70, z: 2 },
      worldTimeTicks: 14000,
      weather: { worldRain: false, exposedToRain: null },
      nearbyBlocks: {
        samplingVersion: "1",
        samples: [],
        missingReason: "NOT_SAMPLED",
      },
    },
  };
}
export async function harness() {
  const root = await mkdtemp(join(tmpdir(), "ge-test-"));
  const worldsDir = join(root, "worlds");
  await cp(resolve("worlds"), worldsDir, { recursive: true });
  const config = {
    hostId: "local-paper",
    token,
    port: 8765,
    dataDir: join(root, "data"),
    worldsDir,
    logger: false,
  };
  const instance = await createApp(config, { startWorker: false });
  await instance.app.ready();
  return {
    ...instance,
    root,
    config,
    async cleanup() {
      await instance.app.close();
      await rm(root, { recursive: true, force: true });
    },
  };
}
