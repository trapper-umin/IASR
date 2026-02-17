package com.iasr.core.actuator;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Actuator that adjusts {@code maximumPoolSize} of a HikariCP connection pool
 * at runtime via {@code HikariConfigMXBean}, without recreating the DataSource.
 * <p>
 * The {@link HikariDataSource} instance is provided externally (e.g. injected
 * from the Spring context or passed programmatically).
 *
 * <h3>Safety</h3>
 * HikariCP documentation states that changing {@code maximumPoolSize} through
 * JMX/MXBean is a supported hot-config operation.  The pool will gradually
 * converge to the new size as connections are returned or evicted.
 */
@Slf4j
public class HikariPoolActuator implements Actuator {

    public static final String ACTUATOR_NAME = "hikari_max_pool";

    private final HikariDataSource dataSource;
    private final int minPoolSize;
    private final int maxPoolSize;

    /**
     * @param dataSource  externally managed HikariDataSource
     * @param minPoolSize hard floor for pool size
     * @param maxPoolSize hard ceiling for pool size
     */
    public HikariPoolActuator(HikariDataSource dataSource, int minPoolSize, int maxPoolSize) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (minPoolSize < 1) throw new IllegalArgumentException("minPoolSize must be >= 1");
        if (maxPoolSize < minPoolSize) throw new IllegalArgumentException("maxPoolSize must be >= minPoolSize");
        this.dataSource = dataSource;
        this.minPoolSize = minPoolSize;
        this.maxPoolSize = maxPoolSize;
    }

    @Override
    public String name() {
        return ACTUATOR_NAME;
    }

    @Override
    public int currentValue() {
        return dataSource.getMaximumPoolSize();
    }

    @Override
    public void apply(int newValue) {
        int clamped = Math.max(minPoolSize, Math.min(newValue, maxPoolSize));
        int prev = dataSource.getMaximumPoolSize();
        if (clamped != prev) {
            // HikariConfigMXBean — HikariDataSource extends HikariConfig
            // which implements HikariConfigMXBean; setMaximumPoolSize is hot-config safe.
            dataSource.setMaximumPoolSize(clamped);
            log.info("HikariPoolActuator: maximumPoolSize {} → {}", prev, clamped);
        }
    }

    @Override
    public int minValue() {
        return minPoolSize;
    }

    @Override
    public int maxValue() {
        return maxPoolSize;
    }
}
