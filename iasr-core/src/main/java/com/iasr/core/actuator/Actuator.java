package com.iasr.core.actuator;

/**
 * Abstraction over a single tuneable resource ("soft resource").
 * <p>
 * Each actuator controls exactly one scalar parameter (e.g. concurrency limit,
 * pool size).  The control engine reads the current value, lets the controller
 * compute the desired value and then applies it through {@link #apply(int)}.
 *
 * @see com.iasr.core.actuator.ConcurrencyLimiter
 * @see com.iasr.core.actuator.HikariPoolActuator
 */
public interface Actuator {

    /** Human-readable name used in logs and dataset records. */
    String name();

    /** Returns the current effective value of the managed parameter. */
    int currentValue();

    /**
     * Apply a new value.  Implementations must be <b>thread-safe</b> and
     * must not block for a long time.
     *
     * @param newValue the desired value (already clamped by guardrails)
     */
    void apply(int newValue);

    /** Returns the configured minimum allowed value. */
    int minValue();

    /** Returns the configured maximum allowed value. */
    int maxValue();
}
