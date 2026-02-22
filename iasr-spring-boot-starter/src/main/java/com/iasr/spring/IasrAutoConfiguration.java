package com.iasr.spring;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.config.ControlEngineConfig;
import com.iasr.core.controller.BaselineController;
import com.iasr.core.engine.ControlEngine;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.WindowedLatencyTracker;
import com.iasr.micrometer.IasrMeterBinder;
import com.iasr.micrometer.IasrMeterFilterConfigurer;
import com.iasr.micrometer.MicrometerMetricsProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Spring Boot auto-configuration for IASR.
 * <p>
 * Activated when {@code iasr.enabled=true} (default) and a
 * {@link MeterRegistry} bean is present in the context.
 * <p>
 * <b>Important</b>: no direct {@code .class} references to HikariCP or
 * {@code HikariPoolActuator} exist in this outer class.  All Hikari-related
 * code lives in the inner {@code HikariActuatorConfiguration}, guarded by
 * string-based {@code @ConditionalOnClass}.  This prevents
 * {@code NoClassDefFoundError} when HikariCP is not on the classpath.
 */
@Slf4j
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "iasr", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IasrAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "iasr")
    @ConditionalOnMissingBean
    public IasrProperties iasrProperties() {
        return new IasrProperties();
    }

    // ── Percentile histogram filter ─────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(name = "iasrPercentileMeterFilter")
    public MeterFilter iasrPercentileMeterFilter() {
        return IasrMeterFilterConfigurer.createFilter();
    }

    // ── Per-window latency tracker ──────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public WindowedLatencyTracker iasrLatencyTracker() {
        return new WindowedLatencyTracker();
    }

    // ── ConcurrencyLimiter ───────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public ConcurrencyLimiter iasrConcurrencyLimiter(IasrProperties props) {
        IasrProperties.Concurrency c = props.getConcurrency();
        return new ConcurrencyLimiter(c.getInitialLimit(), c.getMinLimit(), c.getMaxLimit());
    }

    // ── Meter binder ─────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public IasrMeterBinder iasrMeterBinder(ConcurrencyLimiter limiter, MeterRegistry registry) {
        IasrMeterBinder binder = new IasrMeterBinder(limiter);
        binder.bindTo(registry);
        return binder;
    }

    // ── Servlet filter (only in servlet web apps) ────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletFilterConfiguration {

        @Bean
        public FilterRegistrationBean<ConcurrencyLimitFilter> iasrConcurrencyFilter(
                ConcurrencyLimiter limiter,
                IasrProperties props,
                IasrMeterBinder meterBinder,
                WindowedLatencyTracker latencyTracker) {

            ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(
                    limiter,
                    props.getConcurrency().getAcquireTimeoutMs(),
                    meterBinder,
                    latencyTracker);

            FilterRegistrationBean<ConcurrencyLimitFilter> reg = new FilterRegistrationBean<>(filter);
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            reg.addUrlPatterns("/*");
            reg.setName("iasrConcurrencyLimitFilter");
            return reg;
        }
    }

    // ── HikariPoolActuator (only when HikariCP is on the classpath) ──────
    //
    // Uses string-based @ConditionalOnClass so Spring evaluates the
    // condition via ASM bytecode WITHOUT loading these classes.  With
    // .class literals, Java reflection resolves them eagerly, causing
    // NoClassDefFoundError when HikariCP is absent.

    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "com.zaxxer.hikari.HikariDataSource",
            "com.iasr.core.actuator.HikariPoolActuator"
    })
    @ConditionalOnBean(type = "com.zaxxer.hikari.HikariDataSource")
    static class HikariActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(type = "com.iasr.core.actuator.HikariPoolActuator")
        public com.iasr.core.actuator.HikariPoolActuator iasrHikariPoolActuator(
                com.zaxxer.hikari.HikariDataSource dataSource,
                IasrProperties props) {

            IasrProperties.Hikari h = props.getHikari();
            log.info("IASR: HikariDataSource detected, registering HikariPoolActuator [{}-{}]",
                    h.getMinPoolSize(), h.getMaxPoolSize());
            return new com.iasr.core.actuator.HikariPoolActuator(
                    dataSource, h.getMinPoolSize(), h.getMaxPoolSize());
        }
    }

    // ── MetricsProvider ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public MicrometerMetricsProvider iasrMetricsProvider(
            MeterRegistry registry,
            WindowedLatencyTracker latencyTracker) {
        return new MicrometerMetricsProvider(registry, null, latencyTracker);
    }

    // ── ControlEngine ────────────────────────────────────────────────────
    // Accepts all Actuator beans via List<Actuator> instead of
    // ObjectProvider<HikariPoolActuator> — avoids class-loading the
    // HikariPoolActuator type when HikariCP is absent.

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public ControlEngine iasrControlEngine(
            IasrProperties props,
            MicrometerMetricsProvider metricsProvider,
            ConcurrencyLimiter concurrencyLimiter,
            ObjectProvider<List<Actuator>> additionalActuatorsProvider) {

        IasrProperties.ControllerProps cp = props.getController();
        BaselineController controller = new BaselineController(
                props.getSloLatencyMs(),
                cp.getComfortFactor(),
                cp.getConcurrencyIncreaseStep(),
                cp.getConcurrencyDecreaseFactor(),
                cp.getErrorRateThreshold(),
                cp.getTimeoutRateThreshold(),
                cp.getHikariStep(),
                cp.getHikariPendingThreshold(),
                cp.getHikariAcquireTimeThresholdMs()
        );

        ControlEngineConfig.Builder cfgBuilder = ControlEngineConfig.builder()
                .windowMs(props.getWindowMs())
                .sloLatencyMs(props.getSloLatencyMs())
                .enabled(props.isEnabled())
                .datasetLoggingEnabled(props.isDatasetLoggingEnabled())
                .metricsProvider(metricsProvider)
                .controller(controller)
                .addActuator(concurrencyLimiter)
                .addGuardrail(new ActuatorGuardrail(
                        ConcurrencyLimiter.ACTUATOR_NAME,
                        props.getConcurrency().getMinLimit(),
                        props.getConcurrency().getMaxLimit(),
                        props.getConcurrency().getMaxStep(),
                        props.getConcurrency().getCooldownTicks()
                ));

        // Discover HikariPoolActuator (or any other Actuator) if present
        boolean hasHikari = false;
        List<Actuator> extras = additionalActuatorsProvider.getIfAvailable();
        if (extras != null) {
            for (Actuator actuator : extras) {
                if (actuator instanceof ConcurrencyLimiter) continue;
                cfgBuilder.addActuator(actuator);
                if ("hikari_max_pool".equals(actuator.name())) {
                    hasHikari = true;
                    cfgBuilder.addGuardrail(new ActuatorGuardrail(
                            actuator.name(),
                            props.getHikari().getMinPoolSize(),
                            props.getHikari().getMaxPoolSize(),
                            props.getHikari().getMaxStep(),
                            props.getHikari().getCooldownTicks()
                    ));
                }
            }
        }

        ControlEngine engine = new ControlEngine(cfgBuilder.build());
        log.info("IASR ControlEngine created: SLO={}ms, window={}ms, actuators={}",
                props.getSloLatencyMs(), props.getWindowMs(),
                hasHikari ? "concurrency+hikari" : "concurrency");

        return engine;
    }
}
