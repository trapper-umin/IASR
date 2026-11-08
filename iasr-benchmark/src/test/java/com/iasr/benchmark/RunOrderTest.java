package com.iasr.benchmark;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RunOrderTest {
    @Test
    void balancesEveryModeAcrossEveryPosition() {
        List<String> modes = List.of("fixed", "adaptive", "gradient");
        for (int position = 0; position < modes.size(); position++) {
            var seen = new HashSet<String>();
            for (int repeat = 0; repeat < modes.size(); repeat++) {
                var order = RunOrder.forRepetition(modes, "spike", 42, repeat);
                assertThat(order).containsExactlyInAnyOrderElementsOf(modes);
                seen.add(order.get(position));
            }
            assertThat(seen).containsExactlyInAnyOrderElementsOf(modes);
        }
        assertThat(modes).containsExactly("fixed", "adaptive", "gradient");
    }

    @Test
    void seedAndRepetitionReproduceOrder() {
        var modes = List.of("fixed", "adaptive", "gradient");
        assertThat(RunOrder.forRepetition(modes, "spike", 42, 1))
                .isEqualTo(RunOrder.forRepetition(modes, "spike", 42, 1));
        assertThat(RunOrder.forRepetition(modes, "spike", 42, 0))
                .isNotEqualTo(RunOrder.forRepetition(modes, "spike", 42, 1));
    }
}
