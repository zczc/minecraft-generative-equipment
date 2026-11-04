package dev.generative.equipment.core;
import org.junit.jupiter.api.Test;
import dev.generative.equipment.protocol.Contracts;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ActivationRegistryTest {
    @Test void revokesAllLeasesEvenWhenCleanupFails() {
        var registry = new ActivationRegistry(); var player = UUID.randomUUID();
        for (int i=0;i<2;i++) registry.register(new RuntimeTypes.ActivationLease(UUID.randomUUID(),UUID.randomUUID(),player,"main_hand",i,new Contracts.DefinitionRef("example",1L)));
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> registry.stopPlayer(player, lease -> { calls.incrementAndGet(); throw new IllegalStateException("fixture failure"); }));
        assertEquals(2,calls.get()); assertEquals(0,registry.size());
    }
}
