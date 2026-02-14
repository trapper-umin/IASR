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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

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
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "iasr", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IasrAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IasrAutoConfiguration.class);

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

    // ── Servlet filter ───────────────────────────────────────────────────

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
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); // early in the chain
        reg.addUrlPatterns("/*");
        reg.setName("iasrConcurrencyLimitFilter");
        return reg;
    }

    // ── HikariPoolActuator (optional) ────────────────────────────────────

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(HikariPoolActuator.class)
    public HikariPoolActuator iasrHikariPoolActuator(
            DataSource dataSource,
            IasrProperties props) {

        if (dataSource instanceof HikariDataSource hds) {
            IasrProperties.Hikari h = props.getHikari();
            log.info("IASR: HikariDataSource detected, registering HikariPoolActuator [{}-{}]",
                    h.getMinPoolSize(), h.getMaxPoolSize());
            return new HikariPoolActuator(hds, h.getMinPoolSize(), h.getMaxPoolSize());
        }
        log.info("IASR: DataSource is not HikariDataSource, HikariPoolActuator will not be created");
        return null;
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
            @Autowired(required = false) HikariPoolActuator hikariPoolActuator) {

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
