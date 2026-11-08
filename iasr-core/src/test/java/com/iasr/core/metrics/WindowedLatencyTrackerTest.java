package com.iasr.core.metrics;

import com.iasr.core.metrics.WindowedLatencyTracker.WindowStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WindowedLatencyTrackerTest {

    @Test
    @DisplayName("reports EMPTY when nothing was recorded")
    void emptyWhenNoObservations() {
        assertThat(new WindowedLatencyTracker().drainAndCompute()).isEqualTo(WindowStats.EMPTY);
        assertThat(WindowStats.EMPTY.hasData()).isFalse();
    }

    @Test
    @DisplayName("computes count, mean, percentiles and max over the window")
    void computesStatistics() {
        WindowedLatencyTracker tracker = new WindowedLatencyTracker();
        for (int i = 1; i <= 100; i++) {
            tracker.record(i);
        }

        WindowStats stats = tracker.drainAndCompute();

        assertThat(stats.hasData()).isTrue();
        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.meanMs()).isEqualTo(50.5);
        assertThat(stats.p95Ms()).isEqualTo(95.0);
        assertThat(stats.p99Ms()).isEqualTo(99.0);
        assertThat(stats.maxMs()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("handles a single observation without falling off the array")
    void singleObservation() {
        WindowedLatencyTracker tracker = new WindowedLatencyTracker();
        tracker.record(42.0);

        WindowStats stats = tracker.drainAndCompute();

        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.p95Ms()).isEqualTo(42.0);
        assertThat(stats.p99Ms()).isEqualTo(42.0);
        assertThat(stats.maxMs()).isEqualTo(42.0);
    }

    @Test
    @DisplayName("includes admission wait per request and retains a separate service mean")
    void includesAdmissionWait() {
        WindowedLatencyTracker tracker = new WindowedLatencyTracker();
        tracker.record(10, 90);
        tracker.record(30, 10);

        WindowStats stats = tracker.drainAndCompute();

        assertThat(stats.meanMs()).isEqualTo(70.0);
        assertThat(stats.p95Ms()).isEqualTo(100.0);
        assertThat(stats.serviceMeanMs()).isEqualTo(20.0);
        assertThat(tracker.drainAndCompute()).isEqualTo(WindowStats.EMPTY);
    }

    @Test
    @DisplayName("computes percentiles of paired durations rather than adding percentiles")
    void pairsAdmissionAndServiceBeforeComputingPercentiles() {
        WindowedLatencyTracker tracker = new WindowedLatencyTracker();
        for (int i = 0; i < 50; i++) {
            tracker.record(90, 10);
            tracker.record(10, 90);
        }

        assertThat(tracker.drainAndCompute().p95Ms()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("percentiles do not depend on insertion order")
    void orderIndependent() {
        WindowedLatencyTracker ascending = new WindowedLatencyTracker();
        WindowedLatencyTracker descending = new WindowedLatencyTracker();
        for (int i = 1; i <= 50; i++) {
            ascending.record(i);
            descending.record(51 - i);
        }

        assertThat(ascending.drainAndCompute()).isEqualTo(descending.drainAndCompute());
    }

    @Test
    @DisplayName("drain empties the window so the next tick sees only fresh data")
    void drainResetsTheWindow() {
        WindowedLatencyTracker tracker = new WindowedLatencyTracker();
        tracker.record(1000.0);
        tracker.drainAndCompute();

        // Second window: only the new observation, no carry-over from the first.
        tracker.record(10.0);
        WindowStats second = tracker.drainAndCompute();

        assertThat(second.count()).isEqualTo(1);
        assertThat(second.maxMs()).isEqualTo(10.0);

        // Third window: nothing recorded at all.
        assertThat(tracker.drainAndCompute()).isEqualTo(WindowStats.EMPTY);
    }

    @Test
    @DisplayName("loses no observations under concurrent writers")
    void concurrentWritesAreNotLost() throws Exception {
        WindowedLatencyTracker tracker = new WindowedLatencyTracker();
        int threads = 8;
        int perThread = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        tracker.record(1.0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(tracker.drainAndCompute().count()).isEqualTo(threads * perThread);
    }
}
