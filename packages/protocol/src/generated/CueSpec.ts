/* Generated from contracts/schemas. Do not edit. */

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
