package com.iasr.core.support;

import com.iasr.core.actuator.Actuator;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple in-memory {@link Actuator} for tests: records every applied value so
 * assertions can check not just the final state but the whole trajectory.
 */
public class TestActuator implements Actuator {

    private final String name;
    private final int min;
    private final int max;
    private int value;
    private final List<Integer> applied = new ArrayList<>();

    public TestActuator(String name, int initial, int min, int max) {
        this.name = name;
        this.value = initial;
        this.min = min;
        this.max = max;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int currentValue() {
        return value;
    }

    @Override
    public void apply(int newValue) {
        applied.add(newValue);
        this.value = newValue;
    }

    @Override
    public int minValue() {
        return min;
    }

    @Override
    public int maxValue() {
        return max;
    }

    /** Every value passed to {@link #apply(int)}, in order. */
    public List<Integer> appliedValues() {
        return List.copyOf(applied);
    }

    /** Set the value directly, without recording it as an applied action. */
    public void setSilently(int newValue) {
        this.value = newValue;
    }
}
