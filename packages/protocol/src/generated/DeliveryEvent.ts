/* Generated from contracts/schemas. Do not edit. */

export interface DeliveryEvent {
  eventId: string;
  deliveryId: string;
  hostId: string;
  taskId: string;
  sequenceNo: number;
  state: "NOT_STARTED" | "WAITING_PLAYER" | "PREPARED" | "APPLIED" | "ACKNOWLEDGED" | "RECOVERY_REQUIRED" | "REFUNDED";
  evidence: {
    [k: string]: unknown;
  };
}
