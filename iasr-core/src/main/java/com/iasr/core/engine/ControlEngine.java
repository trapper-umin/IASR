package com.iasr.core.engine;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.config.ControlEngineConfig;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.dataset.DatasetLogger;
import com.iasr.core.guardrail.Guardrails;
import com.iasr.core.metrics.MetricsProvider;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The runtime control loop that periodically:
 * <ol>
 *   <li>Collects metrics via {@link MetricsProvider}</li>
 *   <li>Passes them to the {@link Controller}</li>
 *   <li>Applies {@link Guardrails} to the resulting {@link ControlAction}</li>
 *   <li>Executes the clamped action on registered {@link Actuator}s</li>
 *   <li>Logs the JSONL dataset record via {@link DatasetLogger}</li>
 * </ol>
 *
 * <p>The loop runs on a single-thread {@link ScheduledExecutorService} with
 * fixed-rate scheduling at the configured window period (Δt).
 *
 * <p>Lifecycle: call {@link #start()} after construction and {@link #stop()}
 * to shut down.  The engine is not reusable after stopping.
 */
@Slf4j
public class ControlEngine {

    @Getter
    private final ControlEngineConfig config;
    private final MetricsProvider metricsProvider;
    private final Controller controller;
    @Getter
    private final List<Actuator> actuators;
    private final Guardrails guardrails;
    private final DatasetLogger datasetLogger;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> tickFuture;

    public ControlEngine(ControlEngineConfig config) {
        this.config = config;
        this.metricsProvider = config.metricsProvider();
        this.controller = config.controller();
        this.actuators = config.actuators();
        this.guardrails = new Guardrails(config.guardrails(), config.sloLatencyMs());
        this.datasetLogger = config.datasetLoggingEnabled()
                ? new DatasetLogger(config.windowSec())
                : null;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iasr-control-loop");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start the control loop. Idempotent — calling twice has no effect. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            long windowMs = config.windowMs();
            tickFuture = scheduler.scheduleAtFixedRate(
                    this::tick, windowMs, windowMs, TimeUnit.MILLISECONDS);
            log.info("ControlEngine started: window={}ms, SLO={}ms, enabled={}, actuators={}",
                    windowMs, config.sloLatencyMs(), config.enabled(), actuators.size());
        }
    }

    /** Stop the control loop and flush the dataset. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (tickFuture != null) {
                tickFuture.cancel(false);
            }
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            if (datasetLogger != null) {
                datasetLogger.flush();
            }
            log.info("ControlEngine stopped");
        }
    }

    /** Returns {@code true} if the control loop is running. */
    public boolean isRunning() {
        return running.get();
    }

    // ── The control loop tick ────────────────────────────────────────────

    private void tick() {
        try {
            // 1. Collect metrics
            MetricsSnapshot snapshot = metricsProvider.snapshot();
            if (snapshot == null) {
                log.warn("MetricsProvider returned null snapshot, skipping tick");
                return;
            }

            ControlAction action = ControlAction.NONE;

            if (config.enabled()) {
                // 2. Controller decision
                ControlAction rawAction = controller.decide(snapshot, actuators);

                // 3. Guardrails
                action = guardrails.enforce(rawAction, actuators, snapshot);

                // 4. Apply actions
                applyAction(action);
            }

            // 5. Dataset logging (even when disabled — records metrics for ML)
            if (datasetLogger != null) {
                datasetLogger.recordTick(snapshot, action);
            }

        } catch (Exception e) {
            log.error("ControlEngine tick failed", e);
        }
    }

    private void applyAction(ControlAction action) {
        if (action.isEmpty()) return;

        for (Actuator actuator : actuators) {
            int desired = action.getOrDefault(actuator.name(), actuator.currentValue());
            if (desired != actuator.currentValue()) {
                try {
                    actuator.apply(desired);
                } catch (Exception e) {
                    log.error("Failed to apply action on actuator [{}]: {} → {}",
                            actuator.name(), actuator.currentValue(), desired, e);
                }
            }
        }
    }

}
