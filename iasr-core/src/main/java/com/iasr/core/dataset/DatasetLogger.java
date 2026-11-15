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
    private long nextSequence;
    private Instant previousObservation;

    /**
     * Record state and action for the current tick.
     * Also completes and logs the <em>previous</em> tick record (by attaching
     * current state as its outcome).
     *
     * @param snapshot current window metrics
     * @param action   control action applied at this tick
     */
    public synchronized void recordTick(MetricsSnapshot snapshot, ControlAction action) {
        recordTick(snapshot, action, null);
    }

    /**
     * Record state, the action actually applied, and what the controller
     * intended before the guardrails saw it.
     * <p>
     * Both halves are needed. Training on the applied action alone teaches the
     * guardrail rather than the policy, and the labels silently become wrong
     * the moment the guardrail is retuned. The reasons attached to
     * {@code intent} say which rule fired and what became of it.
     *
     * @param intent the controller's raw decision, or {@code null} when the
     *               engine is not applying actions at all
     */
    public synchronized void recordTick(MetricsSnapshot snapshot,
                                        ControlAction action,
                                        ControlAction intent) {
        recordTick(snapshot, action, intent, Map.of(), Map.of(), Instant.now());
    }

    public synchronized void recordTick(MetricsSnapshot snapshot, ControlAction action,
                                        ControlAction intent, Map<String, Integer> resourcesBefore,
                                        Map<String, Integer> cooldownRemaining, Instant observedAt) {
        Map<String, Double> stateMap = snapshot.asMap();

        // Complete the previous record
        if (pending != null) {
            pending.completeOutcome(stateMap, observedAt);
            writeLine(pending);
        }

        // Build the new record (outcome will be filled at t+1)
        Map<String, Integer> actionMap = action != null ? action.desiredValues() : Map.of();
        Map<String, Integer> intentMap = intent != null ? intent.desiredValues() : Map.of();
        Map<String, String> reasons = intent != null ? intent.reasons() : Map.of();
        Map<String, Integer> baselineMap = intent != null ? intent.baselineValues().orElse(null) : null;
        pending = new DatasetRecord(observedAt, windowSec, stateMap, actionMap,
                intentMap, reasons, nextSequence++, previousObservation, resourcesBefore, cooldownRemaining,
                baselineMap);
        previousObservation = observedAt;
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
