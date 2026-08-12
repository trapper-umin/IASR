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
 * Infrastructure paths (actuator, health, prometheus, liveness/readiness) are
 * skipped entirely: they neither consume a permit nor contribute to the
 * latency signal.  Both halves matter.  Excluding them from latency tracking
 * keeps cheap health-check traffic from diluting the control loop's view of
 * real request latency, and exempting them from the limit keeps the
 * orchestrator's probes answerable while the application is saturated — a
 * 429 on {@code /health} would get the instance killed exactly when IASR is
 * shedding load to save it.
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

        long startNs = System.nanoTime();
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
            if (latencyTracker != null) {
                double durationMs = (System.nanoTime() - startNs) / 1_000_000.0;
                latencyTracker.record(durationMs);
            }
        }
    }

    /**
     * Infrastructure endpoints bypass the filter completely — no permit, no
     * latency sample.  Returning {@code true} here is what keeps probes
     * answerable under saturation.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isInfrastructurePath(pathWithinApplication(request));
    }

    /**
     * The request path relative to the application, with the servlet context
     * path removed.
     * <p>
     * Matching must not be done against {@code getRequestURI()} directly: it
     * carries the context path, so an application deployed under
     * {@code server.servlet.context-path=/myapp} would see
     * {@code /myapp/actuator/health} and silently fail every prefix check.
     * The exclusions would stop applying exactly where they matter most —
     * health probes would start consuming permits and could be answered with
     * 429 while the application is saturated.
     * <p>
     * Visible for testing.
     */
    static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty() || !uri.startsWith(contextPath)) {
            return uri;
        }
        String withinApp = uri.substring(contextPath.length());
        return withinApp.isEmpty() ? "/" : withinApp;
    }

    /** Visible for testing. */
    boolean isInfrastructurePath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : excludedPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
