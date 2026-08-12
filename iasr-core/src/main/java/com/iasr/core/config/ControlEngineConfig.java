package com.iasr.core.config;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.BaselineController;
import com.iasr.core.controller.Controller;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricsProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for {@link com.iasr.core.engine.ControlEngine}.
 * <p>
 * Use the {@link Builder} to construct instances.
 */
public final class ControlEngineConfig {

    private final long windowMs;
    private final double sloLatencyMs;
    private final boolean applyActions;
    private final boolean datasetLoggingEnabled;
    private final MetricsProvider metricsProvider;
    private final Controller controller;
    private final List<Actuator> actuators;
    private final List<ActuatorGuardrail> guardrails;

    private ControlEngineConfig(Builder b) {
        this.windowMs = b.windowMs;
        this.sloLatencyMs = b.sloLatencyMs;
        this.applyActions = b.applyActions;
        this.datasetLoggingEnabled = b.datasetLoggingEnabled;
        this.metricsProvider = b.metricsProvider;
        this.controller = b.controller;
        this.actuators = Collections.unmodifiableList(new ArrayList<>(b.actuators));
        this.guardrails = Collections.unmodifiableList(new ArrayList<>(b.guardrails));
    }

    public long windowMs()               { return windowMs; }
    public double windowSec()             { return windowMs / 1000.0; }
    public double sloLatencyMs()          { return sloLatencyMs; }
    /**
     * When {@code false}, the engine runs in <b>observe-only</b> mode: it
     * collects metrics and writes the dataset, but never calls
     * {@link com.iasr.core.actuator.Actuator#apply(int)}.
     */
    public boolean applyActions()         { return applyActions; }

    /** @deprecated renamed to {@link #applyActions()} for clarity. */
    @Deprecated(since = "1.0.13", forRemoval = true)
    public boolean enabled()              { return applyActions; }

    public boolean datasetLoggingEnabled(){ return datasetLoggingEnabled; }
    public MetricsProvider metricsProvider(){ return metricsProvider; }
    public Controller controller()        { return controller; }
    public List<Actuator> actuators()     { return actuators; }
    public List<ActuatorGuardrail> guardrails(){ return guardrails; }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long windowMs = 5_000;
        private double sloLatencyMs = 200.0;
        private boolean applyActions = true;
        private boolean datasetLoggingEnabled = true;
        private MetricsProvider metricsProvider;
        private Controller controller;
        private final List<Actuator> actuators = new ArrayList<>();
        private final List<ActuatorGuardrail> guardrails = new ArrayList<>();

        private Builder() {}

        /** Control loop period in milliseconds (default 5000). */
        public Builder windowMs(long windowMs) {
            this.windowMs = windowMs;
            return this;
        }

        /** SLO target latency p95 in milliseconds (default 200). */
        public Builder sloLatencyMs(double sloLatencyMs) {
            this.sloLatencyMs = sloLatencyMs;
            return this;
        }

        /**
         * If {@code false}, the engine collects metrics and logs the dataset
         * but does NOT apply actions to actuators (observe-only mode).
         * Default {@code true}.
         */
        public Builder applyActions(boolean applyActions) {
            this.applyActions = applyActions;
            return this;
        }

        /** @deprecated renamed to {@link #applyActions(boolean)} for clarity. */
        @Deprecated(since = "1.0.13", forRemoval = true)
        public Builder enabled(boolean enabled) {
            return applyActions(enabled);
        }

        /** Enable or disable JSONL dataset logging (default true). */
        public Builder datasetLoggingEnabled(boolean enabled) {
            this.datasetLoggingEnabled = enabled;
            return this;
        }

        /** Required — the source of aggregated metrics. */
        public Builder metricsProvider(MetricsProvider metricsProvider) {
            this.metricsProvider = metricsProvider;
            return this;
        }

        /**
         * Optional — override the controller.
         * If not set, a {@link BaselineController} with the configured SLO is used.
         */
        public Builder controller(Controller controller) {
            this.controller = controller;
            return this;
        }

        /** Register an actuator (e.g. ConcurrencyLimiter, HikariPoolActuator). */
        public Builder addActuator(Actuator actuator) {
            this.actuators.add(actuator);
            return this;
        }

        /** Register a guardrail for the named actuator. */
        public Builder addGuardrail(ActuatorGuardrail guardrail) {
            this.guardrails.add(guardrail);
            return this;
        }

        public ControlEngineConfig build() {
            if (metricsProvider == null) {
                throw new IllegalStateException("metricsProvider is required");
            }
            if (controller == null) {
                controller = new BaselineController(sloLatencyMs);
            }
            return new ControlEngineConfig(this);
        }
    }
}
