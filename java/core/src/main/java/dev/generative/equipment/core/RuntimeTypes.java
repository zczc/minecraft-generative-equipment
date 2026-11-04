package dev.generative.equipment.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.protocol.Contracts;
import java.util.UUID;

public final class RuntimeTypes {
  private RuntimeTypes() {}

  public record ActivationLease(
      UUID worldSessionId,
      UUID activationId,
      UUID playerId,
      String slot,
      long slotRevision,
      Contracts.DefinitionRef definition) {}

  public record ExecutionBudget(
      long memoryBytes, long deadlineNanos, int maxOperations, int maxQueries) {}

  public record PreparedDefinition(
      Contracts.DefinitionRef ref, String sourceHash, String engineBuildId, byte[] bytecode) {
    public PreparedDefinition {
      bytecode = bytecode.clone();
    }

    @Override
    public byte[] bytecode() {
      return bytecode.clone();
    }
  }

  public record VerifiedPackage(
      Contracts.DefinitionRef ref,
      String sourceHash,
      String engineBuildId,
      JsonNode manifest,
      String source) {}

  public record Invocation(
      ActivationLease lease,
      Contracts.RuleEvent event,
      JsonNode state,
      JsonNode parameters,
      JsonNode activities,
      JsonNode self) {
    public Invocation(
        ActivationLease lease,
        Contracts.RuleEvent event,
        JsonNode state,
        JsonNode parameters,
        JsonNode activities) {
      this(
          lease,
          event,
          state,
          parameters,
          activities,
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
    }
  }

  public record RenderInvocation(
      ActivationLease lease, JsonNode state, JsonNode parameters, JsonNode frame, long tick) {}

  public record ItemSnapshot(long revision, JsonNode state) {}

  public record QueryResult(boolean ok, JsonNode data, String code) {}

  public record WriteResult(boolean written, long revision, String code) {}

  public sealed interface InvocationResult permits InvocationSuccess, InvocationFailure {}

  public record InvocationSuccess(Contracts.Decision decision) implements InvocationResult {}

  public record InvocationFailure(String code, String message) implements InvocationResult {}

  public record RenderResult(boolean ok, JsonNode frame, String code) {}
}
