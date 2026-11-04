import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomBytes } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";

// 通过真实进程和 TCP 验证 dist 入口、配置与 migration 路径，而非只测试 Fastify 注入。
test(
  "compiled service starts, authenticates and shuts down without a model",
  { timeout: 15000 },
  async () => {
    const probe = createServer();
    probe.listen(0, "127.0.0.1");
    await once(probe, "listening");
    const port = (probe.address() as { port: number }).port;
    await new Promise<void>((done) => probe.close(() => done()));
    const data = await mkdtemp(join(tmpdir(), "ge-startup-"));
    const token = randomBytes(24).toString("hex");
    const child = spawn(process.execPath, ["services/generator/dist/main.js"], {
      env: {
        ...process.env,
        GE_PORT: String(port),
        GE_HOST_ID: "local-paper",
        GE_INTERNAL_TOKEN: token,
        GE_DATA_DIR: data,
        GE_WORLDS_DIR: resolve("worlds"),
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const exit = once(child, "exit");
    let logs = "";
    child.stdout.on("data", (b) => {
      logs += b;
    });
    child.stderr.on("data", (b) => {
      logs += b;
    });
    try {
      let live = false;
      for (let i = 0; i < 50; i++) {
        if (child.exitCode !== null) throw new Error(`Service exited: ${logs}`);
        try {
          live = (await fetch(`http://127.0.0.1:${port}/health/live`)).ok;
        } catch {}
        if (live) break;
        await delay(100);
      }
      assert.equal(live, true, "service should bind a real socket");
      assert.equal(
        (await fetch(`http://127.0.0.1:${port}/v1/health/ready`)).status,
        401,
      );
      const ready = await fetch(`http://127.0.0.1:${port}/v1/health/ready`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      assert.equal(ready.status, 503);
      assert.equal(((await ready.json()) as { ready: boolean }).ready, false);
      assert.equal(
        logs.includes(token),
        false,
        "credentials must not appear in logs",
      );
    } finally {
      child.kill("SIGTERM");
      await exit;
      await rm(data, { recursive: true, force: true });
    }
  },
);
