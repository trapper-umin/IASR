package com.iasr.benchmark.app;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * The application under test: a single endpoint that consumes a database
 * connection and a slice of dependency capacity, which is enough to exercise
 * both IASR actuators (request concurrency and pool size).
 */
@SpringBootApplication
public class BenchmarkApplication {

    @Bean
    public BackendModel backendModel() {
        return new BackendModel(
                Integer.getInteger("bench.db.workers", 12),
                Long.getLong("bench.db.serviceMs", 50L),
                Long.getLong("bench.app.workMs", 5L));
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @RestController
    public static class WorkloadController {

        private final HikariDataSource dataSource;
        private final BackendModel backend;

        public WorkloadController(DataSource dataSource, BackendModel backend) {
            this.dataSource = (HikariDataSource) dataSource;
            this.backend = backend;
        }

        /**
         * One unit of business work.
         * <p>
         * The connection is held for the whole dependency call, which is what
         * makes pool size a real constraint: with N connections, at most N
         * requests can be in the dependency at once, so a pool smaller than the
         * dependency's worker count caps throughput below the dependency's own
         * capacity.
         */
        @GetMapping("/api/work")
        public String work() throws Exception {
            try (Connection connection = dataSource.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
                backend.call();
            }
            backend.applicationWork();
            return "ok";
        }
    }
}
