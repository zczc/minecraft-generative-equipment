package dev.generative.equipment.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ContractsTest {
  @Test
  void intentMatchesSharedUnicodeFixtures() throws Exception {
    var fixtures =
        JsonContracts.MAPPER.readTree(
            Files.readString(
                Path.of(System.getProperty("ge.repo"), "contracts/fixtures/intent.json")));
    for (var fixture : fixtures) {
      var result = IntentPolicy.validate(fixture.get("text").asText());
      assertEquals(fixture.get("count").asInt(), result.count(), fixture.toString());
      assertEquals(fixture.get("accepted").asBoolean(), result.accepted(), fixture.toString());
    }
    assertEquals("INPUT_BYTES_EXCEEDED", IntentPolicy.validate("a".repeat(257)).reason());
  }

  @Test
  void sharedGenerationFixtureDeserializesIntoGeneratedDto() throws Exception {
    var json =
        JsonContracts.MAPPER.readTree(
            Files.readString(
                Path.of(
                    System.getProperty("ge.repo"), "contracts/fixtures/generation-request.json")));
    JsonContracts.validate("GenerationRequest", json);
    var dto = JsonContracts.MAPPER.treeToValue(json, Contracts.GenerationRequest.class);
    assertEquals("task-test", dto.taskId());
    assertEquals("借 echo 的力量", dto.intentText());
    assertEquals(2, dto.materials().get(0).get("count").asInt());
    ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("schemaVersion", 2);
    assertThrows(
        IllegalArgumentException.class, () -> JsonContracts.validate("GenerationRequest", json));
  }

  @Test
  void validatesDynamicStateWithoutAllowingUnknownEnvelopeFields() throws Exception {
    JsonContracts.validate(
        "Decision", JsonContracts.MAPPER.readTree("{\"nextState\":{\"novelAttribute\":[1,2,3]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JsonContracts.validate(
                "Decision", JsonContracts.MAPPER.readTree("{\"owner\":\"someone\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JsonContracts.validate(
                "Decision", JsonContracts.MAPPER.readTree("{\"nextState\":{\"__proto__\":{}}}")));
  }
}
