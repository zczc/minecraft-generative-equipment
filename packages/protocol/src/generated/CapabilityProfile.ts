/* Generated from contracts/schemas. Do not edit. */

export interface CapabilityProfile {
  profileId: string;
  hostId: string;
  hostBuildId: string;
  protocolVersion: "1";
  engineBuildId: string;
  /**
   * @maxItems 128
   */
  events: string[];
  /**
   * @maxItems 128
   */
  queries: string[];
  /**
   * @maxItems 128
   */
  operations: string[];
  /**
   * @maxItems 128
   */
  visuals: string[];
  limits: {
    [k: string]: unknown;
  };
}
