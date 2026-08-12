package com.iasr.benchmark.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop HTTP load generator.
 *
 * <h3>Why open-loop</h3>
 * A closed-loop generator (N threads, each sending the next request only after
 * the previous one returns) throttles itself whenever the server slows down, so
 * the server never actually sees an overload and every configuration looks
 * survivable.  This generator dispatches at a target arrival rate regardless of
 * how the server is doing, which is how real traffic behaves.
 *
 * <h3>Coordinated omission</h3>
 * Latency is measured from the moment a request was <em>scheduled</em> to be
 * sent, not from when it was actually sent.  If the client falls behind, that
 * delay is counted as latency the user experienced rather than quietly
 * discarded — otherwise the reported percentiles improve exactly when the
 * system gets worse.
 */
public class LoadGenerator {

    /** How long a client waits before giving up, as a real user would. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;
    private final ExecutorService clientExecutor;
    private final URI target;

    public LoadGenerator(String targetUrl) {
        this.target = URI.create(targetUrl);
        this.clientExecutor = Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r, "load-client");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .executor(clientExecutor)
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Dispatch requests following the scenario's arrival-rate schedule.
     *
     * @param rateSchedule requests per second as a function of elapsed seconds
     * @param durationMs   how long to generate load
     * @param onTick       called roughly every 250 ms with elapsed seconds, so the
     *                     caller can drive dependency changes and sample actuators
     * @return every observation recorded during the run
     */
    public Outcomes run(RateSchedule rateSchedule, long durationMs, TickListener onTick) {
        Outcomes outcomes = new Outcomes();
        long startNs = System.nanoTime();
        long endNs = startNs + durationMs * 1_000_000L;
        long nextDispatchNs = startNs;
        long nextTickNs = startNs;

        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        while (nextDispatchNs < endNs) {
            long now = System.nanoTime();

            if (now >= nextTickNs) {
                double elapsedSec = (now - startNs) / 1_000_000_000.0;
                onTick.onTick(elapsedSec);
                nextTickNs = now + 250_000_000L;
            }

            if (now < nextDispatchNs) {
                LockSupport.parkNanos(Math.min(nextDispatchNs - now, 1_000_000L));
                continue;
            }

            final long intendedNs = nextDispatchNs;
            outcomes.dispatched.incrementAndGet();
            outcomes.inFlight.incrementAndGet();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        long latencyMs = (System.nanoTime() - intendedNs) / 1_000_000L;
                        outcomes.record(response == null ? -1 : response.statusCode(), latencyMs, error);
                        outcomes.inFlight.decrementAndGet();
                    });

            double elapsedSec = (nextDispatchNs - startNs) / 1_000_000_000.0;
            double rps = Math.max(1.0, rateSchedule.ratePerSecondAt(elapsedSec));
            nextDispatchNs += (long) (1_000_000_000.0 / rps);
        }

        drain(outcomes);
        return outcomes;
    }

    /** Let outstanding requests finish so their latency is not silently dropped. */
    private void drain(Outcomes outcomes) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (outcomes.inFlight.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(50_000_000L);
        }
    }

    public void close() {
        clientExecutor.shutdownNow();
        try {
            clientExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Requests per second as a function of elapsed time. */
    @FunctionalInterface
    public interface RateSchedule {
        double ratePerSecondAt(double elapsedSeconds);
    }

    @FunctionalInterface
    public interface TickListener {
        void onTick(double elapsedSeconds);
    }

    /** Everything observed during a run. */
    public static class Outcomes {
        final AtomicInteger inFlight = new AtomicInteger();
        public final AtomicLong dispatched = new AtomicLong();
        public final AtomicLong succeeded = new AtomicLong();
        public final AtomicLong rejected429 = new AtomicLong();
        public final AtomicLong serverErrors = new AtomicLong();
        public final AtomicLong clientTimeouts = new AtomicLong();
        public final AtomicLong otherFailures = new AtomicLong();

        /** Latencies of successful responses, in milliseconds. */
        public final ConcurrentLinkedQueue<Long> successLatenciesMs = new ConcurrentLinkedQueue<>();

        void record(int status, long latencyMs, Throwable error) {
            if (error != null) {
                if (isTimeout(error)) {
                    clientTimeouts.incrementAndGet();
                } else {
                    otherFailures.incrementAndGet();
                }
                return;
            }
            if (status == 200) {
                succeeded.incrementAndGet();
                successLatenciesMs.add(latencyMs);
            } else if (status == 429) {
                rejected429.incrementAndGet();
            } else if (status >= 500) {
                serverErrors.incrementAndGet();
            } else {
                otherFailures.incrementAndGet();
            }
        }

        private static boolean isTimeout(Throwable error) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof java.net.http.HttpTimeoutException
                        || t instanceof java.util.concurrent.TimeoutException) {
                    return true;
                }
            }
            return false;
        }
    }
}
