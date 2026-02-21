package com.iasr.core.actuator;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Semaphore-based concurrency limiter with runtime-adjustable permit count.
 * <p>
 * This actuator controls the maximum number of requests (or tasks) that may
 * be processed concurrently.  It exposes {@link #tryAcquire(long, TimeUnit)}
 * and {@link #release()} for integration layers (servlet filters, interceptors,
 * middleware, etc.).
 *
 * <h3>Resize mechanism</h3>
 * Uses a {@link ResizableSemaphore} subclass that exposes the JDK's
 * {@code reducePermits(int)} — a single O(1) CAS operation that adjusts
 * the internal permit counter directly (and may drive it negative, creating
 * a "permit debt" that is repaid naturally by subsequent {@link #release()}
 * calls).  This eliminates the need for an external {@code issuedPermits}
 * counter or drain loops.
 *
 * <h3>Thread safety</h3>
 * All public methods are thread-safe.  The semaphore is fair (FIFO).
 */
@Slf4j
public class ConcurrencyLimiter implements Actuator {

    public static final String ACTUATOR_NAME = "concurrency_limit";

    private final int minLimit;
    private final int maxLimit;

    /** Target concurrency limit set by the controller. */
    private final AtomicInteger currentLimit;

    /** Fair semaphore with exposed {@code reducePermits}. */
    private final ResizableSemaphore semaphore;

    /** Counter of currently acquired permits (in-flight requests). */
    private final AtomicInteger inflight = new AtomicInteger(0);

    /**
     * @param initialLimit starting concurrency limit
     * @param minLimit     hard floor (guardrail)
     * @param maxLimit     hard ceiling (guardrail)
     */
    public ConcurrencyLimiter(int initialLimit, int minLimit, int maxLimit) {
        if (minLimit < 1) throw new IllegalArgumentException("minLimit must be >= 1");
        if (maxLimit < minLimit) throw new IllegalArgumentException("maxLimit must be >= minLimit");

        int clamped = Math.max(minLimit, Math.min(initialLimit, maxLimit));
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.currentLimit = new AtomicInteger(clamped);
        this.semaphore = new ResizableSemaphore(clamped);
    }

    // ── Actuator contract ────────────────────────────────────────────────

    @Override
    public String name() {
        return ACTUATOR_NAME;
    }

    @Override
    public int currentValue() {
        return currentLimit.get();
    }

    /**
     * Adjust the concurrency limit at runtime.
     * <p>
     * <b>Increase:</b> permits are released into the semaphore immediately;
     * blocked waiters (if any) are woken in FIFO order.
     * <p>
     * <b>Decrease:</b> {@code reducePermits()} atomically lowers the
     * internal counter in O(1).  If more permits are currently held than
     * the new limit, {@code availablePermits()} goes negative — the
     * semaphore naturally blocks new acquires until enough in-flight
     * requests complete and the debt is repaid via {@link #release()}.
     */
    @Override
    public void apply(int newValue) {
        int clamped = Math.max(minLimit, Math.min(newValue, maxLimit));
        int prev = currentLimit.getAndSet(clamped);
        int delta = clamped - prev;
        if (delta > 0) {
            semaphore.release(delta);
            log.info("ConcurrencyLimiter: {} → {} (+{})", prev, clamped, delta);
        } else if (delta < 0) {
            semaphore.reducePermits(-delta);
            log.info("ConcurrencyLimiter: {} → {} (−{}, available={})",
                    prev, clamped, -delta, semaphore.availablePermits());
        }
    }

    @Override
    public int minValue() {
        return minLimit;
    }

    @Override
    public int maxValue() {
        return maxLimit;
    }

    // ── Public API for integration layers ────────────────────────────────

    /**
     * Try to acquire a permit within the given timeout.
     *
     * @return {@code true} if a permit was acquired (caller MUST call {@link #release()})
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        boolean acquired = semaphore.tryAcquire(timeout, unit);
        if (acquired) {
            inflight.incrementAndGet();
        }
        return acquired;
    }

    /**
     * Release a previously acquired permit.
     * <p>
     * If permits are in debt (after a limit decrease), this release
     * repays part of the debt rather than making a permit available.
     */
    public void release() {
        int prev = inflight.decrementAndGet();
        if (prev < 0) {
            inflight.incrementAndGet();
            log.warn("release() called without matching acquire() — ignoring. " +
                    "Check integration code for asymmetric acquire/release.");
            return;
        }
        semaphore.release();
    }

    /** Returns the number of currently acquired permits (in-flight requests). */
    public int getInflight() {
        return inflight.get();
    }

    /** Returns the number of threads waiting to acquire a permit. */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    // ── Resizable semaphore ──────────────────────────────────────────────

    /**
     * Thin subclass that exposes the JDK's protected
     * {@link Semaphore#reducePermits(int)} as public.
     */
    private static final class ResizableSemaphore extends Semaphore {

        ResizableSemaphore(int permits) {
            super(permits, true);
        }

        @Override
        public void reducePermits(int reduction) {
            super.reducePermits(reduction);
        }
    }
}
