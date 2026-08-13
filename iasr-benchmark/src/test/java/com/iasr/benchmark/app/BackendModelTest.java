package com.iasr.benchmark.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dependency must have a throughput peak, not a plateau. Without a decline
 * past the optimum, over-provisioning costs nothing, the best pool policy is
 * "take the maximum", and no coordination algorithm can show value — the
 * measurements confirmed exactly that before this was modelled.
 */
class BackendModelTest {

    private static BackendModel model(double thrash) {
        return new BackendModel(12, 50, 5, thrash);
    }

    @Test
    @DisplayName("service time is flat up to the optimum")
    void flatBelowOptimum() {
        BackendModel m = model(0.3);

        assertThat(m.serviceTimeFor(1)).isEqualTo(50);
        assertThat(m.serviceTimeFor(6)).isEqualTo(50);
        assertThat(m.serviceTimeFor(12)).isEqualTo(50);
    }

    @Test
    @DisplayName("throughput peaks at the optimum and declines beyond it")
    void throughputHasAKnee() {
        BackendModel m = model(0.3);

        double peak = m.throughputAt(12);
        assertThat(peak).isCloseTo(240.0, org.assertj.core.data.Offset.offset(1.0));

        assertThat(m.throughputAt(24))
                .as("twice the optimal concurrency must serve less, not the same")
                .isLessThan(peak * 0.85);
        assertThat(m.throughputAt(48)).isLessThan(m.throughputAt(24));
    }

    @Test
    @DisplayName("a zero thrash factor restores the previous flat-throughput model")
    void zeroThrashIsTheOldModel() {
        BackendModel m = model(0.0);

        double peak = m.throughputAt(12);
        assertThat(m.throughputAt(24)).isCloseTo(peak, org.assertj.core.data.Offset.offset(1.0));
        assertThat(m.throughputAt(120)).isCloseTo(peak, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("over-provisioning a pool now has a measurable price")
    void overProvisioningCosts() {
        BackendModel m = model(0.3);

        // A pool sized at the optimum against one sized well past it.
        assertThat(m.throughputAt(20))
                .as("a pool of 20 against 12 workers should serve less than a pool of 12")
                .isLessThan(m.throughputAt(12));
    }

    @Test
    @DisplayName("a slower dependency scales the whole curve")
    void degradationScalesTheCurve() {
        BackendModel m = model(0.3);
        double before = m.throughputAt(12);

        m.setServiceTimeMs(120);

        assertThat(m.throughputAt(12)).isLessThan(before * 0.5);
    }
}
