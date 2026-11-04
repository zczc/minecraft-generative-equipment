/* Generated from contracts/schemas. Do not edit. */

export type ActivityChange =
  | {
      action: "begin";
      key: string;
      phase: string;
      data: {
        [k: string]: unknown;
      };
    }
  | {
      action: "update";
      key: string;
      phase?: string;
      data?: {
        [k: string]: unknown;
      };
    }
  | {
      action: "end";
      key: string;
      reason: string;
    };
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

export interface Decision {
  nextState?: {
    [k: string]: unknown;
  };
  /**
   * @maxItems 32
   */
  activities?: ActivityChange[];
  /**
   * @maxItems 16
   */
  timers?:
    | []
    | [TimerCommand]
    | [TimerCommand, TimerCommand]
    | [TimerCommand, TimerCommand, TimerCommand]
    | [TimerCommand, TimerCommand, TimerCommand, TimerCommand]
    | [TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand]
    | [TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand]
    | [TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand]
    | [TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand, TimerCommand]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ]
    | [
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand,
        TimerCommand
      ];
  /**
   * @maxItems 32
   */
  operations?: OperationRequest[];
  /**
   * @maxItems 32
   */
  feedback?: FeedbackSignal[];
}
export interface OperationRequest {
  key: string;
  capability: string;
  args: {
    [k: string]: unknown;
  };
  activityKey?: string;
}
export interface FeedbackSignal {
  cueId: string;
  activityKey?: string;
  data?: {
    [k: string]: unknown;
  };
  /**
   * @maxItems 32
   */
  receiptIds?: string[];
}
