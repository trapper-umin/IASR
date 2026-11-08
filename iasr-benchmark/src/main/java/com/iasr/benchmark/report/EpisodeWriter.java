package com.iasr.benchmark.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iasr.core.dataset.DatasetRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Checkpoint every completed repetition; metadata is the completion marker, written last. */
public final class EpisodeWriter {
    private final Path directory;
    private final String runId;
    private final long orderSeed;
    private final Map<String, Object> source;
    private final ObjectMapper json = new ObjectMapper();

    public EpisodeWriter(Path output, String runId, long orderSeed, Map<String, Object> source) throws IOException {
        this.runId = safeName(runId);
        this.orderSeed = orderSeed;
        this.source = Map.copyOf(source);
        Path parent = output.resolve("episodes");
        Files.createDirectories(parent);
        directory = parent.resolve(this.runId);
        Files.createDirectory(directory); // A repeated run ID must never overwrite an earlier experiment.
        writeJson(directory.resolve("manifest.json"), Map.of(
                "metadataVersion", 1, "datasetSchemaVersion", DatasetRecord.SCHEMA_VERSION,
                "runId", runId, "orderSeed", orderSeed,
                "createdAt", Instant.now().toString(), "source", source));
    }

    public Path directory() { return directory; }

    public Path write(RunResult result, int repetition, Map<String, Object> details) throws IOException {
        if (repetition < 1) throw new IllegalArgumentException("repetition must be positive");
        Path parent = directory.resolve(safeName(result.scenario())).resolve(safeName(result.mode()));
        Files.createDirectories(parent);
        Path episode = parent.resolve(String.format(java.util.Locale.ROOT, "repeat-%04d", repetition));
        Files.createDirectory(episode);

        Files.write(episode.resolve("dataset.jsonl"), result.datasetLines(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        StringBuilder trace = new StringBuilder("t_sec,concurrency_limit,hikari_max_pool,inflight\n");
        for (RunResult.ActuatorSample s : result.actuatorTrace()) {
            trace.append(String.format(java.util.Locale.ROOT, "%.1f,%d,%d,%d%n",
                    s.elapsedSec(), s.concurrencyLimit(), s.hikariMaxPool(), s.inflight()));
        }
        Files.writeString(episode.resolve("actuators.csv"), trace, StandardOpenOption.CREATE_NEW);

        Map<String, Object> metrics = new LinkedHashMap<>(json.convertValue(result,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        metrics.remove("datasetLines");
        metrics.remove("actuatorTrace");
        metrics.put("rejectionRatePercent", result.rejectionRatePercent());
        metrics.put("failureRatePercent", result.failureRatePercent());
        writeJson(episode.resolve("result.json"), metrics);

        Map<String, Object> metadata = new LinkedHashMap<>(details);
        metadata.put("metadataVersion", 1);
        metadata.put("datasetSchemaVersion", DatasetRecord.SCHEMA_VERSION);
        metadata.put("runId", runId);
        metadata.put("episodeId", result.scenario() + "/" + result.mode() + "/" + repetition);
        metadata.put("scenario", result.scenario());
        metadata.put("mode", result.mode());
        metadata.put("repetition", repetition);
        metadata.put("orderSeed", orderSeed);
        metadata.put("source", source);
        metadata.put("datasetRecords", result.datasetLines().size());
        metadata.put("status", "complete");
        writeJson(episode.resolve("metadata.json"), metadata);
        return episode;
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid experiment path component: " + name);
        }
        return name;
    }
}
