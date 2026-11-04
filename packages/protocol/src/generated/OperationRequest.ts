/* Generated from contracts/schemas. Do not edit. */

export interface OperationRequest {
  key: string;
  capability: string;
  args: {
    [k: string]: unknown;
  };
  activityKey?: string;
}
