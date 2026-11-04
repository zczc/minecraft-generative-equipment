export class ApiFault extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details?: unknown,
    public retryable = false,
  ) {
    super(message);
  }
}
export const notImplemented = (component: string) =>
  new ApiFault(
    501,
    "NOT_IMPLEMENTED",
    `${component} is not implemented in S0.`,
  );
