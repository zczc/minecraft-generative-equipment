package dev.generative.equipment.core;
public final class UnavailableFeatureException extends RuntimeException {
    public UnavailableFeatureException(String component) { super("NOT_IMPLEMENTED: " + component + " (S0)"); }
    public String code() { return "NOT_IMPLEMENTED"; }
}
