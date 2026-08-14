package com.iasr.benchmark.app;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * The dependency serves best at {@link #workerCount} concurrent requests. Below
 * that, service time is constant. Above it, service time grows <em>faster than
 * linearly</em>, so throughput does not merely plateau — it declines:
 * <pre>
 *   load    = concurrent / workers
 *   service = base × load × (1 + thrash × (load − 1))     for load &gt; 1
 *   throughput(load) = peak / (1 + thrash × (load − 1))
 * </pre>
 *
 * <h3>Why the decline matters</h3>
 * An earlier version of this model capped concurrency with a semaphore, so
 * excess requests queued and throughput stayed flat past the optimum. Under
 * those rules over-provisioning is free, the optimal pool policy is trivially
 * "take the maximum", and no coordination algorithm can demonstrate value —
 * which is exactly what the measurements showed: a static pool of 20 beat
 * adaptive control of a pool of 10 in every scenario.
 * <p>
 * That was a property of the model, not of databases. Connections cost memory
 * on the server, a shared database cannot grant every client its maximum, and
 * throughput genuinely falls once concurrency passes the point where context
 * switching and lock contention dominate — the well-known result behind every
 * "size your pool small" recommendation, HikariCP's own included. With the
 * decline modelled, over-provisioning has a price and sizing the pool becomes
 * a real decision rather than a formality.
 * <p>
 * Setting {@code thrash} to zero restores the previous flat-throughput
 * behaviour, so results measured under both models can be compared.
 */
public class BackendModel {

    private volatile int workerCount;
    private volatile long serviceTimeMs;
    private volatile long appTimeMs;
    private volatile double thrashFactor;

    /**
     * When set, dependency calls run a real CPU-bound query against this
     * database instead of the analytic model.
     * <p>
     * The model exists so capacity is knowable; a real database exists so the
     * evaluation is not open to "the dependency was invented by the author".
     * Both are kept: the model reproduces earlier results, the database
     * produces defensible ones. Calibration showed the two disagree in degree —
     * the model's default penalty for over-provisioning was roughly an order of
     * magnitude harsher than PostgreSQL's — which is precisely the sort of
     * thing an assumed model gets wrong invisibly.
     */
    private volatile DataSource dataSource;

    /**
     * Rows of generated series per millisecond of query time, from calibration.
     * Lets scenarios keep expressing dependency cost in milliseconds while the
     * real backend needs a row count.
     */
    private volatile long rowsPerMs = 9_200;

    /** Requests currently inside the dependency. */
    private final AtomicInteger active = new AtomicInteger();

    public BackendModel(int workers, long serviceTimeMs, long appTimeMs) {
        this(workers, serviceTimeMs, appTimeMs, 0.0);
    }

    public BackendModel(int workers, long serviceTimeMs, long appTimeMs, double thrashFactor) {
        this.workerCount = workers;
        this.serviceTimeMs = serviceTimeMs;
        this.appTimeMs = appTimeMs;
        this.thrashFactor = thrashFactor;
    }

    /** Peak throughput, reached at exactly {@link #workerCount} concurrent requests. */
    public double capacityRps() {
        return workerCount * 1000.0 / serviceTimeMs;
    }

    /** Concurrency at which the dependency is fastest; more than this costs throughput. */
    public int optimalConcurrency() {
        return workerCount;
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

    public double thrashFactor() {
        return thrashFactor;
    }

    /** Change dependency latency mid-run (the "slow dependency" scenario). */
    public void setServiceTimeMs(long serviceTimeMs) {
        this.serviceTimeMs = serviceTimeMs;
    }

    public void setAppTimeMs(long appTimeMs) {
        this.appTimeMs = appTimeMs;
    }

    /**
     * Service time for a request entering while {@code concurrent} requests are
     * in the dependency.
     * <p>
     * Evaluated once on entry rather than continuously. A request that arrives
     * during a burst pays the burst's price for its whole stay, which overstates
     * the penalty slightly and understates it for one that arrives early — the
     * simplification is symmetric and keeps the model analytically tractable.
     */
    long serviceTimeFor(int concurrent) {
        double load = (double) concurrent / workerCount;
        if (load <= 1.0) {
            return serviceTimeMs;
        }
        return Math.round(serviceTimeMs * load * (1.0 + thrashFactor * (load - 1.0)));
    }

    /** Throughput the dependency sustains at the given concurrency, for reference. */
    public double throughputAt(int concurrent) {
        return concurrent * 1000.0 / serviceTimeFor(concurrent);
    }

    public void useRealDatabase(DataSource dataSource, long rowsPerMs) {
        this.dataSource = dataSource;
        this.rowsPerMs = rowsPerMs;
    }

    public boolean isReal() {
        return dataSource != null;
    }

    /**
     * Execute one dependency call.
     * <p>
     * Against a real database the crowding penalty is not simulated — it
     * emerges, because concurrent queries genuinely contend for the same cores.
     */
    public void call() throws Exception {
        DataSource ds = dataSource;
        if (ds == null) {
            int concurrent = active.incrementAndGet();
            try {
                sleep(serviceTimeFor(concurrent));
            } finally {
                active.decrementAndGet();
            }
            return;
        }
        long rows = Math.max(1, serviceTimeMs * rowsPerMs);
        try (Connection c = ds.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "select sum(i) from generate_series(1, ?) i")) {
            st.setLong(1, rows);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
            }
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
