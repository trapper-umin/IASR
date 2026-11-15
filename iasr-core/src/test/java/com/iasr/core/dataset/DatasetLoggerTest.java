package com.iasr.core.dataset;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the state -> action -> outcome timing protocol: the record for tick
 * t is only emitted at tick t+1, once the next window's metrics are known.
 */
class DatasetLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger datasetLogger;

    @BeforeEach
    void attachAppender() {
        datasetLogger = (Logger) LoggerFactory.getLogger(DatasetLogger.LOGGER_NAME);
        datasetLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        datasetLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        datasetLogger.detachAppender(appender);
        appender.stop();
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static MetricsSnapshot state(double latency) {
        return MetricsSnapshot.builder().put("latency_p95_ms", latency).build();
    }

    private static ControlAction action(int limit) {
        return ControlAction.builder().set("concurrency_limit", limit).build();
    }

    @Test
    @DisplayName("writes nothing on the first tick — the outcome is not known yet")
    void firstTickIsBuffered() {
        DatasetLogger logger = new DatasetLogger(5.0);

        logger.recordTick(state(10), action(100));

        assertThat(lines()).isEmpty();
    }

    @Test
    @DisplayName("emits tick t at tick t+1, using the next window's state as its outcome")
    void outcomeComesFromTheFollowingTick() {
        DatasetLogger logger = new DatasetLogger(5.0);

        logger.recordTick(state(10), action(100));
        logger.recordTick(state(20), action(105));

        assertThat(lines()).hasSize(1);
        String line = lines().get(0);
        assertThat(line).contains("\"state\":{\"latency_p95_ms\":10}");
        assertThat(line).contains("\"action\":{\"concurrency_limit\":100}");
        assertThat(line).contains("\"outcome\":{\"latency_p95_ms\":20}");
    }

    @Test
    @DisplayName("flush emits the trailing record with a null outcome")
    void flushWritesTheLastRecord() {
        DatasetLogger logger = new DatasetLogger(5.0);

        logger.recordTick(state(10), action(100));
        logger.recordTick(state(20), action(105));
        logger.flush();

        assertThat(lines()).hasSize(2);
        assertThat(lines().get(1)).contains("\"outcome\":null");
    }

    @Test
    @DisplayName("flush is idempotent")
    void flushTwiceWritesOnce() {
        DatasetLogger logger = new DatasetLogger(5.0);

        logger.recordTick(state(10), action(100));
        logger.flush();
        logger.flush();

        assertThat(lines()).hasSize(1);
    }

    @Test
    @DisplayName("records the baseline proposal next to the intent only when the controller reports one")
    void baselineProposalIsRecordedSeparately() {
        DatasetLogger logger = new DatasetLogger(5.0);
        ControlAction deviation = ControlAction.builder().set("concurrency_limit", 80, "coordinated_policy")
                .baseline(Map.of("concurrency_limit", 100)).build();
        ControlAction kept = ControlAction.builder().baseline(Map.of()).build();

        logger.recordTick(state(10), action(80), deviation, Map.of(), Map.of(), java.time.Instant.now());
        logger.recordTick(state(20), action(80), kept, Map.of(), Map.of(), java.time.Instant.now());
        logger.recordTick(state(30), action(80), action(80), Map.of(), Map.of(), java.time.Instant.now());
        logger.flush();

        assertThat(lines()).hasSize(3);
        assertThat(lines().get(0)).contains("\"intent\":{\"concurrency_limit\":80},\"baselineIntent\":{\"concurrency_limit\":100}");
        assertThat(lines().get(1)).contains("\"baselineIntent\":{}");
        assertThat(lines().get(2)).doesNotContain("baselineIntent");
    }

    @Test
    @DisplayName("a null action is recorded as an empty object rather than failing")
    void toleratesNullAction() {
        DatasetLogger logger = new DatasetLogger(5.0);

        logger.recordTick(state(10), null);
        logger.flush();

        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("\"action\":{}");
    }

    @Test
    @DisplayName("each emitted line is a single line of JSONL")
    void everyRecordIsOneLine() {
        DatasetLogger logger = new DatasetLogger(5.0);

        logger.recordTick(state(10), action(100));
        logger.recordTick(state(20), action(105));
        logger.flush();

        assertThat(lines()).allSatisfy(line -> {
            assertThat(line).doesNotContain("\n");
            assertThat(line).startsWith("{").endsWith("}");
        });
    }

    @Test
    void recordsPreActionContextAndExactWindowBoundaries() {
        DatasetLogger logger = new DatasetLogger(5.0);
        Instant first = Instant.parse("2026-11-08T10:00:00Z");
        Instant second = first.plusSeconds(6);
        logger.recordTick(state(10), action(105), action(120),
                Map.of("concurrency_limit", 100), Map.of("concurrency_limit", 0), first);
        logger.recordTick(state(20), action(105), action(120),
                Map.of("concurrency_limit", 105), Map.of("concurrency_limit", 2), second);
        logger.flush();

        assertThat(lines().get(0)).contains("\"sequence\":0", "\"stateWindowStartedAt\":null",
                "\"outcomeTimestamp\":\"2026-11-08T10:00:06Z\"",
                "\"resourcesBefore\":{\"concurrency_limit\":100}");
        assertThat(lines().get(1)).contains("\"sequence\":1",
                "\"stateWindowStartedAt\":\"2026-11-08T10:00:00Z\"",
                "\"cooldownRemaining\":{\"concurrency_limit\":2}", "\"outcome\":null");
    }
}
