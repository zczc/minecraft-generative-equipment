/* compact-v1：由生成服务附加的公共适配器，不要求模型重复生成。
 * 只在 QuickJS 中运行。事件同步处理、决策一次性提交，任何越界仍由宿主拒绝。
 * __geSpec 和 act/draw 由编译器放在同一模块中，__ge 前缀为框架保留。
 */
// 中文阶段和活动键不交给模型转换成内部编号；两路散列保持短且跨调用稳定。
function __geKey(text) {
  let a = 2166136261,
    b = 5381;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    a = Math.imul(a ^ c, 16777619);
    b = Math.imul(b, 33) ^ c;
  }
  return (a >>> 0).toString(16) + "-" + (b >>> 0).toString(16);
}
function __geRun(ctx, event) {
  const state = JSON.parse(JSON.stringify(ctx.state));
  const sys = state.__ge;
  delete state.__ge;
  const changes = [],
    operations = [],
    feedback = [];
  const phases = new Map(sys.phases.map((x) => [x.key, x.phase]));
  const hostPhases = new Map(ctx.activities.map((x) => [x.key, x.phase]));
  const receipts =
    event.kind === "operation.results" ? event.payload.receipts : [];
  const checkKey = (key) => {
    if (typeof key !== "string" || key.length < 1 || key.length > 64)
      throw new Error("Invalid scheduler key");
  };
  const set = (list, key, ticks) => {
    checkKey(key);
    if (!Number.isInteger(ticks) || ticks < 1 || ticks > 1200)
      throw new Error("Delay must be 1..1200 ticks");
    const old = list.find((x) => x.key === key);
    if (old) old.remaining = ticks;
    else {
      if (list.length >= 16) throw new Error("Scheduler limit exceeded");
      list.push({ key, remaining: ticks });
    }
  };
  const phase = (name, key = "main") => {
    checkKey(name);
    checkKey(key);
    const nativeName = __geSpec.cues[name]?.id ?? "phase-" + __geKey(name);
    const nativeKey = "act-" + __geKey(key);
    for (const other of phases.keys())
      if (other !== key && __geKey(other) === __geKey(key))
        throw new Error("Activity key collision");
    if (hostPhases.has(nativeKey))
      changes.push({ action: "update", key: nativeKey, phase: nativeName });
    else
      changes.push({
        action: "begin",
        key: nativeKey,
        phase: nativeName,
        data: {},
      });
    hostPhases.set(nativeKey, nativeName);
    phases.set(key, name);
  };
  const op = (capability, args) =>
    operations.push({ key: "op" + operations.length, capability, args });
  const query = (name, args) => {
    const q = ctx.query(name, args);
    return q.ok ? q.data : null;
  };
  const g = {
    s: state,
    p: ctx.parameters,
    self: ctx.self,
    tick: ctx.clock.tick,
    random: ctx.random,
    applied: receipts.filter((x) => x.status === "APPLIED").length,
    proposed: receipts.filter((x) => x.status === "PROPOSED").length,
    receipts,
    near: (radius = 6, limit = 8) =>
      (query("entity.nearby", { radius, limit }) || { entities: [] }).entities,
    ray: (maxDistance = 12) => query("view.raycast", { maxDistance }),
    block: (position) => query("world.block", { position }),
    status: (target = "self") => query("actor.status", { target }),
    push: (target, x, y, z) =>
      op("entity.velocity.add", { target, deltaVelocity: { x, y, z } }),
    hurt: (target, healthPoints) =>
      op("entity.damage.request", { target, healthPoints }),
    damage: (healthPoints) => {
      if (event.kind !== "combat.damage.prepare")
        throw new Error("damage requires current outgoing hit event");
      op("combat.damage.set", {
        eventHandle: event.payload.eventHandle,
        healthPoints,
      });
    },
    after: (key, ticks) => set(sys.jobs, key, ticks),
    cancel: (key) => {
      sys.jobs = sys.jobs.filter((x) => x.key !== key);
    },
    cooldown: (key, ticks) => {
      if (ticks === 0) sys.waits = sys.waits.filter((x) => x.key !== key);
      else set(sys.waits, key, ticks);
    },
    ready: (key) => !sys.waits.some((x) => x.key === key),
    remaining: (key) =>
      (
        sys.jobs.find((x) => x.key === key) ||
        sys.waits.find((x) => x.key === key) || { remaining: 0 }
      ).remaining,
    phase,
    end: (key = "main") => {
      const nativeKey = "act-" + __geKey(key);
      if (hostPhases.has(nativeKey))
        changes.push({ action: "end", key: nativeKey, reason: "rule" });
      hostPhases.delete(nativeKey);
      phases.delete(key);
    },
    cue: (id) => {
      const cue = __geSpec.cues[id];
      if (!cue) throw new Error("Unknown cue " + id);
      const signal = { cueId: cue.id };
      if (cue.category === "result") {
        signal.receiptIds = receipts
          .filter((x) => cue.requiredReceiptStatus.includes(x.status))
          .map((x) => x.receiptId);
        if (!signal.receiptIds.length)
          throw new Error("Result cue needs a matching receipt");
      }
      feedback.push(signal);
    },
  };
  const pulse =
    event.kind === "timer.fired" && event.payload.key === "ge-heartbeat";
  if (event.kind === "item.activated") {
    // 收起期间宿主不运行任务；重新激活按物品保存的剩余时间和阶段恢复。
    for (const [key, name] of phases) phase(name, key);
  }
  if (pulse) {
    for (const x of sys.waits) x.remaining--;
    sys.waits = sys.waits.filter((x) => x.remaining > 0);
    for (const x of sys.jobs) x.remaining--;
    const due = sys.jobs.filter((x) => x.remaining <= 0);
    sys.jobs = sys.jobs.filter((x) => x.remaining > 0);
    for (const x of due)
      act(g, { type: "timer", key: x.key, tick: event.tick });
    act(g, { type: "tick", tick: event.tick });
  } else {
    const type = {
      "item.activated": "activate",
      "item.used": "use",
      "actor.move.sampled": "move",
      "combat.damage.prepare": "hit",
      "operation.results": "receipts",
    }[event.kind];
    if (type) act(g, { ...event.payload, type, tick: event.tick });
  }
  sys.phases = Array.from(phases, ([key, phase]) => ({ key, phase }));
  const decision = { nextState: { ...state, __ge: sys } };
  if (changes.length) decision.activities = changes;
  if (operations.length) decision.operations = operations;
  if (feedback.length) decision.feedback = feedback;
  if (pulse || event.kind === "item.activated")
    decision.timers = [
      { action: "set", key: "ge-heartbeat", afterTicks: 1, payload: {} },
    ];
  return decision;
}

/* 画图只产生相对玩家位置的表现数据，不提供查询或修改世界的接口。 */
function __geDraw(ctx, frame) {
  const primitives = [],
    anchor = frame.anchors.actor;
  const position = (xyz) => ({
    worldId: anchor.worldId,
    x: anchor.x + xyz[0],
    y: anchor.y + xyz[1],
    z: anchor.z + xyz[2],
  });
  const resource = (id) => (id.includes(":") ? id : "minecraft:" + id);
  const v = {
    s: ctx.state,
    p: ctx.parameters,
    cue: __geSpec.labels[frame.cueId],
    t: frame.elapsedTicks,
    particles: (id, count, point) => {
      if (!Number.isInteger(count) || count < 1 || count > 64)
        throw new Error("Particle point count must be 1..64");
      const points = [];
      for (let i = 0; i < count; i++) points.push(position(point(i)));
      primitives.push({
        kind: "particle",
        key: "p" + primitives.length,
        particleKey: resource(id),
        points,
        countPerPoint: 1,
      });
    },
    sound: (id, pitch = 1, volume = 0.4) =>
      primitives.push({
        kind: "sound",
        key: "s" + primitives.length,
        soundKey: resource(id),
        position: anchor,
        pitch,
        volume,
      }),
    text: (text) =>
      primitives.push({
        kind: "text",
        key: "t" + primitives.length,
        channel: "actionbar",
        text,
        ttlTicks: 2,
      }),
    item: (id, offset = [0, 1, 0], scale = 0.4, angle = 0) =>
      primitives.push({
        kind: "display",
        key: "d" + primitives.length,
        resource: { kind: "item", id: resource(id) },
        position: position(offset),
        scale: { x: scale, y: scale, z: scale },
        rotation: {
          x: 0,
          y: Math.sin(angle / 2),
          z: 0,
          w: Math.cos(angle / 2),
        },
        ttlTicks: 2,
      }),
  };
  if (typeof draw === "function")
    draw(v, {
      cue: __geSpec.labels[frame.cueId],
      t: frame.elapsedTicks,
      activity: frame.activity,
    });
  else
    v.particles("end_rod", 8, (i) => [
      Math.cos((i * Math.PI) / 4) * 0.5,
      0.5,
      Math.sin((i * Math.PI) / 4) * 0.5,
    ]);
  return { primitives };
}
export function onEvent(ctx, event) {
  return __geRun(ctx, event);
}
export function render(ctx, frame) {
  return __geDraw(ctx, frame);
}
