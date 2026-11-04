import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { parseCompactOutput } from "../../services/generator/src/adapters/aliyun/compact-output.js";
import {
  ValidatorProcess,
  ENGINE,
} from "../../services/generator/src/adapters/validator-process.js";
import { generationConfig } from "../../services/generator/src/config.js";
import type { CapabilityProfile } from "@ge/protocol";
const caps = JSON.parse(
  await readFile("contracts/capabilities/v1/host.json", "utf8"),
);
const profile: CapabilityProfile = {
  profileId: "compact-test",
  hostId: "local-paper",
  hostBuildId: caps.buildId,
  protocolVersion: "1",
  engineBuildId: ENGINE,
  events: caps.events,
  queries: caps.queries,
  operations: caps.operations,
  visuals: caps.visuals,
  limits: caps.limits,
};
const raw = await readFile(
  "tests/fixtures/equipment/pulse.compact.txt",
  "utf8",
);
const config = generationConfig({});
config.javaCommand = execFileSync(
  process.execPath,
  [
    "--input-type=module",
    "-e",
    'import {javaHome} from "./scripts/java.mjs";console.log((await javaHome())+"/bin/java")',
  ],
  { encoding: "utf8" },
).trim();
const validator = new ValidatorProcess(config);

test("compact declarations expand to bounded state and platform probes; real QuickJS runs them", async () => {
  const source = await parseCompactOutput(raw, profile);
  assert.equal(source.equipment.name, "回声脉冲（手写测试装备）");
  assert.equal(source.equipment.initialState.echoes, 0);
  assert.equal(
    (source.equipment.stateSchema as any).properties.echoes.maximum,
    8,
  );
  const result = await validator.validate(
    source,
    new AbortController().signal,
    profile,
  );
  assert.equal(result.passed, true, JSON.stringify(result));
});

test("compact YAML rejects aliases, duplicate keys, tags, unbounded fields and reserved identifiers", async () => {
  for (const changed of [
    raw.replace("name: 回声脉冲（手写测试装备）", "name: a\nname: b"),
    raw.replace("base: feather", "base: &x feather\nparams: {a: *x}"),
    raw.replace("base: feather", "base: !custom feather"),
    raw.replace("echoes: [0, 0, 8, int]", "echoes: 0"),
    raw.replace("echoes: [0, 0, 8, int]", "__ge: [0, 0, 8]"),
    raw.replace("echoes: [0, 0, 8, int]", "echoes: [9, 0, 8]"),
    raw.replace("function act", "function __geAttack"),
  ])
    await assert.rejects(() => parseCompactOutput(changed, profile));
});

test("no-op rules and false result cues cannot pass platform probes", async () => {
  for (const code of [
    "function act(g,e) {}",
    "function act(g,e) {if(e.type==='use')g.cue('release');}",
  ]) {
    const text = raw.slice(0, raw.indexOf("function act")) + code;
    const source = await parseCompactOutput(text, profile);
    const result = await validator.validate(
      source,
      new AbortController().signal,
      profile,
    );
    assert.equal(result.passed, false, JSON.stringify(result));
  }
});

test("saved scheduler state resumes after activation; cooldown and result state stay valid", async () => {
  const source = await parseCompactOutput(raw, profile);
  const initial = structuredClone(source.equipment.initialState) as any;
  initial.__ge = {
    jobs: [{ key: "release", remaining: 2 }],
    waits: [{ key: "use", remaining: 4 }],
    phases: [{ key: "main", phase: "charging" }],
  };
  const scenarios = source.equipment.scenarios as any[];
  scenarios.push({
    ...scenarios[0],
    id: "resume",
    platformProbe: false,
    initialState: initial,
    events: [
      {
        eventId: "resume0",
        kind: "item.activated",
        tick: 0,
        phase: "internal",
        payload: {},
      },
      {
        eventId: "resume2",
        kind: "test.advance",
        tick: 2,
        phase: "internal",
        payload: {},
      },
    ],
    expectedState: {
      echoes: 1,
      __ge: { jobs: [], waits: [{ key: "use", remaining: 20 }], phases: [] },
    },
    expectedOperationCapabilities: ["entity.velocity.add"],
  });
  const result = await validator.validate(
    source,
    new AbortController().signal,
    profile,
  );
  assert.equal(result.passed, true, JSON.stringify(result));
});

test("Chinese phase and activity keys are normalized by the framework", async () => {
  const text = raw
    .replaceAll("charging", "充能")
    .replace("g.phase('充能')", "g.phase('充能','蓄势')")
    .replaceAll("g.end()", "g.end('蓄势')");
  const source = await parseCompactOutput(text, profile);
  const result = await validator.validate(
    source,
    new AbortController().signal,
    profile,
  );
  assert.equal(result.passed, true, JSON.stringify(result));
});
