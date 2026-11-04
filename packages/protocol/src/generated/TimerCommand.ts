/* Generated from contracts/schemas. Do not edit. */

export type TimerCommand =
  | {
      action: "set";
      key: string;
      afterTicks: number;
      activityKey?: string;
      payload: {
        [k: string]: unknown;
      };
    }
  | {
      action: "cancel";
      key: string;
    };
