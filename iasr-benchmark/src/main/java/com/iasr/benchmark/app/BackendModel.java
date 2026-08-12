package com.iasr.benchmark.app;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The synthetic downstream dependency the benchmark application talks to.
 *
 * <h3>Why a model instead of a real database</h3>
 * The harness needs a dependency whose capacity is known exactly, so that the
 * "right" concurrency limit can be computed analytically and the controller can
 * be judged against it.  A real database's capacity is neither knowable nor
 * stable enough for that.
 *
 * <h3>The model</h3>
 * A fixed number of {@link #workers} serve requests, each taking
 * {@link #serviceTimeMs} milliseconds.  Requests beyond that count queue.
 * Capacity is therefore {@code workers / serviceTimeMs} requests per second,
 * and it is a property of the dependency — <b>not</b> of the connection pool.
 * <p>
 * That distinction is the whole point.  A naive model where sleeping while
 * holding a connection is the only constraint would make the pool size the sole
 * determinant of throughput, so growing the pool would always help and the
 * controller's pool logic would look better than it deserves.  Here, growing
 * the pool past {@code workers} only moves the queue from HikariCP into the
 * dependency: throughput stops improving while latency keeps rising — exactly
 * the trap a real database sets.
 */
public class BackendModel {

    private volatile Semaphore workers;
    private volatile int workerCount;
    private volatile long serviceTimeMs;
    private volatile long appTimeMs;

    public BackendModel(int workers, long serviceTimeMs, long appTimeMs) {
        this.workerCount = workers;
        this.workers = new Semaphore(workers, true);
        this.serviceTimeMs = serviceTimeMs;
        this.appTimeMs = appTimeMs;
    }

    /** Requests per second the dependency can sustain at the current settings. */
    public double capacityRps() {
        return workerCount * 1000.0 / serviceTimeMs;
    }

    public int workerCount() {
        return workerCount;
    }

    public long serviceTimeMs() {
        return serviceTimeMs;
    }

    public long appTimeMs() {
        return appTimeMs;
    }

    /** Change dependency latency mid-run (the "slow dependency" scenario). */
    public void setServiceTimeMs(long serviceTimeMs) {
        this.serviceTimeMs = serviceTimeMs;
    }

    public void setAppTimeMs(long appTimeMs) {
        this.appTimeMs = appTimeMs;
    }

    /**
     * Execute one dependency call: queue for a worker, then occupy it for the
     * current service time.
     */
    public void call() throws InterruptedException {
        Semaphore s = workers;
        s.acquire();
        try {
            sleep(serviceTimeMs);
        } finally {
            s.release();
        }
    }

    /** Simulate in-process work that does not touch the dependency. */
    public void applicationWork() throws InterruptedException {
        sleep(appTimeMs);
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0) {
            TimeUnit.MILLISECONDS.sleep(ms);
        }
    }
}
