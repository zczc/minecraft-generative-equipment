/* Generated from contracts/schemas. Do not edit. */

export interface MaterialCatalog {
  version: string;
  /**
   * @maxItems 128
   */
  materials: MaterialDefinition[];
}
export interface MaterialDefinition {
  itemId: string;
  enabled: boolean;
  name: string;
  gameFacts: string;
  /**
   * @maxItems 12
   */
  associations:
    | []
    | [string]
    | [string, string]
    | [string, string, string]
    | [string, string, string, string]
    | [string, string, string, string, string]
    | [string, string, string, string, string, string]
    | [string, string, string, string, string, string, string]
    | [string, string, string, string, string, string, string, string]
    | [string, string, string, string, string, string, string, string, string]
    | [string, string, string, string, string, string, string, string, string, string]
    | [string, string, string, string, string, string, string, string, string, string, string]
    | [string, string, string, string, string, string, string, string, string, string, string, string];
}
