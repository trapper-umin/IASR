package com.iasr.core.dataset;

import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;

/**
 * Logs one JSONL line per control-loop tick to a dedicated SLF4J logger
 * ({@value #LOGGER_NAME}).
 * <p>
 * The user directs this logger to a separate file (e.g. via Logback or
 * Log4j2 configuration) so the dataset does not pollute regular application
 * logs.
 *
 * <h3>Timing protocol</h3>
 * <ol>
 *   <li>At tick <i>t</i>, engine calls {@link #recordTick} with state_t and action_t.</li>
 *   <li>At tick <i>t+1</i>, before recording the new tick, engine calls
 *       {@link #recordTick} again — the method fills outcome_t with state_{t+1}
 *       and flushes the previous record.</li>
 *   <li>On shutdown, {@link #flush()} writes the last record with {@code outcome=null}.</li>
 * </ol>
 */
@Slf4j(topic = DatasetLogger.LOGGER_NAME)
@RequiredArgsConstructor
public class DatasetLogger {

    public static final String LOGGER_NAME = "softres.dataset";

    private final double windowSec;

    /** Previous tick record waiting for its outcome. */
    private volatile DatasetRecord pending;

    /**
     * Record state and action for the current tick.
     * Also completes and logs the <em>previous</em> tick record (by attaching
     * current state as its outcome).
     *
     * @param snapshot current window metrics
     * @param action   control action applied at this tick
     */
    public synchronized void recordTick(MetricsSnapshot snapshot, ControlAction action) {
        Map<String, Double> stateMap = snapshot.asMap();

        // Complete the previous record
        if (pending != null) {
            pending.setOutcome(stateMap);
            writeLine(pending);
        }

        // Build the new record (outcome will be filled at t+1)
        Map<String, Integer> actionMap = action != null ? action.desiredValues() : Map.of();
        pending = new DatasetRecord(Instant.now(), windowSec, stateMap, actionMap);
    }

    /**
     * Flush the last pending record (with {@code outcome=null}).
     * Called on engine shutdown.
     */
    public synchronized void flush() {
        if (pending != null) {
            writeLine(pending);
            pending = null;
        }
    }

    private void writeLine(DatasetRecord record) {
        if (log.isInfoEnabled()) {
            log.info(record.toJsonLine());
        }
    }
}
