import type {
  Decision,
  RuleEvent,
  ActivitySnapshot,
  RenderFrame,
  VisualFrame,
} from "@ge/protocol";
export type {
  ActivitySnapshot,
  RenderFrame,
  VisualFrame,
  VisualPrimitive,
} from "@ge/protocol";
export type JsonValue =
  | null
  | boolean
  | number
  | string
  | JsonValue[]
  | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
/** 规则只能读取快照、使用受控查询并返回提案，不能持有 Bukkit 或 JVM 对象。 */
export type RuleContext = {
  readonly state: Readonly<JsonObject>;
  readonly parameters: Readonly<JsonObject>;
  readonly self: Readonly<JsonObject>;
  readonly activities: readonly ActivitySnapshot[];
  readonly clock: { worldSessionId: string; tick: number };
  query(
    name: string,
    args: JsonObject,
  ):
    | { ok: true; data: JsonValue }
    | { ok: false; code: string; message: string };
  random(): number;
};
/** 表现上下文故意不暴露查询、计时和世界修改入口。 */
export type RenderContext = Pick<RuleContext, "state" | "parameters" | "clock">;
/** 模型生成的代码入口；阶段和业务字段自由定义，访问能力由宿主约束。 */
export interface EquipmentProgram {
  onEvent(ctx: RuleContext, event: RuleEvent): Decision;
  render?(ctx: RenderContext, frame: RenderFrame): VisualFrame;
}
/** 纯数学工具，不读取时间、随机数或外部状态。 */
export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}
