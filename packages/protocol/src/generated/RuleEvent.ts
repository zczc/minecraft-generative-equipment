/* Generated from contracts/schemas. Do not edit. */

export interface RuleEvent {
  eventId: string;
  kind: string;
  tick: number;
  phase: "prepare" | "observed" | "internal";
  payload: {
    [k: string]: unknown;
  };
}
