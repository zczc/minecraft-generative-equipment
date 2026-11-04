/* Generated from contracts/schemas. Do not edit. */

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
