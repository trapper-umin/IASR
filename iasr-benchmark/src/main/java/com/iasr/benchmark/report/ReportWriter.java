package com.iasr.benchmark.report;

import com.iasr.benchmark.load.Scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes the raw measurements and a human-readable report. */
public class ReportWriter {

    private final Path outputDir;
    private final double sloMs;

    public ReportWriter(Path outputDir, double sloMs) {
        this.outputDir = outputDir;
        this.sloMs = sloMs;
    }

    public void write(List<Scenario> scenarios, List<RunResult> results) throws IOException {
        Files.createDirectories(outputDir);
        Files.createDirectories(outputDir.resolve("raw"));

        writeSummaryCsv(results);
        writeActuatorTraces(results);
        writeDatasets(results);
        writeReport(scenarios, results);
    }

    private void writeSummaryCsv(List<RunResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("scenario,mode,dispatched,succeeded,rejected_429,server_errors,client_timeouts,"
                + "goodput_rps,p50_ms,p95_ms,p99_ms,max_ms,slo_breach_pct,rejection_pct,hard_failure_pct\n");
        for (RunResult r : results) {
            sb.append(String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f%n",
                    r.scenario(), r.mode(), r.dispatched(), r.succeeded(), r.rejected429(),
                    r.serverErrors(), r.clientTimeouts(), r.goodputRps(), r.p50Ms(), r.p95Ms(),
                    r.p99Ms(), r.maxMs(), r.sloBreachPercent(), r.rejectionRatePercent(),
                    r.failureRatePercent()));
        }
        Files.writeString(outputDir.resolve("results.csv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeActuatorTraces(List<RunResult> results) throws IOException {
        StringBuilder sb = new StringBuilder("scenario,mode,t_sec,concurrency_limit,hikari_max_pool,inflight\n");
        for (RunResult r : results) {
            for (RunResult.ActuatorSample s : r.actuatorTrace()) {
                sb.append(String.format(Locale.ROOT, "%s,%s,%.1f,%d,%d,%d%n",
                        r.scenario(), r.mode(), s.elapsedSec(), s.concurrencyLimit(),
                        s.hikariMaxPool(), s.inflight()));
            }
        }
        Files.writeString(outputDir.resolve("raw/actuator-trace.csv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeDatasets(List<RunResult> results) throws IOException {
        for (RunResult r : results) {
            if (r.datasetLines().isEmpty()) {
                continue;
            }
            Path file = outputDir.resolve("raw/dataset-" + r.scenario() + "-" + r.mode() + ".jsonl");
            Files.writeString(file, String.join("\n", r.datasetLines()) + "\n", StandardCharsets.UTF_8);
        }
    }

    private void writeReport(List<Scenario> scenarios, List<RunResult> results) throws IOException {
        Map<String, List<RunResult>> byScenario = results.stream()
                .collect(Collectors.groupingBy(RunResult::scenario, java.util.LinkedHashMap::new, Collectors.toList()));

        StringBuilder md = new StringBuilder();
        md.append("# IASR — результаты нагрузочного стенда\n\n");
        md.append("Прогон от ").append(LocalDate.now()).append(". ");
        md.append("Сгенерировано `iasr-benchmark`, воспроизводится командой в конце документа.\n\n");

        md.append("## Как читать\n\n");
        md.append("Сравнение, ради которого всё затевалось, — **fixed против adaptive**. ")
                .append("В обоих режимах работает один и тот же лимитер с одинаковым стартовым значением, ")
                .append("поэтому разница между ними относится к управляющему контуру, а не к самому факту ")
                .append("наличия лимитера. Режим **off** показывает отдельно, чего стоит admission control вообще.\n\n");
        md.append("Латентность измеряется от момента, когда запрос **должен был** быть отправлен, ")
                .append("а не когда фактически ушёл: иначе отставание клиента улучшало бы перцентили ")
                .append("ровно тогда, когда системе плохо. Перцентили считаются только по успешным ответам, ")
                .append("поэтому доля 429 и доля жёстких отказов приводятся рядом — без них ")
                .append("картина неполна, а режим с агрессивным шеддингом выглядел бы незаслуженно хорошо.\n\n");
        md.append("SLO: **p95 ≤ ").append((long) sloMs).append(" мс**.\n\n");

        md.append("## Сводка\n\n");
        md.append("| Сценарий | Режим | Goodput, rps | p95, мс | p99, мс | 429 | Жёсткие отказы | Нарушений SLO |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (RunResult r : results) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %.1f | %.1f | %.1f | %.1f%% | %.1f%% | %.1f%% |%n",
                    r.scenario(), r.mode(), r.goodputRps(), r.p95Ms(), r.p99Ms(),
                    r.rejectionRatePercent(), r.failureRatePercent(), r.sloBreachPercent()));
        }
        md.append("\n");

        for (Scenario scenario : scenarios) {
            List<RunResult> runs = byScenario.get(scenario.name());
            if (runs == null) {
                continue;
            }
            md.append("## Сценарий `").append(scenario.name()).append("`\n\n");
            md.append("_").append(scenario.question()).append("_\n\n");
            md.append("| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |\n");
            md.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
            for (RunResult r : runs) {
                md.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f%% | %.1f%% |%n",
                        r.mode(), r.goodputRps(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs(),
                        r.rejectionRatePercent(), r.failureRatePercent()));
            }
            md.append("\n");

            RunResult adaptive = runs.stream().filter(r -> r.mode().equals("adaptive")).findFirst().orElse(null);
            if (adaptive != null && !adaptive.actuatorTrace().isEmpty()) {
                md.append("Траектория актюаторов в режиме `adaptive`:\n\n");
                md.append("| t, с | concurrency_limit | hikari_max_pool | inflight |\n|---:|---:|---:|---:|\n");
                List<RunResult.ActuatorSample> trace = adaptive.actuatorTrace();
                int stride = Math.max(1, trace.size() / 15);
                for (int i = 0; i < trace.size(); i += stride) {
                    RunResult.ActuatorSample s = trace.get(i);
                    md.append(String.format(Locale.ROOT, "| %.1f | %d | %d | %d |%n",
                            s.elapsedSec(), s.concurrencyLimit(), s.hikariMaxPool(), s.inflight()));
                }
                md.append("\n");
            }
        }

        md.append("## Файлы\n\n");
        md.append("- `results.csv` — сводка по всем прогонам\n");
        md.append("- `raw/actuator-trace.csv` — значения актюаторов во времени\n");
        md.append("- `raw/dataset-<сценарий>-<режим>.jsonl` — датасет IASR, тот самый формат `state → action → outcome`\n\n");

        md.append("## Воспроизведение\n\n");
        md.append("```bash\n");
        md.append("mvn -q install -DskipTests && mvn -pl iasr-benchmark exec:java\n");
        md.append("```\n");

        Files.writeString(outputDir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
