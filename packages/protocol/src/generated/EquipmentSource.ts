/* Generated from contracts/schemas. Do not edit. */

export interface EquipmentSource {
  formatVersion: 1;
  name: string;
  description: string;
  inputRelation?: string;
  baseItem: string;
  /**
   * @maxItems 6
   */
  activationSlots:
    | []
    | ["main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet"]
    | [
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet"
      ]
    | [
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet"
      ]
    | [
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet"
      ]
    | [
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet"
      ]
    | [
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet",
        "main_hand" | "off_hand" | "head" | "chest" | "legs" | "feet"
      ];
  stateSchema: {
    [k: string]: unknown;
  };
  initialState: {
    [k: string]: unknown;
  };
  parameters: {
    [k: string]: unknown;
  };
  /**
   * @maxItems 128
   */
  subscriptions: string[];
  requires: {
    /**
     * @maxItems 128
     */
    queries: string[];
    /**
     * @maxItems 128
     */
    operations: string[];
  };
  /**
   * @maxItems 32
   */
  constraints?: {
    id: string;
    text: string;
    handler: "onEvent";
    /**
     * @maxItems 128
     */
    scenarioIds: string[];
  }[];
  presentation?: PresentationSpec;
  /**
   * @maxItems 16
   */
  scenarios?:
    | []
    | [
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ]
    | [
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        },
        {
          [k: string]: unknown;
        }
      ];
}
export interface PresentationSpec {
  cues: {
    [k: string]: CueSpec;
  };
  /**
   * @maxItems 128
   */
  bindings: {
    cueId: string;
    trigger: "activity.phase" | "operation.receipt" | "feedback";
    match: {
      [k: string]: unknown;
    };
  }[];
}
export interface CueSpec {
  category: "preview" | "ongoing" | "result" | "failure";
  audience: "actor" | "nearby";
  maxDistance?: number;
  durationTicks: number;
  recipe?: {
    [k: string]: unknown;
  };
  renderer?: "render";
  /**
   * @maxItems 4
   */
  requiredReceiptStatus?:
    | []
    | ["PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"]
    | ["PROPOSED" | "APPLIED" | "REJECTED" | "ERROR", "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"]
    | [
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"
      ]
    | [
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR",
        "PROPOSED" | "APPLIED" | "REJECTED" | "ERROR"
      ];
}
