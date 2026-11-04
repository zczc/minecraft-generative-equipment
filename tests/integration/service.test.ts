import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { auth, profile, input, harness } from "../helpers.js";
import { createApp } from "../../services/generator/src/app.js";
const register = (app: any, p = profile) =>
  app.inject({
    method: "POST",
    url: "/v1/hosts/local-paper/capability-profiles",
    headers: auth,
    payload: p,
  });
const submit = (app: any, body = input()) =>
  app.inject({
    method: "POST",
    url: "/v1/generations",
    headers: { ...auth, "idempotency-key": body.taskId },
    payload: body,
  });
test("public liveness, private readiness, and credential-bound host", async () => {
  const h = await harness();
  try {
    assert.equal((await h.app.inject("/health/live")).statusCode, 200);
    assert.equal((await h.app.inject("/v1/health/ready")).statusCode, 401);
    const ready = await h.app.inject({
      url: "/v1/health/ready",
      headers: auth,
    });
    assert.equal(ready.statusCode, 503);
    assert.equal(ready.json().dependencies.model, false);
    assert.equal(
      (await register(h.app, { ...profile, hostId: "other-host" })).statusCode,
      403,
    );
  } finally {
    await h.cleanup();
  }
});
test("profile registration is immutable and idempotent", async () => {
  const h = await harness();
  try {
    assert.equal((await register(h.app)).statusCode, 201);
    assert.equal((await register(h.app)).statusCode, 200);
    assert.equal(
      (await register(h.app, { ...profile, hostBuildId: "changed" }))
        .statusCode,
      409,
    );
  } finally {
    await h.cleanup();
  }
});
test("materials are configurable and resolved into frozen generation inputs", async () => {
  const h = await harness();
  try {
    await register(h.app);
    const list = await h.app.inject({
      url: "/v1/worlds/mist-isle/materials",
      headers: auth,
    });
    assert.equal(list.json().materials.length, 8);
    const body = input();
    assert.equal((await submit(h.app, body)).statusCode, 202);
    const row = h.store.get(body.taskId, "local-paper")!;
    const frozen = JSON.parse(row.resolved_json);
    assert.equal(frozen.materials[0].name, "紫水晶碎片");
    assert.equal(frozen.materials[0].count, 2);
    assert.equal(frozen.materials[0].associations[0], "共振");
    const path = join(h.config.worldsDir, "mist-isle/materials.json");
    const catalog = JSON.parse(await readFile(path, "utf8"));
    catalog.materials.find(
      (m: any) => m.itemId === "minecraft:amethyst_shard",
    ).enabled = false;
    await writeFile(path, JSON.stringify(catalog));
    assert.equal(
      (await submit(h.app, body)).statusCode,
      200,
      "retry must use frozen input, not the changed catalog",
    );
    assert.equal(
      (await submit(h.app, input("new-task"))).statusCode,
      422,
      "new tasks must honor disabled materials",
    );
    assert.equal(
      JSON.parse(h.store.get(body.taskId, "local-paper")!.resolved_json)
        .materials[0].enabled,
      true,
    );
  } finally {
    await h.cleanup();
  }
});
test("generation submission protects intent, quantities, host identity and idempotency", async () => {
  const h = await harness();
  try {
    await register(h.app);
    const body = input();
    assert.equal((await submit(h.app, body)).statusCode, 202);
    assert.equal((await submit(h.app, body)).statusCode, 200);
    assert.equal(
      (await submit(h.app, { ...body, intentText: "different" })).statusCode,
      409,
    );
    assert.equal(
      (
        await submit(h.app, {
          ...input("bad-intent"),
          intentText: "一二三四五六七八九十百",
        })
      ).json().error.code,
      "INTENT_TOO_LONG",
    );
    assert.equal(h.store.get("bad-intent", "local-paper"), null);
    const duplicate = input("duplicate-material");
    duplicate.materials.push({ ...duplicate.materials[0]! });
    assert.equal(
      (await submit(h.app, duplicate)).json().error.code,
      "DUPLICATE_MATERIAL",
    );
    assert.equal(
      (await submit(h.app, { ...input("bad-host"), hostId: "other-host" }))
        .statusCode,
      403,
    );
    assert.equal(
      (
        await h.app.inject({
          method: "POST",
          url: "/v1/generations",
          headers: auth,
          payload: input("missing-key"),
        })
      ).statusCode,
      400,
    );
  } finally {
    await h.cleanup();
  }
});
test("worker without model credentials fails without false READY", async () => {
  const h = await harness();
  try {
    await register(h.app);
    await submit(h.app);
    await h.worker.runOnce();
    const result = await h.app.inject({
      url: "/v1/generations/task-test",
      headers: auth,
    });
    assert.equal(result.json().status, "FAILED");
    assert.equal(result.json().error.code, "MODEL_NOT_CONFIGURED");
    assert.equal(result.json().definitionRef, null);
    assert.equal(
      (
        await h.app.inject({
          url: "/v1/definitions/made-up/versions/1/bundle",
          headers: auth,
        })
      ).statusCode,
      404,
    );
  } finally {
    await h.cleanup();
  }
});
test("cancellation and optimistic revision prevent stale completion", async () => {
  const h = await harness();
  try {
    await register(h.app);
    await submit(h.app);
    const old = h.store.get("task-test", "local-paper")!;
    const response = await h.app.inject({
      method: "POST",
      url: "/v1/generations/task-test/cancel",
      headers: auth,
      payload: { requestId: "cancel-test", reason: "changed mind" },
    });
    assert.equal(response.json().status, "CANCELLED");
    assert.throws(() =>
      h.store.transition(
        old.task_id,
        old.host_id,
        old.revision,
        "QUEUED",
        "GENERATING",
      ),
    );
    await h.worker.runOnce();
    assert.equal(h.store.get(old.task_id, old.host_id)!.status, "CANCELLED");
  } finally {
    await h.cleanup();
  }
});
test("interrupted execution fails on restart instead of silently replaying model work", async () => {
  const h = await harness();
  await register(h.app);
  await submit(h.app);
  const row = h.store.get("task-test", "local-paper")!;
  h.store.transition(
    row.task_id,
    row.host_id,
    row.revision,
    "QUEUED",
    "GENERATING",
  );
  await h.app.close();
  const restarted = await createApp(h.config, { startWorker: false });
  try {
    assert.equal(
      restarted.store.get(row.task_id, row.host_id)!.status,
      "FAILED",
    );
    assert.equal(
      JSON.parse(restarted.store.get(row.task_id, row.host_id)!.error_json!)
        .code,
      "SERVICE_RESTARTED",
    );
  } finally {
    await restarted.app.close();
    const { rm } = await import("node:fs/promises");
    await rm(h.root, { recursive: true, force: true });
  }
});
