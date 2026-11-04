/* Generated from contracts/schemas. Do not edit. */

export interface GenerationRequest {
  schemaVersion: 1;
  taskId: string;
  hostId: string;
  recipientPlayerId: string;
  worldRuleRef: WorldRef;
  capabilityProfileId: string;
  intentText: string;
  /**
   * @maxItems 8
   */
  materials:
    | []
    | [MaterialInput]
    | [MaterialInput, MaterialInput]
    | [MaterialInput, MaterialInput, MaterialInput]
    | [MaterialInput, MaterialInput, MaterialInput, MaterialInput]
    | [MaterialInput, MaterialInput, MaterialInput, MaterialInput, MaterialInput]
    | [MaterialInput, MaterialInput, MaterialInput, MaterialInput, MaterialInput, MaterialInput]
    | [MaterialInput, MaterialInput, MaterialInput, MaterialInput, MaterialInput, MaterialInput, MaterialInput]
    | [
        MaterialInput,
        MaterialInput,
        MaterialInput,
        MaterialInput,
        MaterialInput,
        MaterialInput,
        MaterialInput,
        MaterialInput
      ];
  /**
   * @maxItems 4
   */
  sourceEquipment:
    | []
    | [SourceEquipment]
    | [SourceEquipment, SourceEquipment]
    | [SourceEquipment, SourceEquipment, SourceEquipment]
    | [SourceEquipment, SourceEquipment, SourceEquipment, SourceEquipment];
  generationContext: GenerationContext;
}
export interface WorldRef {
  id: string;
  version: string;
}
export interface MaterialInput {
  definitionId: string;
  count: number;
}
export interface SourceEquipment {
  definitionId: string;
  version: number;
  bundleHash: string;
  state: {
    [k: string]: unknown;
  };
}
export interface GenerationContext {
  dimension: string;
  biome: string | null;
  position: WorldPosition;
  worldTimeTicks: number;
  weather: {
    worldRain: boolean | null;
    exposedToRain: boolean | null;
  };
  nearbyBlocks: {
    samplingVersion: string;
    /**
     * @maxItems 64
     */
    samples: {
      blockId: string;
      count: number;
    }[];
    missingReason: string | null;
  };
}
export interface WorldPosition {
  worldId: string;
  x: number;
  y: number;
  z: number;
}
