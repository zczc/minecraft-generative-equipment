import { parse } from "acorn";
import { parseDocument, visit, isAlias } from "yaml";
import { readFile } from "node:fs/promises";
import type { GeneratedSource, CapabilityProfile } from "@ge/protocol";

const object = (properties: Record<string, unknown>) => ({
  type: "object",
  properties,
  required: Object.keys(properties),
  additionalProperties: false,
});
const boundedNumber = (minimum: number, maximum: number, integer = false) => ({
  type: integer ? "integer" : "number",
  minimum,
  maximum,
});
const boundedText = { type: "string", maxLength: 64 };
const list = (items: unknown, maxItems = 16) => ({
  type: "array",
  items,
  maxItems,
});
const scheduler = object({
  jobs: list(
    object({ key: boundedText, remaining: boundedNumber(0, 1200, true) }),
  ),
  waits: list(
    object({ key: boundedText, remaining: boundedNumber(0, 1200, true) }),
  ),
  phases: list(object({ key: boundedText, phase: boundedText })),
});

/** 一次简短声明同时产生初值和有界 schema，避免模型把相同字段写两遍。 */
function stateField(value: any, depth = 0): { schema: any; initial: any } {
  if (depth > 12) throw new Error("State declaration too deep");
  if (typeof value === "boolean")
    return { schema: { type: "boolean" }, initial: value };
  if (Array.isArray(value)) {
    if (
      value.length >= 3 &&
      value
        .slice(0, 3)
        .every((x) => typeof x === "number" && Number.isFinite(x))
    ) {
      const [initial, min, max, kind] = value;
      if (
        value.length > 4 ||
        (kind !== undefined && kind !== "int") ||
        min > initial ||
        initial > max ||
        (kind === "int" && !value.slice(0, 3).every(Number.isInteger))
      )
        throw new Error("State number syntax: [initial,min,max,int?]");
      return { schema: boundedNumber(min, max, kind === "int"), initial };
    }
    if (
      value.length &&
      value.length <= 32 &&
      value.every((x) => typeof x === "string" && x.length <= 1024)
    )
      return {
        schema: { type: "string", enum: [...new Set(value)] },
        initial: value[0],
      };
    throw new Error("State field must be a number range or string enum");
  }
  if (value && typeof value === "object") {
    if (Object.hasOwn(value, "$text")) {
      if (
        Object.keys(value).some((k) => !["$text", "max"].includes(k)) ||
        typeof value.$text !== "string" ||
        !Number.isInteger(value.max) ||
        value.max < value.$text.length ||
        value.max > 1024
      )
        throw new Error("Invalid bounded text state");
      return {
        schema: { type: "string", maxLength: value.max },
        initial: value.$text,
      };
    }
    if (Object.hasOwn(value, "$list")) {
      if (
        Object.keys(value).some(
          (k) => !["$list", "max", "initial"].includes(k),
        ) ||
        !Number.isInteger(value.max) ||
        value.max < 0 ||
        value.max > 256 ||
        !Array.isArray(value.initial ?? [])
      )
        throw new Error("Invalid bounded list state");
      return {
        schema: list(stateField(value.$list, depth + 1).schema, value.max),
        initial: value.initial ?? [],
      };
    }
    const properties: Record<string, unknown> = {},
      initial: Record<string, unknown> = {};
    for (const [key, field] of Object.entries(value)) {
      if (["__proto__", "prototype", "constructor", "__ge"].includes(key))
        throw new Error("Reserved state key");
      const parsed = stateField(field, depth + 1);
      properties[key] = parsed.schema;
      initial[key] = parsed.initial;
    }
    return { schema: object(properties), initial };
  }
  throw new Error(
    "State needs a bound: number [initial,min,max], boolean, enum, object or bounded list/text",
  );
}

/** 平台生成固定的基本操作探针，不把测试序列发送给模型；它们只证明探针覆盖到的行为。 */
function probes(initialState: any): any[] {
  const target = {
    ref: "target-1",
    position: { worldId: "test-world", x: 3, y: 64, z: 0 },
    health: 20,
    onGround: true,
    sneaking: false,
    velocity: { x: 0, y: 0, z: 0 },
  };
  const queries = {
    "entity.nearby": { ok: true, data: { entities: [target] }, code: null },
    "view.raycast": {
      ok: true,
      data: { entity: target, position: target.position, block: null },
      code: null,
    },
    "actor.status": {
      ok: true,
      data: {
        ...target,
        ref: "self",
        position: { worldId: "test-world", x: 0, y: 64, z: 0 },
      },
      code: null,
    },
    "world.block": { ok: true, data: { block: "minecraft:stone" }, code: null },
  };
  const make = (id: string, moving: boolean, empty: boolean) => {
    const events: any[] = [];
    const add = (
      kind: string,
      tick: number,
      payload: any = {},
      phase = "observed",
    ) =>
      events.push({
        eventId: id + "-" + events.length,
        kind,
        tick,
        phase,
        payload,
      });
    add("item.activated", 0, {}, "internal");
    for (let tick = 1; tick <= 240; tick++) {
      if ([1, 31, 61, 121, 181].includes(tick))
        add("item.used", tick, { action: "right_click", hand: "main_hand" });
      if (moving && tick % 8 === 0)
        add("actor.move.sampled", tick, {
          from: { worldId: "test-world", x: 0, y: 64, z: 0 },
          to: { worldId: "test-world", x: 0.6, y: 64, z: 0.4 },
        });
      if (!moving && [15, 45, 90, 150, 210].includes(tick))
        add(
          "combat.damage.prepare",
          tick,
          { eventHandle: "hit-" + tick, target: target.ref, healthPoints: 4 },
          "prepare",
        );
    }
    add("test.advance", 240, {}, "internal");
    return {
      id,
      platformProbe: true,
      initialState,
      events,
      queries: empty
        ? {
            ...queries,
            "entity.nearby": { ok: true, data: { entities: [] }, code: null },
            "view.raycast": {
              ok: true,
              data: { entity: null, position: null, block: null },
              code: null,
            },
          }
        : queries,
    };
  };
  return [
    make("stationary", false, false),
    make("moving", true, false),
    make("no-target", true, true),
  ];
}

/**
 * 读取 YAML front matter 和原样 JS，只展开通用结构，不改写装备算法。
 * 严格禁止 YAML 别名、危险键和自定义标签；JS 只经过语法分析，实际执行仍在 QuickJS。
 */
export async function parseCompactOutput(
  raw: string,
  profile: CapabilityProfile,
): Promise<GeneratedSource> {
  if (Buffer.byteLength(raw, "utf8") > 24000)
    throw new Error(
      "Equipment source is too long; keep only unique logic within 1000–2000 output tokens",
    );
  raw = raw.trim().replace(/\r\n/g, "\n");
  const fenced = raw.match(/^```(?:text|javascript|js)?\s*\n([\s\S]*?)\n```$/);
  if (fenced) raw = fenced[1]!;
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]+)$/);
  if (!match)
    throw new Error(
      "Use --- newline YAML metadata newline --- newline JavaScript functions act(g,e) and draw(v,f)",
    );
  const doc = parseDocument(match[1]!, {
    uniqueKeys: true,
    merge: false,
    schema: "core",
  });
  if (doc.errors.length || doc.warnings.length)
    throw new Error(String((doc.errors[0] ?? doc.warnings[0])?.message));
  let nodes = 0;
  visit(doc, (_, node) => {
    if (++nodes > 4096 || isAlias(node))
      throw new Error("YAML aliases or excessive metadata are not allowed");
  });
  const metadata = doc.toJS({ maxAliasCount: 0 });
  const safe = (x: any, depth = 0) => {
    if (depth > 16) throw new Error("Metadata too deep");
    if (typeof x === "number" && !Number.isFinite(x))
      throw new Error("Non-finite metadata number");
    if (x && typeof x === "object")
      for (const [k, v] of Object.entries(x)) {
        if (["__proto__", "prototype", "constructor", "__ge"].includes(k))
          throw new Error("Reserved metadata key");
        safe(v, depth + 1);
      }
  };
  safe(metadata);
  if (
    !metadata ||
    Array.isArray(metadata) ||
    typeof metadata !== "object" ||
    Object.keys(metadata).some(
      (k) =>
        !["name", "description", "base", "state", "params", "cues"].includes(k),
    )
  )
    throw new Error("Metadata fields: name,description,base,state,params,cues");
  const fields = stateField(metadata.state ?? {});
  if (fields.schema.type !== "object")
    throw new Error("state must map field names to declarations");
  fields.schema.properties.__ge = scheduler;
  fields.schema.required.push("__ge");
  fields.initial.__ge = { jobs: [], waits: [], phases: [] };
  const publicCues: Record<string, any> = {};
  const labels: Record<string, string> = {};
  const cues: Record<string, any> = {},
    bindings: any[] = [];
  if (
    !metadata.cues ||
    Array.isArray(metadata.cues) ||
    typeof metadata.cues !== "object" ||
    Object.keys(metadata.cues).length > 16 ||
    Object.keys(metadata.cues).length === 0
  )
    throw new Error(
      "Declare 1..16 cues: id: [category,ticks,audience?,receiptStatus?]",
    );
  for (const [id, entry] of Object.entries(metadata.cues) as [string, any][]) {
    if (
      !Array.isArray(entry) ||
      entry.length < 2 ||
      entry.length > 4 ||
      !["preview", "ongoing", "result", "failure"].includes(entry[0])
    )
      throw new Error("Cue syntax: [category,ticks,audience?,receiptStatus?]");
    const [category, durationTicks, audience = "nearby", status = "APPLIED"] =
      entry;
    const internalId = "cue-" + Object.keys(cues).length;
    cues[internalId] = {
      category,
      durationTicks,
      audience,
      renderer: "render",
      ...(category === "result" ? { requiredReceiptStatus: [status] } : {}),
    };
    publicCues[id] = { ...cues[internalId], id: internalId };
    labels[internalId] = id;
    if (category !== "result")
      bindings.push({
        cueId: internalId,
        trigger: "activity.phase",
        match: { phase: internalId },
      });
  }
  let code = match[2]!;
  const ast = parse(code, { ecmaVersion: 2024, sourceType: "module" });
  const removals: [number, number][] = [];
  let hasAct = false;
  for (const n of ast.body) {
    if (n.type === "FunctionDeclaration" && n.id?.name === "act") hasAct = true;
    if (
      n.type === "ExportNamedDeclaration" &&
      n.declaration?.type === "FunctionDeclaration" &&
      ["act", "draw"].includes(n.declaration.id?.name ?? "")
    ) {
      if (n.declaration.id?.name === "act") hasAct = true;
      removals.push([n.start, n.declaration.start]);
    } else if (n.type.startsWith("Export"))
      throw new Error("Only act/draw exports are accepted");
  }
  const walk = (node: any) => {
    if (!node || typeof node !== "object") return;
    if (
      node.type === "Identifier" &&
      (node.name.startsWith("__ge") ||
        ["onEvent", "render"].includes(node.name))
    )
      throw new Error("Reserved framework identifier");
    for (const value of Object.values(node))
      if (Array.isArray(value)) value.forEach(walk);
      else if (value && typeof value === "object") walk(value);
  };
  walk(ast);
  if (!hasAct) throw new Error("Missing function act(g,e)");
  for (const [start, end] of removals.reverse())
    code = code.slice(0, start) + " ".repeat(end - start) + code.slice(end);
  const runtime = await readFile(
    new URL(
      "../../../../../packages/equipment-sdk/runtime/compact-v1.js",
      import.meta.url,
    ),
    "utf8",
  );
  const equipment = {
    formatVersion: 1,
    name: metadata.name,
    description: metadata.description,
    baseItem: String(metadata.base).includes(":")
      ? metadata.base
      : "minecraft:" + metadata.base,
    activationSlots: ["main_hand"],
    stateSchema: fields.schema,
    initialState: fields.initial,
    parameters: metadata.params ?? {},
    subscriptions: profile.events,
    requires: { queries: profile.queries, operations: profile.operations },
    presentation: { cues, bindings },
    scenarios: probes(fields.initial),
  };
  return {
    equipment,
    behaviorSource: `const __geSpec=${JSON.stringify({ cues: publicCues, labels })};\n${code}\n${runtime}`,
  } as GeneratedSource;
}
