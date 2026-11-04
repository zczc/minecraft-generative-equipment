// Generated from contracts/schemas. Do not edit.
package dev.generative.equipment.protocol;
import com.fasterxml.jackson.databind.JsonNode;
public final class Contracts {
    private Contracts() {}
    public record DefinitionRef(String definitionId, Long version) {}
    public record WorldRef(String id, String version) {}
    public record WorldPosition(String worldId, Double x, Double y, Double z) {}
    public record Vec3(Double x, Double y, Double z) {}
    public record ApiError(int schemaVersion, String requestId, JsonNode error) {}
    public record GenerationContext(String dimension, JsonNode biome, JsonNode position, Long worldTimeTicks, JsonNode weather, JsonNode nearbyBlocks) {}
    public record CapabilityProfile(String profileId, String hostId, String hostBuildId, String protocolVersion, String engineBuildId, JsonNode events, JsonNode queries, JsonNode operations, JsonNode visuals, JsonNode limits) {}
    public record CueSpec(String category, String audience, Double maxDistance, Long durationTicks, JsonNode recipe, String renderer, JsonNode requiredReceiptStatus) {}
    public record PresentationSpec(JsonNode cues, JsonNode bindings) {}
    public record EquipmentSource(int formatVersion, String name, String description, String inputRelation, String baseItem, JsonNode activationSlots, JsonNode stateSchema, JsonNode initialState, JsonNode parameters, JsonNode subscriptions, JsonNode requires, JsonNode constraints, JsonNode presentation, JsonNode scenarios) {}
    public record GeneratedSource(JsonNode equipment, String behaviorSource) {}
    public record MaterialInput(String definitionId, Long count) {}
    public record SourceEquipment(String definitionId, Long version, String bundleHash, JsonNode state) {}
    public record GenerationRequest(int schemaVersion, String taskId, String hostId, String recipientPlayerId, JsonNode worldRuleRef, String capabilityProfileId, String intentText, JsonNode materials, JsonNode sourceEquipment, JsonNode generationContext) {}
    public record GenerationView(int schemaVersion, String taskId, String status, Long revision, String inputHash, JsonNode definitionRef, JsonNode error, String createdAt) {}
    public record CancelRequest(String requestId, String reason) {}
    public record DeliveryEvent(String eventId, String deliveryId, String hostId, String taskId, Long sequenceNo, String state, JsonNode evidence) {}
    public record Observation(String eventId, JsonNode definitionRef, String codeHash, String activationId, JsonNode activityId, String kind, JsonNode payload, String observedAt) {}
    public record PackageManifest(int packageFormatVersion, JsonNode definitionRef, String protocolVersion, String capabilityProfileId, String engineBuildId, JsonNode worldRuleRef, String sourceHash, JsonNode files, JsonNode subscriptions, JsonNode requiredCapabilities, JsonNode activationSlots, String origin) {}
    public record RuleEvent(String eventId, String kind, Long tick, String phase, JsonNode payload) {}
    public record OperationRequest(String key, String capability, JsonNode args, String activityKey) {}
    public record OperationReceipt(String receiptId, String requestKey, String capability, String status, String code, JsonNode data, Long observedTick) {}
    public record FeedbackSignal(String cueId, String activityKey, JsonNode data, JsonNode receiptIds) {}
    public record Decision(JsonNode nextState, JsonNode activities, JsonNode timers, JsonNode operations, JsonNode feedback) {}
    public record ActivitySnapshot(String activityId, String key, String phase, Long elapsedTicks, Long phaseElapsedTicks, JsonNode data) {}
    public record RenderFrame(String cueId, JsonNode activity, Long elapsedTicks, JsonNode progress, JsonNode anchors, JsonNode receipts, JsonNode data) {}
    public record VisualFrame(JsonNode primitives) {}
    public record WorldConfig(String id, String version, String name, String description, String materialsFile, String policyFile) {}
    public record MaterialDefinition(String itemId, Boolean enabled, String name, String gameFacts, JsonNode associations) {}
    public record MaterialCatalog(String version, JsonNode materials) {}
    public record GenerationPolicy(int intentLimit, Long maxIntentBytes, Long maxMaterialKinds, Long maxCountPerMaterial, boolean associationsAreSuggestions, Long repairLimit) {}
}
