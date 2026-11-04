/* Generated from contracts/schemas. Do not edit. */

export interface GenerationView {
  schemaVersion: 1;
  taskId: string;
  status: "QUEUED" | "GENERATING" | "VALIDATING" | "REPAIRING" | "READY" | "FAILED" | "CANCELLED";
  revision: number;
  inputHash: string;
  definitionRef: DefinitionRef | null;
  error: {
    [k: string]: unknown;
  } | null;
  createdAt: string;
}
export interface DefinitionRef {
  definitionId: string;
  version: number;
}
