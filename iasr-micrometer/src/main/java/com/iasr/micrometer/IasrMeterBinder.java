package com.iasr.micrometer;

import com.iasr.core.actuator.ConcurrencyLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Registers IASR-specific meters in the Micrometer registry:
 * <ul>
 *   <li>{@code iasr.concurrency.inflight} — current in-flight requests</li>
 *   <li>{@code iasr.concurrency.limit} — current concurrency limit value</li>
 *   <li>{@code iasr.concurrency.queue} — threads waiting for a permit</li>
 *   <li>{@code iasr.concurrency.timeouts} — cumulative permit-acquire timeouts</li>
 *   <li>{@code iasr.http.errors} — cumulative HTTP error count (5xx or similar)</li>
 * </ul>
 *
 * The timeout and error counters are exposed as public fields so the
 * integration filter can increment them.
 */
@RequiredArgsConstructor
public class IasrMeterBinder implements MeterBinder {

    private final ConcurrencyLimiter limiter;

    @Getter
    private Counter timeoutCounter;
    @Getter
    private Counter errorCounter;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("iasr.concurrency.inflight", limiter, ConcurrencyLimiter::getInflight)
                .description("Number of currently in-flight requests")
                .register(registry);

        Gauge.builder("iasr.concurrency.limit", limiter, ConcurrencyLimiter::currentValue)
                .description("Current concurrency limit")
                .register(registry);

        Gauge.builder("iasr.concurrency.queue", limiter, ConcurrencyLimiter::getQueueLength)
                .description("Threads waiting for a concurrency permit")
                .register(registry);

        timeoutCounter = Counter.builder("iasr.concurrency.timeouts")
                .description("Cumulative count of concurrency permit acquire timeouts")
                .register(registry);

        errorCounter = Counter.builder("iasr.http.errors")
                .description("Cumulative count of HTTP errors (5xx)")
                .register(registry);
    }

    /** Increment the timeout counter (called by integration filter). */
    public void recordTimeout() {
        if (timeoutCounter != null) {
            timeoutCounter.increment();
        }
    }

    /** Increment the error counter (called by integration filter). */
    public void recordError() {
        if (errorCounter != null) {
            errorCounter.increment();
        }
    }

}
