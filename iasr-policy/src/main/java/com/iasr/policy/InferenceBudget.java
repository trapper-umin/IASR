package com.iasr.policy;

import java.util.function.LongSupplier;

/** Cooperative deadline: every bounded scoring loop checks it; no executor or orphan inference thread. */
public final class InferenceBudget {
    private final long started;
    private final long allowance;
    private final LongSupplier clock;

    public InferenceBudget(long allowanceNanos) { this(allowanceNanos, System::nanoTime); }

    public InferenceBudget(long allowanceNanos, LongSupplier clock) {
        if (allowanceNanos <= 0) throw new IllegalArgumentException("Inference budget must be positive");
        this.clock = clock;
        this.allowance = allowanceNanos;
        this.started = clock.getAsLong();
    }

    public void check() {
        if (Thread.currentThread().isInterrupted() || clock.getAsLong() - started >= allowance) {
            throw new Exceeded();
        }
    }

    public static final class Exceeded extends RuntimeException {
        public Exceeded() { super("Inference deadline exceeded"); }
    }
}
