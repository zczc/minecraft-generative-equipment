/* Generated from contracts/schemas. Do not edit. */

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
