/* Generated from contracts/schemas. Do not edit. */

export interface RenderFrame {
  cueId: string;
  activity: ActivitySnapshot | null;
  elapsedTicks: number;
  progress: number | null;
  anchors: {
    [k: string]: WorldPosition;
  };
  /**
   * @maxItems 32
   */
  receipts: OperationReceipt[];
  data: {
    [k: string]: unknown;
  };
}
export interface ActivitySnapshot {
  activityId: string;
  key: string;
  phase: string;
  elapsedTicks: number;
  phaseElapsedTicks: number;
  data: {
    [k: string]: unknown;
  };
}
export interface WorldPosition {
  worldId: string;
  x: number;
  y: number;
  z: number;
}
export interface OperationReceipt {
  receiptId: string;
  requestKey: string;
  capability: string;
  status: "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR";
  code: string;
  data: {
    [k: string]: unknown;
  };
  observedTick: number;
}
