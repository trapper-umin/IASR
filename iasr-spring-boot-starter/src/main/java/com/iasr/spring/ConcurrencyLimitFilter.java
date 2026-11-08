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
import java.util.function.LongSupplier;

/**
 * Servlet filter that enforces the IASR concurrency limit on every incoming
 * HTTP request and records successful-request latency, including admission wait.
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

    /** Minimum gap between aggregated rejection WARN lines. */
    private static final long REJECTION_SUMMARY_INTERVAL_MS = 5_000;

    private final java.util.concurrent.atomic.AtomicLong rejectionsSinceSummary =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastSummaryNs =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private final ConcurrencyLimiter limiter;
    private final long acquireTimeoutMs;
    private final IasrMeterBinder meterBinder;
    private final WindowedLatencyTracker latencyTracker;
    private final Set<String> excludedPrefixes;
    private final LongSupplier nanoTime;

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
        this(limiter, acquireTimeoutMs, meterBinder, latencyTracker, excludedPrefixes, System::nanoTime);
    }

    ConcurrencyLimitFilter(ConcurrencyLimiter limiter,
                           long acquireTimeoutMs,
                           IasrMeterBinder meterBinder,
                           WindowedLatencyTracker latencyTracker,
                           Set<String> excludedPrefixes,
                           LongSupplier nanoTime) {
        this.limiter = limiter;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.meterBinder = meterBinder;
        this.latencyTracker = latencyTracker;
        this.excludedPrefixes = excludedPrefixes != null ? excludedPrefixes : EXCLUDED_PREFIXES;
        this.nanoTime = nanoTime;
        if (meterBinder != null) {
            meterBinder.enableResponseMetrics();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        boolean acquired = false;
        long admissionStartNs = nanoTime.getAsLong();
        try {
            acquired = limiter.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            if (meterBinder != null) {
                meterBinder.recordResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
            response.getWriter().write("{\"error\":\"Service interrupted\"}");
            return;
        }

        if (!acquired) {
            if (meterBinder != null) {
                meterBinder.recordTimeout();
                meterBinder.recordResponse(429);
            }
            logRejection(request);

            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\",\"retryAfterMs\":" + acquireTimeoutMs + "}");
            return;
        }

        long startNs = nanoTime.getAsLong();
        if (meterBinder != null) {
            // Includes all admitted requests, even those that later fail.
            // The successful-request tracker separately pairs wait and service.
            meterBinder.recordAdmissionWait(startNs - admissionStartNs);
        }
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } catch (Exception e) {
            if (e instanceof ServletException se) throw se;
            if (e instanceof IOException ioe) throw ioe;
            throw new ServletException(e);
        } finally {
            limiter.release();
            int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            if (meterBinder != null) {
                meterBinder.recordResponse(status);
            }
            if (latencyTracker != null && status >= 200 && status < 300) {
                double durationMs = (nanoTime.getAsLong() - startNs) / 1_000_000.0;
                double waitMs = (startNs - admissionStartNs) / 1_000_000.0;
                latencyTracker.record(durationMs, waitMs);
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
     * Report a rejection without letting the log become part of the outage.
     * <p>
     * Rejections arrive in bursts by definition — shedding 300 rps would emit
     * 300 log lines per second, and that I/O lands on an application that is
     * already saturated, amplifying the very overload the limiter exists to
     * contain.  So the per-request detail goes to DEBUG, and WARN carries at
     * most one aggregated line per {@value #REJECTION_SUMMARY_INTERVAL_MS} ms.
     * The authoritative signal is the {@code iasr.concurrency.timeouts} counter,
     * which loses nothing.
     */
    private void logRejection(HttpServletRequest request) {
        rejectionsSinceSummary.incrementAndGet();

        if (log.isDebugEnabled()) {
            log.debug("Concurrency limit reached (limit={}, inflight={}, queue={}), rejecting {} {}",
                    limiter.currentValue(), limiter.getInflight(), limiter.getQueueLength(),
                    request.getMethod(), request.getRequestURI());
        }

        long now = System.nanoTime();
        long last = lastSummaryNs.get();
        if (now - last < REJECTION_SUMMARY_INTERVAL_MS * 1_000_000L) {
            return;
        }
        if (!lastSummaryNs.compareAndSet(last, now)) {
            // Another thread is emitting the summary for this interval.
            return;
        }
        long rejected = rejectionsSinceSummary.getAndSet(0);
        if (rejected > 0) {
            log.warn("Concurrency limit shedding load: {} request(s) rejected in the last {} ms "
                            + "(limit={}, inflight={}, queue={})",
                    rejected, REJECTION_SUMMARY_INTERVAL_MS,
                    limiter.currentValue(), limiter.getInflight(), limiter.getQueueLength());
        }
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
