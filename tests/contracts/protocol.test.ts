import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  validateIntent,
  validate,
  hashJson,
  ContractError,
} from "@ge/protocol";
import { input } from "../helpers.js";
const cases = JSON.parse(
  await readFile("contracts/fixtures/intent.json", "utf8"),
) as Array<{ text: string; count: number; accepted: boolean }>;
for (const fixture of cases)
  test(`intent count: ${JSON.stringify(fixture.text)}`, () => {
    const result = validateIntent(fixture.text);
    assert.equal(result.count, fixture.count);
    assert.equal(result.accepted, fixture.accepted);
  });
test("byte limit applies even to a single long word or punctuation", () => {
  assert.equal(validateIntent("a".repeat(257)).reason, "INPUT_BYTES_EXCEEDED");
  assert.equal(validateIntent("，".repeat(100)).reason, "INPUT_BYTES_EXCEEDED");
});
test("canonical input hash ignores key order but preserves actual content", () => {
  assert.equal(hashJson({ b: 2, a: 1 }), hashJson({ a: 1, b: 2 }));
  assert.notEqual(hashJson({ a: 1 }), hashJson({ a: 2 }));
});
test("protocol accepts a valid request and rejects extra fields and invalid counts", () => {
  validate("GenerationRequest", input());
  assert.throws(
    () => validate("GenerationRequest", { ...input(), secretOverride: true }),
    ContractError,
  );
  const bad = input();
  bad.materials[0]!.count = 65;
  assert.throws(() => validate("GenerationRequest", bad), ContractError);
});
test("JSON boundary rejects dangerous keys, non-finite numbers and deep graphs", () => {
  assert.throws(() => hashJson(JSON.parse('{"__proto__":{}}')), ContractError);
  assert.throws(() => hashJson({ a: NaN }), ContractError);
  let x: unknown = {};
  for (let i = 0; i < 34; i++) x = { x };
  assert.throws(() => hashJson(x), ContractError);
});
test("decision forbids unknown fields, operation overflow and zero-delay timers", () => {
  validate("Decision", { nextState: { newAbilityAttribute: [1, 2, 3] } });
  assert.throws(
    () => validate("Decision", { owner: "someone" }),
    ContractError,
  );
  assert.throws(
    () =>
      validate("Decision", {
        operations: Array.from({ length: 33 }, (_, i) => ({
          key: String(i),
          capability: "entity.velocity.add",
          args: {},
        })),
      }),
    ContractError,
  );
  assert.throws(
    () =>
      validate("Decision", {
        timers: [{ action: "set", key: "loop", afterTicks: 0, payload: {} }],
      }),
    ContractError,
  );
});

test("render output uses its own contract and cannot smuggle world operations", () => {
  validate("VisualFrame", {
    primitives: [
      {
        kind: "text",
        key: "progress",
        channel: "actionbar",
        text: "蓄力中",
        ttlTicks: 5,
      },
    ],
  });
  assert.throws(
    () =>
      validate("VisualFrame", {
        primitives: [],
        operations: [{ capability: "entity.damage.request" }],
      }),
    ContractError,
  );
  assert.throws(
    () =>
      validate("VisualFrame", {
        primitives: [
          {
            kind: "text",
            key: "floating",
            channel: "floating",
            text: "missing position",
            ttlTicks: 5,
          },
        ],
      }),
    ContractError,
  );
});
