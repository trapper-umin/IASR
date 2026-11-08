package com.iasr.micrometer;

import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.metrics.WindowedLatencyTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerMetricsProviderTest {

    private static SimpleMeterRegistry registryWithPercentiles() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IasrMeterFilterConfigurer.apply(registry);
        return registry;
    }

    /**
     * A distribution whose 95th percentile and maximum are orders of magnitude
     * apart, so a test can tell which one a metric actually reports.
     */
    private static void recordSkewed(Timer timer) {
        for (int i = 0; i < 99; i++) {
            timer.record(1, TimeUnit.MILLISECONDS);
        }
        timer.record(10_000, TimeUnit.MILLISECONDS);
    }

    @Nested
    @DisplayName("percentiles")
    class Percentiles {

        @Test
        @DisplayName("gc_pause_p95_ms reports the 95th percentile, not the maximum")
        void gcPauseUsesPercentileNotMax() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            recordSkewed(Timer.builder("jvm.gc.pause").register(registry));

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            double p95 = snapshot.get(MetricNames.GC_PAUSE_P95_MS);
            assertThat(p95)
                    .as("99 of 100 samples are 1 ms, so p95 must be near 1 ms and nowhere near the 10 s max")
                    .isLessThan(100.0);
            assertThat(p95).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("hikari_acquire_p95_ms reports the 95th percentile, not the maximum")
        void acquireTimeUsesPercentileNotMax() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            recordSkewed(Timer.builder("hikaricp.connections.acquire")
                    .tag("pool", "HikariPool-1")
                    .register(registry));

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS)).isLessThan(100.0);
        }

        @Test
        @DisplayName("falls back to the maximum when the registry publishes no percentiles")
        void fallsBackToMaxWithoutPercentileHistograms() {
            // No IasrMeterFilterConfigurer: percentileValues() will be empty.
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recordSkewed(Timer.builder("jvm.gc.pause").register(registry));

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.GC_PAUSE_P95_MS))
                    .as("an over-estimate is reported rather than a silent zero")
                    .isGreaterThan(1_000.0);
        }

        @Test
        @DisplayName("reports zero when the meter is absent entirely")
        void zeroWhenMeterMissing() {
            MetricsSnapshot snapshot =
                    new MicrometerMetricsProvider(registryWithPercentiles()).snapshot();

            assertThat(snapshot.get(MetricNames.GC_PAUSE_P95_MS)).isZero();
            assertThat(snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS)).isZero();
        }
    }

    @Nested
    @DisplayName("latency")
    class Latency {

        @Test
        @DisplayName("takes per-window percentiles from the tracker when one is supplied")
        void usesWindowedTracker() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            WindowedLatencyTracker tracker = new WindowedLatencyTracker();
            for (int i = 1; i <= 100; i++) {
                tracker.record(i);
            }

            MetricsSnapshot snapshot =
                    new MicrometerMetricsProvider(registry, null, tracker).snapshot();

            assertThat(snapshot.get(MetricNames.LATENCY_P95_MS)).isEqualTo(95.0);
            assertThat(snapshot.get(MetricNames.LATENCY_P99_MS)).isEqualTo(99.0);
            assertThat(snapshot.get(MetricNames.LATENCY_MEAN_MS)).isEqualTo(50.5);
        }

        @Test
        @DisplayName("consecutive windows report different latencies rather than a decaying average")
        void windowsAreIndependent() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            WindowedLatencyTracker tracker = new WindowedLatencyTracker();
            MicrometerMetricsProvider provider =
                    new MicrometerMetricsProvider(registry, null, tracker);

            for (int i = 0; i < 100; i++) {
                tracker.record(500);
            }
            double first = provider.snapshot().get(MetricNames.LATENCY_P95_MS);

            for (int i = 0; i < 100; i++) {
                tracker.record(5);
            }
            double second = provider.snapshot().get(MetricNames.LATENCY_P95_MS);

            assertThat(first).isEqualTo(500.0);
            assertThat(second)
                    .as("the previous window's slow requests must not bleed into this one")
                    .isEqualTo(5.0);
        }

        @Test
        @DisplayName("reports zeros for health-check level traffic instead of a stale histogram")
        void belowNoiseThresholdReportsZero() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer http = Timer.builder("http.server.requests").register(registry);
            http.record(900, TimeUnit.MILLISECONDS);
            http.record(900, TimeUnit.MILLISECONDS);

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.LATENCY_P95_MS)).isZero();
            assertThat(snapshot.get(MetricNames.LATENCY_MEAN_MS)).isZero();
        }

        @Test
        @DisplayName("without a tracker, falls back to the per-window mean once traffic is real")
        void fallsBackToWindowMeanWithoutTracker() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer http = Timer.builder("http.server.requests").register(registry);
            for (int i = 0; i < 10; i++) {
                http.record(20, TimeUnit.MILLISECONDS);
            }

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.LATENCY_MEAN_MS)).isCloseTo(20.0, org.assertj.core.data.Offset.offset(1.0));
        }
    }

    @Nested
    @DisplayName("throughput")
    class Throughput {

        @Test
        @DisplayName("80 successful responses and 20 rejections produce goodput 80, not 100")
        void rejectionsAreNotGoodput() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer success = Timer.builder("http.server.requests").tag("status", "200").register(registry);
            Timer rejected = Timer.builder("http.server.requests").tag("status", "429").register(registry);
            for (int i = 0; i < 80; i++) success.record(10, TimeUnit.MILLISECONDS);
            for (int i = 0; i < 20; i++) rejected.record(10, TimeUnit.MILLISECONDS);
            Counter.builder("iasr.concurrency.timeouts").register(registry).increment(20);

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.GOODPUT_RPS)).isEqualTo(80.0);
            assertThat(snapshot.get(MetricNames.TIMEOUT_RATE)).isEqualTo(0.2);
        }

        @Test
        @DisplayName("only 2xx responses count as successful when HTTP statuses are available")
        void countsOnlySuccessfulStatuses() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            for (String status : new String[]{"200", "201", "204", "302", "404", "429", "500"}) {
                Timer.builder("http.server.requests").tag("status", status).register(registry)
                        .record(10, TimeUnit.MILLISECONDS);
            }
            Counter.builder("iasr.http.errors").register(registry).increment();

            assertThat(new MicrometerMetricsProvider(registry).snapshot().get(MetricNames.GOODPUT_RPS))
                    .isEqualTo(3.0);
        }

        @Test
        @DisplayName("untagged legacy timers subtract both errors and admission timeouts")
        void legacyTimersExcludeKnownFailures() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer http = Timer.builder("http.server.requests").register(registry);
            for (int i = 0; i < 100; i++) http.record(10, TimeUnit.MILLISECONDS);
            Counter.builder("iasr.http.errors").register(registry).increment(10);
            Counter.builder("iasr.concurrency.timeouts").register(registry).increment(20);

            assertThat(new MicrometerMetricsProvider(registry).snapshot().get(MetricNames.GOODPUT_RPS))
                    .isEqualTo(70.0);
        }

        @Test
        @DisplayName("business counters work without HTTP timers and exclude infrastructure traffic")
        void prefersBusinessCounters() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            IasrMeterBinder binder = new IasrMeterBinder(
                    new com.iasr.core.actuator.ConcurrencyLimiter(10, 1, 20));
            binder.bindTo(registry);
            for (int i = 0; i < 80; i++) binder.recordResponse(200);
            for (int i = 0; i < 20; i++) {
                binder.recordTimeout();
                binder.recordResponse(429);
            }
            MicrometerMetricsProvider provider = new MicrometerMetricsProvider(registry);
            MetricsSnapshot first = provider.snapshot();
            assertThat(first.get(MetricNames.GOODPUT_RPS)).isEqualTo(80.0);
            assertThat(first.get(MetricNames.TIMEOUT_RATE)).isEqualTo(0.2);

            Timer probes = Timer.builder("http.server.requests").tag("status", "200")
                    .tag("uri", "/actuator/health").register(registry);
            for (int i = 0; i < 100; i++) probes.record(1, TimeUnit.MILLISECONDS);
            MetricsSnapshot second = provider.snapshot();
            assertThat(second.get(MetricNames.GOODPUT_RPS)).isZero();
            assertThat(second.get(MetricNames.TIMEOUT_RATE)).isZero();
        }

        @Test
        @DisplayName("a binder without HTTP response instrumentation does not hide existing HTTP timers")
        void inactiveBusinessCountersDoNotHideHttpTimers() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            new IasrMeterBinder(new com.iasr.core.actuator.ConcurrencyLimiter(10, 1, 20))
                    .bindTo(registry);
            Timer.builder("http.server.requests").tag("status", "200").register(registry)
                    .record(10, TimeUnit.MILLISECONDS);

            assertThat(new MicrometerMetricsProvider(registry).snapshot().get(MetricNames.GOODPUT_RPS))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("rates use only the current window and its actual elapsed duration")
        void ratesUseElapsedWindow() {
            MockClock clock = new MockClock();
            SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
            Timer success = Timer.builder("http.server.requests").tag("status", "200").register(registry);
            Timer rejected = Timer.builder("http.server.requests").tag("status", "429").register(registry);
            Counter timeouts = Counter.builder("iasr.concurrency.timeouts").register(registry);
            MicrometerMetricsProvider provider = new MicrometerMetricsProvider(registry);
            provider.snapshot();

            for (int i = 0; i < 80; i++) success.record(10, TimeUnit.MILLISECONDS);
            for (int i = 0; i < 20; i++) rejected.record(10, TimeUnit.MILLISECONDS);
            timeouts.increment(20);
            clock.add(Duration.ofSeconds(2));
            MetricsSnapshot second = provider.snapshot();

            assertThat(second.get(MetricNames.GOODPUT_RPS)).isEqualTo(40.0);
            assertThat(second.get(MetricNames.TIMEOUT_RATE)).isEqualTo(0.2);
            clock.add(Duration.ofSeconds(1));
            assertThat(provider.snapshot().get(MetricNames.GOODPUT_RPS)).isZero();
        }

        @Test
        @DisplayName("changing counter sources establishes a new baseline without a throughput spike")
        void sourceChangesResetBaseline() {
            MockClock clock = new MockClock();
            SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
            Timer success = Timer.builder("http.server.requests").tag("status", "200").register(registry);
            success.record(10, TimeUnit.MILLISECONDS);
            MicrometerMetricsProvider provider = new MicrometerMetricsProvider(registry);
            provider.snapshot();

            IasrMeterBinder binder = new IasrMeterBinder(
                    new com.iasr.core.actuator.ConcurrencyLimiter(10, 1, 20));
            binder.bindTo(registry);
            for (int i = 0; i < 100; i++) binder.recordResponse(200);
            clock.add(Duration.ofSeconds(1));
            assertThat(provider.snapshot().get(MetricNames.GOODPUT_RPS)).isZero();

            binder.recordResponse(200);
            clock.add(Duration.ofSeconds(1));
            assertThat(provider.snapshot().get(MetricNames.GOODPUT_RPS)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("derives goodput, error rate and timeout rate from per-window deltas")
        void computesRatesFromDeltas() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer http = Timer.builder("http.server.requests").register(registry);
            Counter errors = Counter.builder("iasr.http.errors").register(registry);
            Counter timeouts = Counter.builder("iasr.concurrency.timeouts").register(registry);

            for (int i = 0; i < 100; i++) {
                http.record(10, TimeUnit.MILLISECONDS);
            }
            for (int i = 0; i < 10; i++) {
                errors.increment();
            }
            for (int i = 0; i < 5; i++) {
                timeouts.increment();
            }

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.ERROR_RATE)).isEqualTo(0.1);
            assertThat(snapshot.get(MetricNames.TIMEOUT_RATE)).isEqualTo(0.05);
            assertThat(snapshot.get(MetricNames.GOODPUT_RPS)).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("a window with no new requests reports zero rates, not the previous window's")
        void quietWindowReportsZeroes() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer http = Timer.builder("http.server.requests").register(registry);
            Counter.builder("iasr.http.errors").register(registry).increment(10);
            for (int i = 0; i < 100; i++) {
                http.record(10, TimeUnit.MILLISECONDS);
            }

            MicrometerMetricsProvider provider = new MicrometerMetricsProvider(registry);
            provider.snapshot();
            MetricsSnapshot second = provider.snapshot();

            assertThat(second.get(MetricNames.ERROR_RATE)).isZero();
            assertThat(second.get(MetricNames.TIMEOUT_RATE)).isZero();
            assertThat(second.get(MetricNames.GOODPUT_RPS)).isZero();
        }
    }

    @Nested
    @DisplayName("hikari")
    class Hikari {

        @Test
        @DisplayName("reports the per-window mean acquire time alongside the percentile")
        void reportsPerWindowMeanAcquireTime() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer acquire = Timer.builder("hikaricp.connections.acquire")
                    .tag("pool", "HikariPool-1")
                    .register(registry);
            for (int i = 0; i < 10; i++) {
                acquire.record(20, TimeUnit.MILLISECONDS);
            }

            MicrometerMetricsProvider provider = new MicrometerMetricsProvider(registry);
            MetricsSnapshot first = provider.snapshot();

            assertThat(first.get(MetricNames.HIKARI_ACQUIRE_MEAN_MS))
                    .isCloseTo(20.0, org.assertj.core.data.Offset.offset(1.0));

            // No further acquires: the next window reports zero, not the old mean.
            assertThat(provider.snapshot().get(MetricNames.HIKARI_ACQUIRE_MEAN_MS)).isZero();
        }

        @Test
        @DisplayName("only reads the pool it was configured for")
        void readsOnlyTheConfiguredPool() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            io.micrometer.core.instrument.Gauge.builder(
                            "hikaricp.connections.pending", () -> 7.0)
                    .tag("pool", "OtherPool")
                    .register(registry);

            MetricsSnapshot snapshot =
                    new MicrometerMetricsProvider(registry, "HikariPool-1").snapshot();

            assertThat(snapshot.get(MetricNames.HIKARI_PENDING)).isZero();
        }

        @Test
        @DisplayName("reads pool gauges for the configured pool name")
        void readsPoolGauges() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            io.micrometer.core.instrument.Gauge.builder("hikaricp.connections.active", () -> 3.0)
                    .tag("pool", "HikariPool-1").register(registry);
            io.micrometer.core.instrument.Gauge.builder("hikaricp.connections.idle", () -> 2.0)
                    .tag("pool", "HikariPool-1").register(registry);
            io.micrometer.core.instrument.Gauge.builder("hikaricp.connections.pending", () -> 4.0)
                    .tag("pool", "HikariPool-1").register(registry);

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.HIKARI_ACTIVE)).isEqualTo(3.0);
            assertThat(snapshot.get(MetricNames.HIKARI_IDLE)).isEqualTo(2.0);
            assertThat(snapshot.get(MetricNames.HIKARI_PENDING)).isEqualTo(4.0);
        }
    }

    @Nested
    @DisplayName("latency decomposition")
    class Decomposition {

        @Test
        @DisplayName("SLO latency includes admission wait but dependency residual excludes it")
        void admissionWaitDoesNotBecomeDependencyTime() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            WindowedLatencyTracker tracker = new WindowedLatencyTracker();
            Timer acquire = Timer.builder("hikaricp.connections.acquire")
                    .tag("pool", "HikariPool-1").register(registry);
            for (int i = 0; i < 20; i++) {
                tracker.record(80, 100);
                acquire.record(20, TimeUnit.MILLISECONDS);
            }

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry, null, tracker).snapshot();

            assertThat(snapshot.get(MetricNames.LATENCY_P95_MS)).isEqualTo(180.0);
            assertThat(snapshot.get(MetricNames.LATENCY_MEAN_MS)).isEqualTo(180.0);
            assertThat(snapshot.get(MetricNames.SERVICE_MEAN_MS)).isEqualTo(80.0);
            assertThat(snapshot.get(MetricNames.DEPENDENCY_MEAN_MS)).isEqualTo(60.0);
        }

        @Test
        @DisplayName("reports the permit wait, which service latency excludes by construction")
        void reportsAdmissionWait() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            Timer wait = Timer.builder("iasr.concurrency.wait").register(registry);
            for (int i = 0; i < 10; i++) {
                wait.record(30, TimeUnit.MILLISECONDS);
            }

            MetricsSnapshot snapshot = new MicrometerMetricsProvider(registry).snapshot();

            assertThat(snapshot.get(MetricNames.ADMISSION_WAIT_MEAN_MS))
                    .isCloseTo(30.0, org.assertj.core.data.Offset.offset(1.0));
        }

        @Test
        @DisplayName("dependency time is service time with the connection wait removed")
        void dependencyIsTheResidual() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            WindowedLatencyTracker tracker = new WindowedLatencyTracker();
            for (int i = 0; i < 20; i++) {
                tracker.record(80);
            }
            Timer acquire = Timer.builder("hikaricp.connections.acquire")
                    .tag("pool", "HikariPool-1").register(registry);
            for (int i = 0; i < 20; i++) {
                acquire.record(20, TimeUnit.MILLISECONDS);
            }

            MetricsSnapshot snapshot =
                    new MicrometerMetricsProvider(registry, null, tracker).snapshot();

            assertThat(snapshot.get(MetricNames.DEPENDENCY_MEAN_MS))
                    .as("80 ms of service with 20 ms of it spent waiting for a connection")
                    .isCloseTo(60.0, org.assertj.core.data.Offset.offset(2.0));
        }

        @Test
        @DisplayName("never reports a negative residual")
        void residualIsClamped() {
            SimpleMeterRegistry registry = registryWithPercentiles();
            WindowedLatencyTracker tracker = new WindowedLatencyTracker();
            for (int i = 0; i < 20; i++) {
                tracker.record(10);
            }
            Timer acquire = Timer.builder("hikaricp.connections.acquire")
                    .tag("pool", "HikariPool-1").register(registry);
            for (int i = 0; i < 20; i++) {
                acquire.record(50, TimeUnit.MILLISECONDS);
            }

            MetricsSnapshot snapshot =
                    new MicrometerMetricsProvider(registry, null, tracker).snapshot();

            assertThat(snapshot.get(MetricNames.DEPENDENCY_MEAN_MS)).isNotNegative();
        }
    }

    @Test
    @DisplayName("never returns a null snapshot, even against a completely empty registry")
    void emptyRegistryYieldsUsableSnapshot() {
        MetricsSnapshot snapshot = new MicrometerMetricsProvider(new SimpleMeterRegistry()).snapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.get(MetricNames.GOODPUT_RPS)).isZero();
        assertThat(snapshot.get(MetricNames.INFLIGHT)).isZero();
        assertThat(snapshot.get(MetricNames.CPU_PROCESS)).isZero();
    }
}
