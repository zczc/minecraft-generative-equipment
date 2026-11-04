/* Generated from contracts/schemas. Do not edit. */

/**
 * @maxItems 100
 */
export type ObservationBatch = Observation[];

export interface Observation {
  eventId: string;
  definitionRef: DefinitionRef;
  codeHash: string;
  activationId: string;
  activityId: string | null;
  kind: string;
  payload: {
    [k: string]: unknown;
  };
  observedAt: string;
}
export interface DefinitionRef {
  definitionId: string;
  version: number;
}
