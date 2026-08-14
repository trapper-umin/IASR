package com.iasr.benchmark.calibration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures a real database's throughput as a function of concurrency.
 *
 * <h3>Why this runs before anything else</h3>
 * Every scenario parameter in this harness — the dependency's optimal
 * concurrency, where the throughput knee sits, how much over-provisioning costs
 * — was previously a number chosen by the author. That is the easiest thing to
 * attack in an evaluation, and the attack would have landed: an earlier version
 * of the synthetic model had no penalty for over-provisioning at all, and under
 * it every measurement said "just make the pool bigger". Rewriting the model
 * reversed that conclusion.
 * <p>
 * This measures the curve instead of assuming it. The result is both a
 * justification for the scenario parameters and a finding in its own right: it
 * shows the knee exists in a real database, at a concurrency that has nothing
 * to do with what anyone configured.
 *
 * <h3>Method</h3>
 * Closed loop on purpose — the question is what the dependency sustains at a
 * given concurrency, not how it behaves under an arrival process. For each
 * level, exactly that many threads issue a fixed-cost CPU-bound query in a
 * tight loop; throughput and latency are taken from the steady portion.
 * <p>
 * The query does real work ({@code sum} over a generated series) rather than
 * sleeping: a sleeping backend contends for nothing, so it would show a
 * plateau where a working one shows a decline. The container is pinned to a
 * small number of cores so that contention appears at a concurrency reachable
 * within a benchmark run.
 */
public final class DependencyCalibration {

    private static final int[] LEVELS = {1, 2, 4, 6, 8, 12, 16, 24, 32, 48, 64};

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("cal.url", "jdbc:postgresql://localhost:55432/iasr");
        String user = System.getProperty("cal.user", "iasr");
        String password = System.getProperty("cal.password", "iasr");
        long seriesSize = Long.getLong("cal.n", 500_000L);
        long perLevelMs = Long.getLong("cal.ms", 8_000L);
        Path out = Path.of(System.getProperty("cal.out", "docs/benchmark/raw/calibration.csv"));

        System.out.printf("Калибровка: %s, стоимость запроса N=%d, %d мс на уровень%n",
                url, seriesSize, perLevelMs);
        System.out.printf("%n%8s %12s %10s %10s %10s%n",
                "conc", "throughput", "mean ms", "p95 ms", "vs peak");

        List<double[]> rows = new ArrayList<>();
        for (int level : LEVELS) {
            rows.add(measure(url, user, password, seriesSize, perLevelMs, level));
        }

        double peak = rows.stream().mapToDouble(r -> r[1]).max().orElse(1);
        StringBuilder csv = new StringBuilder("concurrency,throughput_rps,mean_ms,p95_ms,share_of_peak\n");
        for (double[] r : rows) {
            System.out.printf(Locale.ROOT, "%8.0f %12.1f %10.1f %10.1f %9.0f%%%n",
                    r[0], r[1], r[2], r[3], 100 * r[1] / peak);
            csv.append(String.format(Locale.ROOT, "%.0f,%.1f,%.1f,%.1f,%.3f%n",
                    r[0], r[1], r[2], r[3], r[1] / peak));
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("\nЗаписано: " + out.toAbsolutePath());
    }

    private static double[] measure(String url, String user, String password,
                                    long seriesSize, long durationMs, int concurrency)
            throws Exception {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        // The pool must never be the constraint here — this measures the
        // database, not HikariCP.
        config.setMaximumPoolSize(concurrency);
        config.setMinimumIdle(concurrency);
        config.setPoolName("calibration-" + concurrency);
        config.setConnectionTimeout(30_000);

        AtomicLong completed = new AtomicLong();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        try (HikariDataSource ds = new HikariDataSource(config)) {
            warmUp(ds, seriesSize);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(concurrency);
            long deadline = System.nanoTime() + durationMs * 1_000_000L;

            for (int i = 0; i < concurrency; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        while (System.nanoTime() < deadline) {
                            long t0 = System.nanoTime();
                            query(ds, seriesSize);
                            latencies.add((System.nanoTime() - t0) / 1_000_000);
                            completed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // A failed worker biases the level downwards; report it
                        // rather than letting the number look clean.
                        System.err.println("worker failed at concurrency "
                                + concurrency + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }

            long wallStart = System.nanoTime();
            start.countDown();
            done.await();
            double elapsedSec = (System.nanoTime() - wallStart) / 1_000_000_000.0;

            double[] sorted = latencies.stream().mapToDouble(Long::doubleValue).sorted().toArray();
            double mean = Arrays.stream(sorted).average().orElse(0);
            double p95 = sorted.length == 0 ? 0
                    : sorted[Math.min(sorted.length - 1, (int) Math.ceil(0.95 * sorted.length) - 1)];

            return new double[]{concurrency, completed.get() / elapsedSec, mean, p95};
        }
    }

    private static void warmUp(HikariDataSource ds, long seriesSize) throws Exception {
        for (int i = 0; i < 3; i++) {
            query(ds, seriesSize);
        }
    }

    private static void query(HikariDataSource ds, long seriesSize) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "select sum(i) from generate_series(1, ?) i")) {
            st.setLong(1, seriesSize);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
            }
        }
    }

    private DependencyCalibration() {}
}
