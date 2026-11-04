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
