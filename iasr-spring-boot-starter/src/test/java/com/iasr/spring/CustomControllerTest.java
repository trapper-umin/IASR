package com.iasr.spring;

import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.engine.ControlEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replacing the decision logic is the point of the {@link Controller} SPI — a
 * learned policy, an MPC, a comparison baseline. The auto-configuration has to
 * honour a supplied bean, otherwise the only way to substitute a controller
 * through Spring is to replace the whole engine and rebuild its configuration
 * by hand, which is not the "swap one bean" the design claims.
 */
class CustomControllerTest {

    private static final Controller MARKER = (snapshot, actuators) -> ControlAction.NONE;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("iasr.window-ms=600000");

    @Test
    @DisplayName("uses a supplied Controller bean instead of the bundled baseline")
    void usesSuppliedController() {
        runner.withBean("customController", Controller.class, () -> MARKER).run(context -> {
            ControlEngine engine = context.getBean(ControlEngine.class);
            assertThat(engine.getConfig().controller()).isSameAs(MARKER);
        });
    }

    @Test
    @DisplayName("falls back to the baseline controller when none is supplied")
    void fallsBackToBaseline() {
        runner.run(context -> {
            ControlEngine engine = context.getBean(ControlEngine.class);
            assertThat(engine.getConfig().controller())
                    .isInstanceOf(com.iasr.core.controller.BaselineController.class);
        });
    }
}
