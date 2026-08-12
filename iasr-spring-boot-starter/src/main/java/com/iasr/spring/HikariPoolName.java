package com.iasr.spring;

import java.util.function.Supplier;

/**
 * Carries a way to look up the actual HikariCP pool name, from the
 * Hikari-specific configuration to the metrics provider.
 * <p>
 * Micrometer tags every {@code hikaricp.*} meter with the pool's name, so a
 * reader that guesses the name finds nothing.  Guessing is easy to get wrong:
 * the default is only {@code HikariPool-N} when the application does not set
 * {@code spring.datasource.hikari.pool-name}, and the counter increments per
 * pool created in the JVM.  A mismatch is silent — every Hikari metric reads
 * zero, so the pool controller sees no pending threads, never acts, and the
 * dataset records a column of zeros.
 * <p>
 * A {@link Supplier} rather than a plain string, because HikariCP assigns a
 * generated name only when the pool actually starts: read during context
 * startup the name is frequently still {@code null}.
 * <p>
 * This holder exists so the outer auto-configuration can learn the name without
 * a compile-time reference to {@code HikariDataSource}, which must stay confined
 * to the class-guarded inner configuration.
 *
 * @param resolver supplies the pool name of the live {@code HikariDataSource}
 */
public record HikariPoolName(Supplier<String> resolver) {
}
