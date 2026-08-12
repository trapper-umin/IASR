package com.iasr.spring;

import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.metrics.WindowedLatencyTracker;
import com.iasr.micrometer.IasrMeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyLimitFilterTest {

    private ConcurrencyLimiter limiter;
    private WindowedLatencyTracker tracker;
    private SimpleMeterRegistry registry;
    private IasrMeterBinder binder;
    private ConcurrencyLimitFilter filter;

    @BeforeEach
    void setUp() {
        limiter = new ConcurrencyLimiter(1, 1, 10);
        tracker = new WindowedLatencyTracker();
        registry = new SimpleMeterRegistry();
        binder = new IasrMeterBinder(limiter);
        binder.bindTo(registry);
        filter = new ConcurrencyLimitFilter(limiter, 20, binder, tracker);
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    /** Take the only permit, so the next request has to wait and time out. */
    private void exhaustPermits() throws InterruptedException {
        assertThat(limiter.tryAcquire(0, TimeUnit.MILLISECONDS)).isTrue();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator", "/actuator/health", "/health", "/ready", "/live", "/prometheus"
    })
    @DisplayName("infrastructure paths bypass the filter entirely")
    void infrastructurePathsAreSkipped(String uri) throws Exception {
        exhaustPermits();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get(uri), response, chain);

        assertThat(chain.getRequest())
                .as("the probe must reach the application even with no permits left")
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(limiter.getInflight())
                .as("a probe must not consume a permit")
                .isEqualTo(1);
        assertThat(tracker.drainAndCompute().hasData())
                .as("probe latency must not pollute the control signal")
                .isFalse();
    }

    @Test
    @DisplayName("business requests pass through and record their latency")
    void businessRequestIsTrackedAndReleased() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(limiter.getInflight()).as("the permit is returned").isZero();
        assertThat(tracker.drainAndCompute().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects with 429 and counts a timeout when no permit is available")
    void rejectsWhenSaturated() throws Exception {
        exhaustPermits();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/orders"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("Too many requests");
        assertThat(chain.getRequest()).as("the request never reached the application").isNull();
        assertThat(counter("iasr.concurrency.timeouts")).isEqualTo(1.0);
        assertThat(tracker.drainAndCompute().hasData())
                .as("a rejected request has no service latency to report")
                .isFalse();
    }

    @Test
    @DisplayName("counts a 5xx response as an error")
    void countsServerErrors() throws Exception {
        FilterChain failing = (rq, rs) -> ((HttpServletResponse) rs).setStatus(500);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(get("/api/orders"), response, failing);

        assertThat(counter("iasr.http.errors")).isEqualTo(1.0);
        assertThat(limiter.getInflight()).isZero();
    }

    @Test
    @DisplayName("does not count a 4xx response as a server error")
    void ignoresClientErrors() throws Exception {
        FilterChain notFound = (rq, rs) -> ((HttpServletResponse) rs).setStatus(404);

        filter.doFilter(get("/api/orders"), new MockHttpServletResponse(), notFound);

        assertThat(counter("iasr.http.errors")).isZero();
    }

    @Test
    @DisplayName("releases the permit and counts an error when the chain throws")
    void releasesPermitOnException() {
        FilterChain throwing = (rq, rs) -> {
            throw new IllegalStateException("downstream blew up");
        };

        try {
            filter.doFilter(get("/api/orders"), new MockHttpServletResponse(), throwing);
        } catch (Exception expected) {
            // rethrown as ServletException
        }

        assertThat(limiter.getInflight())
                .as("a failed request must not leak its permit")
                .isZero();
        assertThat(counter("iasr.http.errors")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("honours a custom exclusion list")
    void customExclusionsAreRespected() throws Exception {
        ConcurrencyLimitFilter custom = new ConcurrencyLimitFilter(
                limiter, 20, binder, tracker, java.util.Set.of("/internal"));

        assertThat(custom.isInfrastructurePath("/internal/metrics")).isTrue();
        assertThat(custom.isInfrastructurePath("/actuator/health"))
                .as("the custom list replaces the default one")
                .isFalse();

        exhaustPermits();
        MockFilterChain chain = new MockFilterChain();
        custom.doFilter(get("/internal/metrics"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("treats a missing URI as a business path rather than failing")
    void nullUriIsNotInfrastructure() {
        assertThat(filter.isInfrastructurePath(null)).isFalse();
    }

    @Test
    @DisplayName("recognises infrastructure paths under a servlet context path")
    void exclusionsSurviveAContextPath() throws Exception {
        exhaustPermits();
        MockHttpServletRequest request = get("/myapp/actuator/health");
        request.setContextPath("/myapp");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest())
                .as("a probe must stay exempt when the app is deployed under a context path")
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(limiter.getInflight()).isEqualTo(1);
    }

    @Test
    @DisplayName("business paths under a context path are still limited")
    void businessPathsUnderContextPathAreStillLimited() throws Exception {
        exhaustPermits();
        MockHttpServletRequest request = get("/myapp/api/orders");
        request.setContextPath("/myapp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("strips the context path, and only the context path")
    void pathWithinApplicationStripsContextPath() {
        MockHttpServletRequest withContext = get("/myapp/actuator/health");
        withContext.setContextPath("/myapp");
        assertThat(ConcurrencyLimitFilter.pathWithinApplication(withContext))
                .isEqualTo("/actuator/health");

        MockHttpServletRequest rootContext = get("/actuator/health");
        assertThat(ConcurrencyLimitFilter.pathWithinApplication(rootContext))
                .isEqualTo("/actuator/health");

        // A request for the context root itself must not collapse to an empty
        // string, which would match no prefix and read oddly in logs.
        MockHttpServletRequest contextRoot = get("/myapp");
        contextRoot.setContextPath("/myapp");
        assertThat(ConcurrencyLimitFilter.pathWithinApplication(contextRoot)).isEqualTo("/");

        // A path that merely looks like the context path must be left alone.
        MockHttpServletRequest lookalike = get("/api/orders");
        lookalike.setContextPath("/myapp");
        assertThat(ConcurrencyLimitFilter.pathWithinApplication(lookalike)).isEqualTo("/api/orders");
    }
}
