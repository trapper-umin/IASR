package com.iasr.benchmark.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpisodeWriterTest {
    @TempDir Path output;
    private final ObjectMapper json = new ObjectMapper();

    private static RunResult result(String line) {
        return new RunResult("adaptive", "spike", 1000, 10, 8, 2, 0, 0, 0,
                8, 1, 2, 3, 4, 0,
                List.of(new RunResult.ActuatorSample(0, 10, 4, 0)), List.of(line));
    }

    @Test
    void preservesEveryRepetitionAndItsProvenance() throws Exception {
        EpisodeWriter writer = new EpisodeWriter(output, "run-1", 42, Map.of("commit", "abc", "dirty", false));
        Path first = writer.write(result("{\"sequence\":0}"), 1, Map.of("configuration", Map.of("sloMs", 150)));
        Path second = writer.write(result("{\"sequence\":1}"), 2, Map.of());

        assertThat(Files.readString(first.resolve("dataset.jsonl"))).contains("\"sequence\":0");
        assertThat(Files.readString(second.resolve("dataset.jsonl"))).contains("\"sequence\":1");
        var metadata = json.readTree(first.resolve("metadata.json").toFile());
        assertThat(metadata.get("repetition").asInt()).isEqualTo(1);
        assertThat(metadata.get("status").asText()).isEqualTo("complete");
        assertThat(metadata.get("source").get("commit").asText()).isEqualTo("abc");
        assertThat(metadata.get("configuration").get("sloMs").asInt()).isEqualTo(150);
        var summary = json.readTree(first.resolve("result.json").toFile());
        assertThat(summary.get("goodputRps").asDouble()).isEqualTo(8);
        assertThat(summary.has("datasetLines")).isFalse();
        assertThat(Files.readString(first.resolve("actuators.csv"))).contains("0.0,10,4,0");
    }

    @Test
    void refusesToOverwriteAnEpisodeOrCollection() throws Exception {
        EpisodeWriter writer = new EpisodeWriter(output, "run-1", 42, Map.of());
        Path first = writer.write(result("original"), 1, Map.of());

        assertThatThrownBy(() -> writer.write(result("overwritten"), 1, Map.of()))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThatThrownBy(() -> new EpisodeWriter(output, "run-1", 42, Map.of()))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(first.resolve("dataset.jsonl"))).isEqualTo("original\n");
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> new EpisodeWriter(output, "../escape", 42, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
