package com.iasr.spring;

import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.metrics.WindowedLatencyTracker;
import com.iasr.micrometer.IasrMeterBinder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Servlet filter that enforces the IASR concurrency limit on every incoming
 * HTTP request and records per-request latency for the control loop.
 * <p>
 * If a permit cannot be acquired within the configured timeout the filter
 * responds with <b>429 Too Many Requests</b> and increments the timeout counter.
 * <p>
 * Request duration is recorded in {@link WindowedLatencyTracker} only for
 * "business" endpoints.  Infrastructure paths (actuator, health, prometheus)
 * are excluded from latency tracking to prevent health-check traffic from
 * polluting the control loop's latency signal.
 */
@Slf4j
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/actuator", "/health", "/ready", "/live", "/prometheus"
    );

    private final ConcurrencyLimiter limiter;
    private final long acquireTimeoutMs;
    private final IasrMeterBinder meterBinder;
    private final WindowedLatencyTracker latencyTracker;
    private final Set<String> excludedPrefixes;

    public ConcurrencyLimitFilter(ConcurrencyLimiter limiter,
                                  long acquireTimeoutMs,
                                  IasrMeterBinder meterBinder,
                                  WindowedLatencyTracker latencyTracker) {
        this(limiter, acquireTimeoutMs, meterBinder, latencyTracker, EXCLUDED_PREFIXES);
    }

    public ConcurrencyLimitFilter(ConcurrencyLimiter limiter,
                                  long acquireTimeoutMs,
                                  IasrMeterBinder meterBinder,
                                  WindowedLatencyTracker latencyTracker,
                                  Set<String> excludedPrefixes) {
        this.limiter = limiter;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.meterBinder = meterBinder;
        this.latencyTracker = latencyTracker;
        this.excludedPrefixes = excludedPrefixes != null ? excludedPrefixes : EXCLUDED_PREFIXES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        boolean acquired = false;
        try {
            acquired = limiter.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"error\":\"Service interrupted\"}");
            return;
        }

        if (!acquired) {
            if (meterBinder != null) {
                meterBinder.recordTimeout();
            }
            log.warn("Concurrency limit reached (limit={}, inflight={}, queue={}), rejecting request: {} {}",
                    limiter.currentValue(), limiter.getInflight(), limiter.getQueueLength(),
                    request.getMethod(), request.getRequestURI());

            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\",\"retryAfterMs\":" + acquireTimeoutMs + "}");
            return;
        }

        boolean trackLatency = shouldTrackLatency(request);
        long startNs = trackLatency ? System.nanoTime() : 0;
        try {
            chain.doFilter(request, response);

            if (response.getStatus() >= 500 && meterBinder != null) {
                meterBinder.recordError();
            }
        } catch (Exception e) {
            if (meterBinder != null) {
                meterBinder.recordError();
            }
            if (e instanceof ServletException se) throw se;
            if (e instanceof IOException ioe) throw ioe;
            throw new ServletException(e);
        } finally {
            limiter.release();
            if (trackLatency && latencyTracker != null) {
                double durationMs = (System.nanoTime() - startNs) / 1_000_000.0;
                latencyTracker.record(durationMs);
            }
        }
    }

    private boolean shouldTrackLatency(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String prefix : excludedPrefixes) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
