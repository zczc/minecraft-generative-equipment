package dev.generative.equipment.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HostCapabilitiesTest {
  @Test
  void budgetUpgradeAcceptsOnlyTheSameHostsKnownLegacyProfile() {
    var current = HostCapabilities.profile("local-paper", "test-engine");
    assertEquals("paper-alpha2-local-paper", current.path("profileId").asText());
    assertTrue(HostCapabilities.acceptsProfile(current, "paper-alpha2-local-paper"));
    assertTrue(HostCapabilities.acceptsProfile(current, "paper-alpha1-local-paper"));
    assertFalse(HostCapabilities.acceptsProfile(current, "paper-alpha1-other-host"));
    assertFalse(HostCapabilities.acceptsProfile(current, "paper-alpha0-local-paper"));
    assertFalse(HostCapabilities.acceptsProfile(current, "paper-alpha3-local-paper"));
    assertTrue(
        current.path("limits").path("tickTargetMs").asLong()
            < current.path("limits").path("handlerBudgetMs").asLong());
    assertTrue(
        current.path("limits").path("handlerBudgetMs").asLong()
            <= current.path("limits").path("tickBudgetMs").asLong());
  }
}
