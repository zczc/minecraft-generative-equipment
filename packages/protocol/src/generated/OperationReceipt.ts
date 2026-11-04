/* Generated from contracts/schemas. Do not edit. */

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
