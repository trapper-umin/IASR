package com.iasr.spring;

import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.micrometer.IasrMeterBinder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Servlet filter that enforces the IASR concurrency limit on every incoming
 * HTTP request.
 * <p>
 * If a permit cannot be acquired within the configured timeout the filter
 * responds with <b>429 Too Many Requests</b> (or 503 Service Unavailable
 * depending on preference) and increments the timeout counter.
 * <p>
 * If the downstream processing results in a 5xx status code, the error
 * counter is incremented.
 */
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimitFilter.class);

    private final ConcurrencyLimiter limiter;
    private final long acquireTimeoutMs;
    private final IasrMeterBinder meterBinder;

    public ConcurrencyLimitFilter(ConcurrencyLimiter limiter,
                                  long acquireTimeoutMs,
                                  IasrMeterBinder meterBinder) {
        this.limiter = limiter;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.meterBinder = meterBinder;
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
            // Permit not obtained within timeout → reject
            if (meterBinder != null) {
                meterBinder.recordTimeout();
            }
            log.warn("Concurrency limit reached (limit={}, inflight={}, queue={}), rejecting request: {} {}",
                    limiter.currentValue(), limiter.getInflight(), limiter.getQueueLength(),
                    request.getMethod(), request.getRequestURI());

            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\",\"retryAfterMs\":" + acquireTimeoutMs + "}");
            return;
        }

        try {
            chain.doFilter(request, response);

            // Track server errors
            if (response.getStatus() >= 500 && meterBinder != null) {
                meterBinder.recordError();
            }
        } finally {
            limiter.release();
        }
    }
}
