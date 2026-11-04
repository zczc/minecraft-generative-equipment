/** compact-v1 面向生成装备的简短接口；运行时实现由宿主附加，不由模型重新生成。 */
export const COMPACT_SDK_VERSION = "compact-v1";
export type Vec3 = { x: number; y: number; z: number };
export type WorldPoint = Vec3 & { worldId: string };
export type EntitySnapshot = {
  ref: string;
  position: WorldPoint;
  health: number;
  onGround: boolean;
  sneaking: boolean;
  velocity: Vec3;
};
export type CompactEvent = {
  type: "activate" | "use" | "move" | "hit" | "timer" | "tick" | "receipts";
  tick: number;
  key?: string;
  from?: WorldPoint;
  to?: WorldPoint;
  target?: string;
  healthPoints?: number;
  receipts?: unknown[];
};
/** 在隔离上下文中同步处理事件。s 为本次可变副本，调用结束后由框架完整校验、提交。 */
export interface CompactGame<
  S = Record<string, unknown>,
  P = Record<string, unknown>,
> {
  s: S;
  readonly p: Readonly<P>;
  readonly self: EntitySnapshot;
  readonly tick: number;
  readonly applied: number;
  readonly proposed: number;
  readonly receipts: readonly unknown[];
  random(): number;
  near(radius?: number, limit?: number): EntitySnapshot[];
  ray(distance?: number): {
    entity: EntitySnapshot | null;
    position: WorldPoint | null;
    block: string | null;
  } | null;
  status(ref?: string): EntitySnapshot | null;
  block(position: WorldPoint): { block: string } | null;
  /** 只提交世界操作；applied/proposed 仅在随后的真实回执事件中可用于成功判断。 */
  push(ref: string, x: number, y: number, z: number): void;
  hurt(ref: string, healthPoints: number): void;
  damage(healthPoints: number): void;
  /** 任务按剩余激活刻数保存；收起期间不执行，重新拿起后恢复。 */
  after(key: string, ticks: number): void;
  cancel(key: string): void;
  cooldown(key: string, ticks: number): void;
  ready(key: string): boolean;
  remaining(key: string): number;
  phase(name: string, key?: string): void;
  end(key?: string): void;
  /** result 类反馈必须有本次符合状态要求的回执，否则拒绝该次决策。 */
  cue(name: string): void;
}
/** 绘图不提供世界操作和查询，坐标为相对玩家的偏移；宿主限制数量并清理播放资源。 */
export interface CompactVisual<
  S = Record<string, unknown>,
  P = Record<string, unknown>,
> {
  readonly s: Readonly<S>;
  readonly p: Readonly<P>;
  readonly cue: string;
  readonly t: number;
  particles(
    name: string,
    count: number,
    point: (index: number) => [number, number, number],
  ): void;
  sound(name: string, pitch?: number, volume?: number): void;
  text(text: string): void;
  item(
    name: string,
    offset?: [number, number, number],
    scale?: number,
    angle?: number,
  ): void;
}
