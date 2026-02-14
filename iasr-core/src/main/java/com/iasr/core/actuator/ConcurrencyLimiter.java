package com.iasr.core.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>
 * When the control engine changes the limit via {@link #apply(int)}, the
 * semaphore's permit count is adjusted atomically without recreating the
 * semaphore.  The implementation uses a fair semaphore so that waiters are
 * served in FIFO order.
 *
 * <h3>Thread safety</h3>
 * All public methods are thread-safe.
 */
public class ConcurrencyLimiter implements Actuator {

    public static final String ACTUATOR_NAME = "concurrency_limit";

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimiter.class);

    private final int minLimit;
    private final int maxLimit;

    /**
     * Current logical limit.  We track it separately because Semaphore does
     * not expose its "initial permits" and {@code availablePermits()} fluctuates.
     */
    private final AtomicInteger currentLimit;

    /** Fair semaphore — guarantees FIFO ordering for waiters. */
    private final Semaphore semaphore;

    /** Counter of currently acquired permits (inflight requests). */
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
        this.semaphore = new Semaphore(clamped, true);
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
     * If the new limit is higher, extra permits are released immediately.
     * If it is lower, permits are <b>not</b> forcibly revoked — the
     * effective reduction happens naturally as in-flight requests complete
     * and the excess permits are simply not re-issued.
     */
    @Override
    public void apply(int newValue) {
        int clamped = Math.max(minLimit, Math.min(newValue, maxLimit));
        int prev = currentLimit.getAndSet(clamped);
        int delta = clamped - prev;
        if (delta > 0) {
            semaphore.release(delta);
            log.info("ConcurrencyLimiter: increased {} → {} (+{})", prev, clamped, delta);
        } else if (delta < 0) {
            // Reduce permits eagerly where possible, but never block.
            int reduced = 0;
            for (int i = 0; i < -delta; i++) {
                if (semaphore.tryAcquire()) {
                    reduced++;
                } else {
                    break; // remaining reduction will happen as requests complete
                }
            }
            log.info("ConcurrencyLimiter: decreased {} → {} (eagerly reclaimed {} permits)",
                    prev, clamped, reduced);
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
     * If the current limit has been lowered below the old limit, excess
     * permits are silently absorbed (the semaphore may temporarily have
     * fewer available permits than the logical limit until convergence).
     */
    public void release() {
        inflight.decrementAndGet();
        // Only release back to semaphore if we haven't overshot the current limit.
        // This naturally "drains" excess permits when the limit was reduced.
        if (semaphore.availablePermits() < currentLimit.get()) {
            semaphore.release();
        }
    }

    /** Returns the number of currently acquired permits (in-flight requests). */
    public int getInflight() {
        return inflight.get();
    }

    /** Returns the number of threads waiting to acquire a permit. */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }
}
