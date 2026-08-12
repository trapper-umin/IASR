package com.iasr.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter registration lives in a nested {@code @ConditionalOnWebApplication}
 * configuration, which a plain {@link ApplicationContextRunner} never activates.
 * These tests use a servlet context so the registration is actually exercised.
 */
class IasrServletFilterRegistrationTest {

    private static final String[] PROPS = {"iasr.window-ms=600000"};

    @Test
    @DisplayName("registers the concurrency filter for all paths in a servlet application")
    void registersFilterInServletApp() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);

                    FilterRegistrationBean<?> registration =
                            context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(ConcurrencyLimitFilter.class);
                    assertThat(registration.getUrlPatterns()).containsExactly("/*");
                    assertThat(registration.getOrder())
                            .as("the limiter must run early, before expensive downstream filters")
                            .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                });
    }

    @Test
    @DisplayName("does not register a servlet filter in a non-web application")
    void noFilterOutsideServletApp() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(PROPS)
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("the registered filter honours the configured acquire timeout")
    void filterUsesConfiguredTimeout() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("iasr.window-ms=600000",
                        "iasr.concurrency.initial-limit=1",
                        "iasr.concurrency.min-limit=1",
                        "iasr.concurrency.acquire-timeout-ms=1")
                .run(context -> {
                    ConcurrencyLimitFilter filter = (ConcurrencyLimitFilter)
                            context.getBean(FilterRegistrationBean.class).getFilter();

                    // Exhaust the single permit, then confirm the filter rejects
                    // rather than blocking for the default half second.
                    context.getBean(com.iasr.core.actuator.ConcurrencyLimiter.class)
                            .tryAcquire(0, java.util.concurrent.TimeUnit.MILLISECONDS);

                    var response = new org.springframework.mock.web.MockHttpServletResponse();
                    long startNs = System.nanoTime();
                    filter.doFilter(
                            new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/x"),
                            response,
                            new org.springframework.mock.web.MockFilterChain());
                    long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                    assertThat(response.getStatus()).isEqualTo(429);
                    assertThat(elapsedMs)
                            .as("a 1 ms acquire timeout must not wait the 500 ms default")
                            .isLessThan(250);
                });
    }
}
