/* Generated from contracts/schemas. Do not edit. */

export interface PackageManifest {
  packageFormatVersion: 1;
  definitionRef: DefinitionRef;
  protocolVersion: "1";
  capabilityProfileId: string;
  engineBuildId: string;
  worldRuleRef: WorldRef;
  sourceHash: string;
  /**
   * @maxItems 32
   */
  files: {
    path: string;
    sha256: string;
    bytes: number;
  }[];
  /**
   * @maxItems 128
   */
  subscriptions: string[];
  /**
   * @maxItems 128
   */
  requiredCapabilities: string[];
  /**
   * @maxItems 6
   */
  activationSlots:
    | []
    | [string]
    | [string, string]
    | [string, string, string]
    | [string, string, string, string]
    | [string, string, string, string, string]
    | [string, string, string, string, string, string];
  origin: "model" | "fixture";
}
export interface DefinitionRef {
  definitionId: string;
  version: number;
}
export interface WorldRef {
  id: string;
  version: string;
}
