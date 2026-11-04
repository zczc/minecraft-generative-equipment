/* Generated from contracts/schemas. Do not edit. */

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
