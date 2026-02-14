package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.List;

/**
 * Strategy interface for the decision-making component of the control loop.
 * <p>
 * The engine invokes {@link #decide} once per tick, passing the freshly
 * collected metrics and the list of registered actuators.  The controller
 * returns a {@link ControlAction} describing which actuator values it wants
 * to change (if any).
 * <p>
 * The built-in implementation is {@link BaselineController} (AIMD-style
 * threshold logic).  The interface is designed so that an ML-based controller
 * can be a drop-in replacement without changing the public API.
 */
public interface Controller {

    /**
     * Compute the desired control action based on current metrics.
     *
     * @param snapshot  aggregated metrics of the current window
     * @param actuators the registered actuators (read-only; do NOT call {@code apply})
     * @return a non-null action; return {@link ControlAction#NONE} to skip this tick
     */
    ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators);
}
