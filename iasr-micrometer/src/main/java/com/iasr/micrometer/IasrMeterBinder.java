package com.iasr.micrometer;

import com.iasr.core.actuator.ConcurrencyLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 *   <li>{@code iasr.http.requests} — completed business responses, including rejections</li>
 *   <li>{@code iasr.http.successes} — successful business responses (2xx)</li>
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
    @Getter
    private volatile Counter requestCounter;
    @Getter
    private Counter successCounter;
    @Getter
    private Timer admissionWaitTimer;
    private MeterRegistry registry;
    private boolean responseMetricsEnabled;

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
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

        admissionWaitTimer = Timer.builder("iasr.concurrency.wait")
                .description("Time a served request spent waiting for a concurrency permit")
                .register(registry);
        if (responseMetricsEnabled) {
            enableResponseMetrics();
        }
    }

    /** Increment the timeout counter (called by integration filter). */
    public void recordTimeout() {
        if (timeoutCounter != null) {
            timeoutCounter.increment();
        }
    }

    /**
     * Activate the HTTP counters only when response instrumentation is present.
     * Registering unused counters in a non-servlet application would otherwise
     * hide its working HTTP timers behind a permanently zero business count.
     */
    public synchronized void enableResponseMetrics() {
        responseMetricsEnabled = true;
        if (registry != null && requestCounter == null) {
            successCounter = Counter.builder("iasr.http.successes")
                    .description("Successful business responses (HTTP 2xx)")
                    .register(registry);
            requestCounter = Counter.builder("iasr.http.requests")
                    .description("Completed business requests, including admission rejections and errors")
                    .register(registry);
        }
    }

    /** Record exactly one completed business response; infrastructure requests bypass this. */
    public void recordResponse(int status) {
        if (requestCounter == null) {
            enableResponseMetrics();
        }
        if (requestCounter != null) {
            requestCounter.increment();
        }
        if (status >= 200 && status < 300 && successCounter != null) {
            successCounter.increment();
        }
        if (status >= 500) {
            recordError();
        }
    }

    /**
     * Record how long a request waited for its permit.
     * <p>
     * Only for requests that got one: a rejected request's wait is the timeout
     * by construction and would say nothing about how contended the limit is.
     */
    public void recordAdmissionWait(long nanos) {
        if (admissionWaitTimer != null) {
            admissionWaitTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** Increment the error counter (called by integration filter). */
    public void recordError() {
        if (errorCounter != null) {
            errorCounter.increment();
        }
    }

}
