import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import { AliyunModelProvider } from "../../services/generator/src/adapters/aliyun/model.js";
import { generationConfig } from "../../services/generator/src/config.js";
import { GenerationWorker } from "../../services/generator/src/application/generation-worker.js";
import { harness, input, profile } from "../helpers.js";

test("invalid paid model responses retain usage and raw code for repair; unchanged repair stops", async () => {
  const h = await harness();
  const bodies: any[] = [];
  const server = createServer(async (req, res) => {
    let text = "";
    for await (const part of req) text += part;
    bodies.push(JSON.parse(text));
    res.setHeader("content-type", "application/json");
    res.end(
      JSON.stringify({
        model: "mock-deepseek",
        choices: [
          {
            finish_reason: "stop",
            message: { content: "export const equipment = {broken:" },
          },
        ],
        usage: {
          prompt_tokens: 100,
          completion_tokens: 20,
          total_tokens: 120,
          completion_tokens_details: { reasoning_tokens: 10 },
        },
      }),
    );
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const address = server.address() as { port: number };
    const model = new AliyunModelProvider({
      ...generationConfig({}),
      apiKey: "test-key",
      baseUrl: `http://127.0.0.1:${address.port}`,
    });
    const request = input("broken-module");
    const expanded = {
      ...(await h.worlds.resolveInput(request)),
      capabilityProfile: profile,
    };
    h.store.create(request, expanded);
    const worker = new GenerationWorker(
      h.store,
      model,
      {
        validate: async () => {
          throw new Error("unparsed text must never reach runtime");
        },
      },
      h.definitions,
    );
    await worker.runOnce();
    const row = h.store.get(request.taskId, request.hostId)!;
    assert.equal(row.status, "FAILED");
    assert.equal(JSON.parse(row.error_json!).code, "REPAIR_UNCHANGED");
    assert.equal(bodies.length, 2);
    assert.equal(bodies[0].response_format, undefined);
    assert.equal(bodies[0].enable_thinking, true);
    assert.equal(bodies[0].reasoning_effort, "low");
    assert.equal(
      bodies[1].messages[2].content,
      "export const equipment = {broken:",
    );
    const attempts = h.store.db
      .prepare("SELECT usage_json FROM generation_attempts WHERE task_id=?")
      .all(request.taskId) as { usage_json: string }[];
    assert.equal(attempts.length, 2);
    assert.equal(JSON.parse(attempts[0].usage_json).reasoning_tokens, 10);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await h.cleanup();
  }
});
