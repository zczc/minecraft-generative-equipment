/* Generated from contracts/schemas. Do not edit. */

export type VisualPrimitive =
  | {
      kind: "particle";
      key: string;
      particleKey: string;
      /**
       * @maxItems 256
       */
      points: WorldPosition[];
      countPerPoint: number;
      options?: {
        [k: string]: unknown;
      };
    }
  | {
      kind: "sound";
      key: string;
      soundKey: string;
      position: WorldPosition;
      pitch: number;
      volume: number;
    }
  | {
      kind: "display";
      key: string;
      resource: {
        kind: "item" | "block";
        id: string;
      };
      position: WorldPosition;
      scale: Vec3;
      rotation: {
        x: number;
        y: number;
        z: number;
        w: number;
      };
      ttlTicks: number;
    }
  | {
      kind: "text";
      key: string;
      channel: "actionbar";
      text: string;
      ttlTicks: number;
    }
  | {
      kind: "text";
      key: string;
      channel: "floating";
      text: string;
      position: WorldPosition;
      ttlTicks: number;
    };

export interface WorldPosition {
  worldId: string;
  x: number;
  y: number;
  z: number;
}
export interface Vec3 {
  x: number;
  y: number;
  z: number;
}
