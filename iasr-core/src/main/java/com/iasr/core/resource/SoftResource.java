package com.iasr.core.resource;

import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.metrics.MetricNames;

/**
 * Uniform description of a soft resource: a capacity that an actuator can
 * change at run time, together with the four signals that say how the
 * application is using it.
 * <p>
 * Every soft resource the library knows about — an admission limit, a
 * connection pool, a thread pool, a bounded queue — has the same shape: a
 * capacity, the part of it in use, the work waiting for it, how long that work
 * waits, and how long a unit of capacity stays occupied once granted. Naming
 * these signals once lets a policy reason about any resource without knowing
 * what it is, and lets a new resource join coordinated control by declaring
 * five strings instead of extending the policy.
 *
 * @param actuatorName  name of the {@link com.iasr.core.actuator.Actuator} that sets the capacity
 * @param usageMetric   units of capacity currently occupied
 * @param backlogMetric requests waiting for a unit of capacity
 * @param waitMetric    mean time a request waited for capacity, milliseconds
 * @param holdMetric    mean time a granted unit stays occupied, milliseconds
 */
public record SoftResource(String actuatorName, String usageMetric, String backlogMetric,
                           String waitMetric, String holdMetric) {

    public SoftResource {
        for (String value : new String[]{actuatorName, usageMetric, backlogMetric, waitMetric, holdMetric}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Soft resource signals must be named");
        }
    }

    /** Admission limit: permits, requests in service, the permit queue. */
    public static final SoftResource CONCURRENCY = new SoftResource(ConcurrencyLimiter.ACTUATOR_NAME,
            MetricNames.INFLIGHT, MetricNames.QUEUE_LENGTH,
            MetricNames.ADMISSION_WAIT_MEAN_MS, MetricNames.SERVICE_MEAN_MS);

    /** Connection pool: connections, those checked out, threads waiting for one. */
    public static final SoftResource CONNECTION_POOL = new SoftResource(HikariPoolActuator.ACTUATOR_NAME,
            MetricNames.HIKARI_ACTIVE, MetricNames.HIKARI_PENDING,
            MetricNames.HIKARI_ACQUIRE_MEAN_MS, MetricNames.DEPENDENCY_MEAN_MS);
}
