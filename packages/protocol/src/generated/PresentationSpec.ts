/* Generated from contracts/schemas. Do not edit. */

export interface PresentationSpec {
  cues: {
    [k: string]: CueSpec;
  };
  /**
   * @maxItems 128
   */
  bindings: {
    cueId: string;
    trigger: "activity.phase" | "operation.receipt" | "feedback";
    match: {
      [k: string]: unknown;
    };
  }[];
}
export interface CueSpec {
  category: "preview" | "ongoing" | "result" | "failure";
  audience: "actor" | "nearby";
  maxDistance?: number;
  durationTicks: number;
  recipe?: {
    [k: string]: unknown;
  };
  renderer?: "render";
  /**
   * @maxItems 4
   */
  requiredReceiptStatus?:
    | []
    | ["PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"]
    | ["PROPOSED" | "APPLIED" | "REJECTED" | "ERROR", "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"]
    | [
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"
      ]
    | [
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"
      ];
}
