package dev.generative.equipment.core;
import dev.generative.equipment.protocol.Contracts;
import static dev.generative.equipment.core.RuntimeTypes.*;
public final class RuleEngine {
    public void onEvent(ActivationLease lease, Contracts.RuleEvent event) {
        throw new UnavailableFeatureException("Rule execution coordinator");
    }
}
