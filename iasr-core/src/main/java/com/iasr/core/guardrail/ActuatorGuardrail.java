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
 * @param blockIncreaseOnSloViolation
 *        when {@code true}, increases are suppressed while latency p95 exceeds
 *        the SLO target.  This is the safe default for resources whose growth
 *        can itself cause the violation (e.g. request concurrency).  Set to
 *        {@code false} for resources where a shortage is a plausible
 *        <em>cause</em> of the violation and growing them is the remedy — the
 *        database connection pool being the canonical case: queued threads
 *        waiting for a connection show up as high request latency, and
 *        blocking the pool increase would deadlock the control loop into the
 *        very state it is trying to escape.
 */
public record ActuatorGuardrail(
        String actuatorName,
        int minValue,
        int maxValue,
        int maxStep,
        int cooldownTicks,
        boolean blockIncreaseOnSloViolation,
        int maxStepDown
) {
    /**
     * Largest reduction allowed in one tick.
     * <p>
     * Defaults to {@link #maxStep} when not set explicitly, but is worth
     * setting higher: the two directions are not symmetric risks. Growing a
     * resource too fast can cause an incident, so it is rate-limited on
     * purpose; shrinking it is the escape from one, and capping that at the
     * same rate makes the guardrail throttle the controller's own emergency
     * brake. A controller that decides to halve the limit should be allowed to.
     */
    public int effectiveMaxStepDown() {
        return maxStepDown > 0 ? maxStepDown : maxStep;
    }

    public ActuatorGuardrail {
        if (actuatorName == null || actuatorName.isBlank()) {
            throw new IllegalArgumentException("actuatorName must not be blank");
        }
        if (minValue < 0) throw new IllegalArgumentException("minValue must be >= 0");
        if (maxValue < minValue) throw new IllegalArgumentException("maxValue must be >= minValue");
        if (maxStep < 1) throw new IllegalArgumentException("maxStep must be >= 1");
        if (cooldownTicks < 0) throw new IllegalArgumentException("cooldownTicks must be >= 0");
        if (maxStepDown < 0) throw new IllegalArgumentException("maxStepDown must be >= 0");
    }

    /** Symmetric step limits, blocking increases during an SLO violation. */
    public ActuatorGuardrail(String actuatorName,
                             int minValue,
                             int maxValue,
                             int maxStep,
                             int cooldownTicks) {
        this(actuatorName, minValue, maxValue, maxStep, cooldownTicks, true, 0);
    }

    /** Symmetric step limits, with an explicit SLO-violation policy. */
    public ActuatorGuardrail(String actuatorName,
                             int minValue,
                             int maxValue,
                             int maxStep,
                             int cooldownTicks,
                             boolean blockIncreaseOnSloViolation) {
        this(actuatorName, minValue, maxValue, maxStep, cooldownTicks,
                blockIncreaseOnSloViolation, 0);
    }
}
