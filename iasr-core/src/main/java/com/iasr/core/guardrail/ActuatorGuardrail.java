package com.iasr.core.guardrail;

/**
 * Per-actuator safety constraints that are enforced <b>on top of</b> whatever
 * the controller decides.
 * <p>
 * Guardrails are immutable configuration objects.  They are created once at
 * startup and passed to the {@link Guardrails} enforcer.
 *
 * @param actuatorName  matches {@link com.iasr.core.actuator.Actuator#name()}
 * @param minValue      absolute floor for the parameter
 * @param maxValue      absolute ceiling for the parameter
 * @param maxStep       maximum allowed change per single tick (always positive)
 * @param cooldownTicks minimum number of ticks between successive changes
 */
public record ActuatorGuardrail(
        String actuatorName,
        int minValue,
        int maxValue,
        int maxStep,
        int cooldownTicks
) {
    public ActuatorGuardrail {
        if (minValue < 0) throw new IllegalArgumentException("minValue must be >= 0");
        if (maxValue < minValue) throw new IllegalArgumentException("maxValue must be >= minValue");
        if (maxStep < 1) throw new IllegalArgumentException("maxStep must be >= 1");
        if (cooldownTicks < 0) throw new IllegalArgumentException("cooldownTicks must be >= 0");
    }
}
