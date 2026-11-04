/* Generated from contracts/schemas. Do not edit. */

export interface ApiError {
  schemaVersion: 1;
  requestId: string;
  error: {
    code: string;
    message: string;
    retryable: boolean;
    details?: {
      [k: string]: unknown;
    };
  };
}
