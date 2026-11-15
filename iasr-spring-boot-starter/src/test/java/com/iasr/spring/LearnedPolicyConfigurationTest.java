package com.iasr.spring;

import com.iasr.core.controller.BaselineController;
import com.iasr.core.engine.ControlEngine;
import com.iasr.policy.coordinated.CoordinatedController;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The learned coordinated policy is a first-class mode of the library: one
 * property turns it on, and the rule-based controller becomes its fallback.
 */
class LearnedPolicyConfigurationTest {

    private static final String POLICY =
            Path.of("../iasr-policy/src/test/resources/coordinated/policy.json").toAbsolutePath().toString();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("iasr.window-ms=600000");

    private static HikariDataSource unstartedHikari() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(10);
        ds.setPoolName("policy-test-pool");
        return ds;
    }

    @Test
    @DisplayName("iasr.policy.model-path puts the learned policy in charge of both resources")
    void modelPathSelectsTheCoordinatedController() {
        runner.withBean("dataSource", HikariDataSource.class, LearnedPolicyConfigurationTest::unstartedHikari)
                .withPropertyValues("iasr.policy.model-path=" + POLICY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ControlEngine.class).getConfig().controller())
                            .isInstanceOf(CoordinatedController.class);
                });
    }

    @Test
    @DisplayName("without a model the rules decide alone")
    void noModelKeepsTheBaseline() {
        runner.run(context -> assertThat(context.getBean(ControlEngine.class).getConfig().controller())
                .isInstanceOf(BaselineController.class));
    }

    @Test
    @DisplayName("a policy that manages a resource the application does not have fails at startup")
    void policyForAnAbsentResourceFailsFast() {
        runner.withPropertyValues("iasr.policy.model-path=" + POLICY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("no guardrail");
        });
    }

    @Test
    @DisplayName("an unreadable model file fails at startup instead of silently running the rules")
    void missingModelFailsFast() {
        runner.withPropertyValues("iasr.policy.model-path=/nonexistent/policy.json").run(context ->
                assertThat(context).hasFailed());
    }
}
