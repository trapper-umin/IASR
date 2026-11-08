package com.iasr.core.guardrail;

import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.support.TestActuator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GuardrailObservationTest {
    @Test
    void observingCooldownDoesNotAdvanceIt() {
        var actuator = new TestActuator("limit", 10, 1, 100);
        var rails = new Guardrails(List.of(new ActuatorGuardrail("limit", 1, 100, 5, 2)), 150);
        var state = MetricsSnapshot.builder().build();
        assertThat(rails.remainingCooldowns()).containsEntry("limit", 0);
        rails.enforce(ControlAction.builder().set("limit", 15).build(), List.of(actuator), state);
        var before = rails.remainingCooldowns();
        assertThat(before).containsEntry("limit", 2);
        assertThat(rails.remainingCooldowns()).isEqualTo(before);
        rails.enforce(ControlAction.NONE, List.of(actuator), state);
        assertThat(rails.remainingCooldowns()).containsEntry("limit", 1);
        assertThat(before).containsEntry("limit", 2);
    }
}
