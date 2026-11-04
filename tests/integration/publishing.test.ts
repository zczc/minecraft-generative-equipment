import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { execFileSync } from "node:child_process";
import {
  hashJson,
  type GeneratedSource,
  type CapabilityProfile,
} from "@ge/protocol";
import { harness, input, auth } from "../helpers.js";
import {
  ValidatorProcess,
  ENGINE,
  checkSource,
} from "../../services/generator/src/adapters/validator-process.js";
import { generationConfig } from "../../services/generator/src/config.js";
import { taskView } from "../../services/generator/src/adapters/sqlite-store.js";

test("real validator, immutable publication, tamper rejection and delivery idempotency", async () => {
  const h = await harness();
  try {
    const caps = JSON.parse(
      await readFile("contracts/capabilities/v1/host.json", "utf8"),
    );
    const p: CapabilityProfile = {
      profileId: "profile-real",
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
    const source = JSON.parse(
      await readFile("tests/fixtures/equipment/pulse.source.json", "utf8"),
    ) as GeneratedSource;
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
    const report = await new ValidatorProcess(config).validate(
      source,
      new AbortController().signal,
      p,
    );
    assert.equal(report.passed, true, JSON.stringify(report));
    h.store.saveProfile(p);
    const request = {
      ...input("publish-test"),
      capabilityProfileId: p.profileId,
    };
    const expanded = {
      ...(await h.worlds.resolveInput(request)),
      capabilityProfile: p,
    };
    let row = h.store.create(request, expanded).row;
    row = h.store.transition(
      row.task_id,
      row.host_id,
      row.revision,
      "QUEUED",
      "GENERATING",
    );
    row = h.store.transition(
      row.task_id,
      row.host_id,
      row.revision,
      "GENERATING",
      "VALIDATING",
    );
    await assert.rejects(() =>
      h.definitions.publish(
        row,
        expanded,
        { source, usage: null },
        { ...report, checks: [] },
      ),
    );
    await assert.rejects(() =>
      h.definitions.publish(
        row,
        expanded,
        {
          source: { ...source, behaviorSource: source.behaviorSource + " " },
          usage: null,
        },
        report,
      ),
    );
    await h.definitions.publish(
      row,
      expanded,
      { source, usage: null, model: "test-double", origin: "fixture" },
      report,
    );
    const published = taskView(h.store.get(row.task_id, row.host_id)!);
    assert.equal(published.status, "READY");
    assert.ok(published.definitionRef);
    const def = h.definitions.get(
      published.definitionRef.definitionId,
      1,
      row.host_id,
    )!;
    const bundle = await h.definitions.bundle(def);
    assert.ok(bundle.length > 0);
    const provenance = JSON.parse(def.provenance_json);
    assert.equal(provenance.request.intentText, request.intentText);
    assert.equal(
      h.definitions.get(published.definitionRef.definitionId, 1, "other-host"),
      undefined,
    );
    const event = {
      eventId: "delivery-1",
      deliveryId: "delivery-test",
      hostId: row.host_id,
      taskId: row.task_id,
      sequenceNo: 1,
      state: "PREPARED" as const,
      evidence: {},
    };
    assert.equal(h.definitions.delivery(event, row.host_id).duplicate, false);
    assert.equal(h.definitions.delivery(event, row.host_id).duplicate, true);
    assert.throws(() =>
      h.definitions.delivery(
        { ...event, evidence: { changed: true } },
        row.host_id,
      ),
    );
    assert.throws(() =>
      h.definitions.delivery(
        { ...event, eventId: "other", deliveryId: "other" },
        row.host_id,
      ),
    );
    h.definitions.delivery(
      { ...event, eventId: "delivery-2", sequenceNo: 2, state: "APPLIED" },
      row.host_id,
    );
    h.definitions.delivery(
      { ...event, eventId: "delivery-3", sequenceNo: 3, state: "ACKNOWLEDGED" },
      row.host_id,
    );
    const observation = {
      eventId: "observation-test",
      definitionRef: published.definitionRef,
      codeHash: hashJson(source),
      activationId: "activation-test",
      activityId: null,
      kind: "operation.receipt",
      payload: { status: "APPLIED" },
      observedAt: new Date().toISOString(),
    };
    assert.equal(h.definitions.observe(observation, row.host_id), "accepted");
    assert.equal(h.definitions.observe(observation, row.host_id), "duplicate");
    const response = await h.app.inject({
      url: `/v1/observations?definitionId=${published.definitionRef.definitionId}&version=1`,
      headers: auth,
    });
    assert.equal(response.json().items.length, 1);
  } finally {
    await h.cleanup();
  }
});
test("source precheck rejects OS modules, regex and asynchronous handlers", async () => {
  const source = JSON.parse(
    await readFile("tests/fixtures/equipment/pulse.source.json", "utf8"),
  ) as GeneratedSource;
  for (const behaviorSource of [
    "import fs from 'fs'; export function onEvent(){return {}}",
    "export function onEvent(){return /x/.test('x')}",
    "export async function onEvent(){return {}}",
  ])
    assert.throws(() => checkSource({ ...source, behaviorSource }));
});
