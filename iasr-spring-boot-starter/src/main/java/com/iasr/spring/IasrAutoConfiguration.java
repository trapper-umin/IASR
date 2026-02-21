package com.iasr.spring;

import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.config.ControlEngineConfig;
import com.iasr.core.controller.BaselineController;
import com.iasr.core.engine.ControlEngine;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.micrometer.IasrMeterBinder;
import com.iasr.micrometer.MicrometerMetricsProvider;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Spring Boot auto-configuration for IASR.
 * <p>
 * Activated when {@code iasr.enabled=true} (default) and a
 * {@link MeterRegistry} bean is present in the context.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li>Creates a {@link ConcurrencyLimiter} and registers the servlet filter</li>
 *   <li>If a {@link HikariDataSource} is available, creates a {@link HikariPoolActuator}</li>
 *   <li>Binds IASR meters via {@link IasrMeterBinder}</li>
 *   <li>Creates the {@link MicrometerMetricsProvider}</li>
 *   <li>Creates and starts the {@link ControlEngine}</li>
 * </ol>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "iasr", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IasrAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "iasr")
    @ConditionalOnMissingBean
    public IasrProperties iasrProperties() {
        return new IasrProperties();
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
                IasrMeterBinder meterBinder) {

            ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(
                    limiter,
                    props.getConcurrency().getAcquireTimeoutMs(),
                    meterBinder);

            FilterRegistrationBean<ConcurrencyLimitFilter> reg = new FilterRegistrationBean<>(filter);
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            reg.addUrlPatterns("/*");
            reg.setName("iasrConcurrencyLimitFilter");
            return reg;
        }
    }

    // ── HikariPoolActuator (only when HikariDataSource is present) ───────

    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HikariDataSource.class)
    @ConditionalOnBean(HikariDataSource.class)
    static class HikariActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(HikariPoolActuator.class)
        public HikariPoolActuator iasrHikariPoolActuator(
                HikariDataSource dataSource,
                IasrProperties props) {

            IasrProperties.Hikari h = props.getHikari();
            log.info("IASR: HikariDataSource detected, registering HikariPoolActuator [{}-{}]",
                    h.getMinPoolSize(), h.getMaxPoolSize());
            return new HikariPoolActuator(dataSource, h.getMinPoolSize(), h.getMaxPoolSize());
        }
    }

    // ── MetricsProvider ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public MicrometerMetricsProvider iasrMetricsProvider(MeterRegistry registry) {
        return new MicrometerMetricsProvider(registry);
    }

    // ── ControlEngine ────────────────────────────────────────────────────

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public ControlEngine iasrControlEngine(
            IasrProperties props,
            MicrometerMetricsProvider metricsProvider,
            ConcurrencyLimiter concurrencyLimiter,
            ObjectProvider<HikariPoolActuator> hikariPoolActuatorProvider) {

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

        HikariPoolActuator hikariPoolActuator = hikariPoolActuatorProvider.getIfAvailable();
        if (hikariPoolActuator != null) {
            cfgBuilder
                    .addActuator(hikariPoolActuator)
                    .addGuardrail(new ActuatorGuardrail(
                            HikariPoolActuator.ACTUATOR_NAME,
                            props.getHikari().getMinPoolSize(),
                            props.getHikari().getMaxPoolSize(),
                            props.getHikari().getMaxStep(),
                            props.getHikari().getCooldownTicks()
                    ));
        }

        ControlEngine engine = new ControlEngine(cfgBuilder.build());
        log.info("IASR ControlEngine created: SLO={}ms, window={}ms, actuators={}",
                props.getSloLatencyMs(), props.getWindowMs(),
                hikariPoolActuator != null ? "concurrency+hikari" : "concurrency");

        return engine;
    }
}
