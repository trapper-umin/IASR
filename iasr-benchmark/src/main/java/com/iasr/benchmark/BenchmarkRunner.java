package com.iasr.benchmark;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iasr.benchmark.app.BackendModel;
import com.iasr.benchmark.baseline.GradientController;
import com.iasr.benchmark.app.BenchmarkApplication;
import com.iasr.benchmark.load.LoadGenerator;
import com.iasr.benchmark.load.Scenario;
import com.iasr.benchmark.report.ReportWriter;
import com.iasr.benchmark.report.EpisodeWriter;
import com.iasr.benchmark.report.RunResult;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.engine.ControlEngine;
import com.iasr.spring.IasrProperties;
import com.iasr.core.dataset.DatasetLogger;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs every scenario against every configuration and writes the evidence out.
 *
 * <h3>The three configurations</h3>
 * <ul>
 *   <li><b>off</b> — IASR disabled entirely: no admission control at all.</li>
 *   <li><b>fixed</b> — IASR present but in observe-only mode: the concurrency
 *       limiter enforces its initial value and never changes it.</li>
 *   <li><b>adaptive</b> — IASR fully active.</li>
 * </ul>
 *
 * The comparison that matters is <b>fixed vs adaptive</b>: both have the same
 * limiter with the same starting value, so any difference is attributable to the
 * control loop rather than to the mere presence of a limiter.  Comparing against
 * <b>off</b> separately shows what admission control is worth at all.  Measuring
 * only "IASR vs no IASR" would credit the control loop for the limiter's work.
 */
public class BenchmarkRunner {

    /**
     * Latency target the controller is asked to meet.  Configurable so the
     * tuning can be checked in other regimes: a coefficient that only wins at
     * one SLO and one dependency speed is fitted to this harness, not a
     * property of the algorithm.
     */
    private static final double SLO_MS =
            Double.parseDouble(System.getProperty("bench.sloMs", "150"));

    /** Deliberately non-default, to prove IASR reads the pool by its real name. */
    private static final String POOL_NAME = "bench-pool";

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Path outputDir = Path.of(System.getProperty("bench.out", "docs/benchmark"));
        List<Scenario> scenarios = selectScenarios();
        List<String> modes = selectModes();
        int repeats = Integer.getInteger("bench.repeat", 1);
        if (repeats < 1) throw new IllegalArgumentException("bench.repeat must be positive");
        if (scenarios.isEmpty() || modes.isEmpty() || Set.copyOf(modes).size() != modes.size()) {
            throw new IllegalArgumentException("Select nonempty scenarios and unique modes");
        }
        for (String mode : modes) {
            if (!Set.of("off", "fixed", "adaptive", "gradient", "bottleneck", "explore", "explore-balanced", "explore-joint",
                    "learned", "learned-additive").contains(baseMode(mode))) {
                throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        }
        long orderSeed = Long.getLong("bench.orderSeed", 42L);
        EpisodeWriter episodeWriter = new EpisodeWriter(outputDir,
                System.getProperty("bench.runId", UUID.randomUUID().toString()), orderSeed, sourceVersion());

        quietenLogging();

        List<RunResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            // Repetitions are interleaved across modes rather than blocked by
            // mode, and the reason is a measured one.  Running all seven
            // repetitions of a mode before starting the next one puts each mode
            // in its own stretch of wall-clock time, so anything that drifts —
            // machine load, thermal state, the database's cache, the host going
            // to sleep — lands on one mode and not the others, and the
            // difference between modes becomes inseparable from the difference
            // between the times they happened to be measured.
            //
            // This is not hypothetical here.  A blocked run measured the same
            // Gradient configuration at 70.9 rps in one window and 114.7 rps in
            // another, a 62% gap for a mode whose settings did not change,
            // which is far larger than any effect this harness is trying to
            // resolve.  Interleaving spreads every mode across the whole
            // measurement window, so drift shifts all of them together and
            // cancels in the comparison.
            Map<String, List<RunResult>> byMode = new LinkedHashMap<>();
            for (String mode : modes) {
                byMode.put(mode, new ArrayList<>());
            }
            for (int i = 0; i < repeats; i++) {
                for (String mode : RunOrder.forRepetition(modes, scenario.name(), orderSeed, i)) {
                    // One header per run keeps the log parseable by
                    // tools/analyse.py, which attributes each result line to the
                    // most recent header rather than assuming contiguous blocks.
                    System.out.printf("%n=== %s / %s ===%n", scenario.name(), mode);
                    long policySeed = Long.getLong("bench.policySeed", 20261108L)
                            ^ ((long) scenario.name().hashCode() << 32) ^ (i + 1L);
                    MeasuredRun measured = runOnce(mode, scenario, policySeed);
                    RunResult result = measured.result();
                    episodeWriter.write(result, i + 1, measured.details());
                    byMode.get(mode).add(result);
                    System.out.printf(
                            "   [%d/%d] goodput=%.1f rps  p95=%.1f ms  p99=%.1f ms  429=%.1f%%  hard-fail=%.1f%%%n",
                            i + 1, repeats, result.goodputRps(), result.p95Ms(), result.p99Ms(),
                            result.rejectionRatePercent(), result.failureRatePercent());
                }
            }
            for (String mode : modes) {
                List<RunResult> repetitions = byMode.get(mode);
                RunResult representative = RunResult.medianOf(repetitions);
                results.add(representative);
                if (repeats > 1) {
                    System.out.printf("%n   %s / %s median: goodput=%.1f rps  p95=%.1f ms  (spread p95 %.1f–%.1f)%n",
                            scenario.name(), mode,
                            representative.goodputRps(), representative.p95Ms(),
                            repetitions.stream().mapToDouble(RunResult::p95Ms).min().orElse(0),
                            repetitions.stream().mapToDouble(RunResult::p95Ms).max().orElse(0));
                }
            }
        }

        new ReportWriter(outputDir, SLO_MS).write(scenarios, results);
        System.out.println("\nReport written to " + outputDir.toAbsolutePath());
        System.out.println("All repetitions saved to " + episodeWriter.directory().toAbsolutePath());
    }

    private static List<Scenario> selectScenarios() {
        String only = System.getProperty("bench.scenarios");
        if (only == null || only.isBlank()) {
            return Scenario.all();
        }
        List<String> wanted = java.util.Arrays.stream(only.split(",", -1)).map(String::trim).toList();
        Map<String, Scenario> available = Scenario.all().stream().collect(
                java.util.stream.Collectors.toMap(Scenario::name, java.util.function.Function.identity()));
        if (Set.copyOf(wanted).size() != wanted.size() || !available.keySet().containsAll(wanted)) {
            throw new IllegalArgumentException("Unknown or duplicate scenario selection: " + only);
        }
        return wanted.stream().map(available::get).toList();
    }

    private static List<String> selectModes() {
        String only = System.getProperty("bench.modes");
        if (only == null || only.isBlank()) {
            return List.of("off", "fixed", "adaptive", "gradient", "bottleneck");
        }
        return java.util.Arrays.stream(only.split(",", -1)).map(String::trim).toList();
    }

    private record MeasuredRun(RunResult result, Map<String, Object> details) { }

    private static MeasuredRun runOnce(String mode, Scenario scenario, long policySeed) throws Exception {
        ConfigurableApplicationContext context = boot(mode, policySeed);
        ListAppender<ILoggingEvent> datasetAppender = captureDataset();
        ControlEngine engine = context.getBeanProvider(ControlEngine.class).getIfAvailable();
        var learned = engine != null && engine.getConfig().controller() instanceof com.iasr.policy.LearnedController p ? p : null;
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            Map<String, String> timing = new LinkedHashMap<>();
            timing.put("captureStartedAt", Instant.now().toString());
            List<RunResult.ActuatorSample> trace = new ArrayList<>();
            LoadGenerator.Outcomes outcomes;
            try (context) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                BackendModel backend = context.getBean(BackendModel.class);
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                ConcurrencyLimiter limiter = context.getBeanProvider(ConcurrencyLimiter.class).getIfAvailable();
                details.put("initialResources", resources(limiter, dataSource));
                details.put("configuration", effectiveConfiguration(context, backend, dataSource, scenario));
                try (LoadGenerator generator = new LoadGenerator("http://localhost:" + port + "/api/work")) {
                    timing.put("warmupStartedAt", Instant.now().toString());
                    warmUp(generator, backend);
                    details.put("measurementInitialResources", resources(limiter, dataSource));
                    Instant measurementStart = Instant.now();
                    timing.put("measurementStartedAt", measurementStart.toString());
                    timing.put("measurementScheduledEndAt", measurementStart.plusMillis(scenario.durationMs()).toString());
                    outcomes = generator.run(scenario.rate(), scenario.durationMs(), elapsed -> {
                            scenario.dependency().applyAt(elapsed, backend);
                            trace.add(new RunResult.ActuatorSample(
                                    Math.round(elapsed * 10) / 10.0,
                                    limiter != null ? limiter.currentValue() : -1,
                                    dataSource.getMaximumPoolSize(),
                                    limiter != null ? limiter.getInflight() : -1));
                        });
                    timing.put("drainCompletedAt", Instant.now().toString());
                }
            } // Stop the engine and flush its terminal record while capture is still attached.
            timing.put("captureEndedAt", Instant.now().toString());
            details.put("timing", timing);
            if (learned != null) details.put("inference", learned.statistics());

            List<String> datasetLines = datasetAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            return new MeasuredRun(RunResult.from(mode, scenario.name(), scenario.durationMs(),
                    SLO_MS, outcomes, trace, datasetLines), details);
        } finally {
            releaseDataset(datasetAppender);
        }
    }

    private static Map<String, Integer> resources(ConcurrencyLimiter limiter, HikariDataSource dataSource) {
        Map<String, Integer> values = new LinkedHashMap<>();
        if (limiter != null) values.put("concurrency_limit", limiter.currentValue());
        values.put("hikari_max_pool", dataSource.getMaximumPoolSize());
        return values;
    }

    private static Map<String, Object> effectiveConfiguration(ConfigurableApplicationContext context,
                                                              BackendModel backend, HikariDataSource pool,
                                                              Scenario scenario) {
        Map<String, Object> config = new LinkedHashMap<>();
        ControlEngine engine = context.getBeanProvider(ControlEngine.class).getIfAvailable();
        if (engine != null) {
            config.put("iasr", context.getBean(IasrProperties.class));
            config.put("controllerClass", engine.getConfig().controller().getClass().getName());
            config.put("guardrails", engine.getConfig().guardrails());
            if (engine.getConfig().controller() instanceof ExplorationController exploring) {
                config.put("exploration", exploring.parameters());
            }
            if (engine.getConfig().controller() instanceof com.iasr.policy.LearnedController learned) {
                config.put("learned", learned.parameters());
            }
        }
        config.put("backend", Map.of("kind", backend.isReal() ? "postgres" : "synthetic",
                "workers", backend.workerCount(), "serviceMs", backend.serviceTimeMs(),
                "appMs", backend.appTimeMs(), "thrash", backend.thrashFactor(),
                "rowsPerMs", Long.getLong("bench.db.rowsPerMs", 9_200L)));
        config.put("pool", Map.of("maximum", pool.getMaximumPoolSize(),
                "minimumIdle", pool.getMinimumIdle(), "connectionTimeoutMs", pool.getConnectionTimeout()));
        config.put("load", Map.of("scenario", scenario.name(), "durationMs", scenario.durationMs(),
                "warmupMs", 6_000, "warmupRps", 60, "clientThreads", 64, "requestTimeoutMs", 3_000));
        config.put("runtime", Map.of("javaVersion", System.getProperty("java.version"),
                "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"),
                "availableProcessors", Runtime.getRuntime().availableProcessors()));
        config.put("sloMs", SLO_MS);
        // Object conditions are invariant across policies, repetitions and seeds.
        // They define leakage-safe partition groups, including pinned ablations.
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("profile", scenario.definition());
        conditions.put("backend", config.get("backend"));
        conditions.put("load", config.get("load"));
        conditions.put("runtime", config.get("runtime"));
        conditions.put("initialPool", Integer.getInteger("bench.poolSize", 10));
        conditions.put("initialLimit", Integer.getInteger("bench.initialLimit", 100));
        conditions.put("sloMs", SLO_MS);
        config.put("conditions", conditions);
        return config;
    }

    private static Map<String, Object> sourceVersion() {
        try {
            Process revision = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String commit = new String(revision.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (revision.waitFor() != 0) return Map.of("commit", "unknown", "dirty", true);
            Process status = new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start();
            boolean dirty = status.getInputStream().readAllBytes().length > 0;
            if (status.waitFor() != 0) return Map.of("commit", commit, "dirty", true);
            return Map.of("commit", commit, "dirty", dirty);
        } catch (java.io.IOException e) {
            return Map.of("commit", "unknown", "dirty", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recording source version", e);
        }
    }

    /**
     * Let the JIT and the connection pool settle before measuring.  Without
     * this the first scenario absorbs class loading and pool fill-up, which
     * would show up as a latency penalty for whichever mode ran first.
     */
    private static void warmUp(LoadGenerator generator, BackendModel backend) {
        generator.run(t -> 60, 6_000, t -> { });
    }

    private static ConfigurableApplicationContext boot(String mode, long policySeed) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("server.port", 0);
        props.put("server.tomcat.threads.max", 400);
        props.put("server.tomcat.accept-count", 2000);
        props.put("spring.jmx.enabled", false);
        if (Boolean.getBoolean("bench.db.real")) {
            props.put("spring.datasource.url",
                    System.getProperty("bench.db.url", "jdbc:postgresql://localhost:55432/iasr"));
            props.put("spring.datasource.username", System.getProperty("bench.db.user", "iasr"));
            props.put("spring.datasource.password", System.getProperty("bench.db.password", "iasr"));
            DB_COUNTER.incrementAndGet();
        } else {
            props.put("spring.datasource.url",
                    "jdbc:h2:mem:bench" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        }
        props.put("spring.datasource.hikari.pool-name", POOL_NAME);
        // Starting pool size.  Raising it above the dependency's worker count
        // removes the connection shortage entirely, which is how the claim
        // "coordinating a second resource pays" gets its boundary: where the
        // second resource is not a constraint, coordinating it should buy
        // nothing, and a benchmark that only ever shows the favourable case is
        // indistinguishable from one arranged to.
        int poolSize = Integer.getInteger("bench.poolSize", 10);
        props.put("spring.datasource.hikari.maximum-pool-size", poolSize);
        props.put("spring.datasource.hikari.minimum-idle", poolSize);
        props.put("spring.datasource.hikari.connection-timeout", 3000);
        props.put("management.endpoints.enabled-by-default", false);
        // Each SpringApplication boot reinitialises the logging system, so levels
        // have to be set as properties rather than programmatically once.
        props.put("logging.level.root", "WARN");
        props.put("logging.level.com.iasr", "WARN");
        props.put("logging.level.com.zaxxer", "WARN");

        props.putAll(iasrProperties(mode));
        props.put("bench.exploration.seed", policySeed);
        String base = baseMode(mode);
        SpringApplicationBuilder builder;
        if ("gradient".equals(base)) {
            builder = new SpringApplicationBuilder(BenchmarkApplication.class, GradientBaselineConfig.class);
        } else if ("bottleneck".equals(base)) {
            builder = new SpringApplicationBuilder(BenchmarkApplication.class, BottleneckConfig.class);
        } else if ("learned".equals(base) || "learned-additive".equals(base)) {
            String modelProperty = "learned-additive".equals(base) ? "bench.additiveModel" : "bench.model";
            String model = System.getProperty(modelProperty);
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Missing -D" + modelProperty + "=<JSON model>");
            props.put("bench.learned.modelPath", model);
            builder = new SpringApplicationBuilder(BenchmarkApplication.class, LearnedConfig.class);
        } else if (base.startsWith("explore")) {
            props.put("bench.exploration.sampling", switch (base) {
                case "explore-balanced" -> "BALANCED";
                case "explore-joint" -> "JOINT";
                default -> "UNIFORM";
            });
            builder = new SpringApplicationBuilder(BenchmarkApplication.class, ExplorationConfig.class);
        } else {
            builder = new SpringApplicationBuilder(BenchmarkApplication.class);
        }
        return builder
                .properties(props)
                .web(WebApplicationType.SERVLET)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .run();
    }

    /**
     * Supplies the comparison controller through the SPI, which is also a test
     * of the claim that the decision logic is swappable by adding one bean.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class GradientBaselineConfig {
        @org.springframework.context.annotation.Bean
        public com.iasr.core.controller.Controller gradientController(
                com.iasr.spring.IasrProperties props) {
            return new GradientController(
                    props.getConcurrency().getMinLimit(),
                    props.getConcurrency().getMaxLimit());
        }
    }

    /** Supplies the bottleneck-directed controller through the same SPI. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class BottleneckConfig {
        @org.springframework.context.annotation.Bean
        public com.iasr.core.controller.Controller bottleneckController(
                com.iasr.spring.IasrProperties props) {
            return new com.iasr.core.controller.BottleneckController(
                    props.getSloLatencyMs(),
                    props.getController().getConcurrencyIncreaseStep(),
                    props.getController().getHikariStep(),
                    props.getController().getMinGradient(),
                    Integer.getInteger("bench.poolPeriod", 5));
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ExplorationConfig {
        @org.springframework.context.annotation.Bean
        public com.iasr.core.controller.Controller explorationController(
                IasrProperties props,
                @org.springframework.beans.factory.annotation.Value("${bench.exploration.seed}") long seed,
                @org.springframework.beans.factory.annotation.Value("${bench.exploration.sampling}") ExplorationController.Sampling sampling) {
            var rules = policyRules(props);
            var baseline = policyBaseline(props);
            return new ExplorationController(new com.iasr.core.controller.JointActionSpace(rules,
                    props.getSloLatencyMs(), props.getWindowMs() / 1000.0, Integer.getInteger("bench.poolPeriod", 5)),
                    baseline, Double.parseDouble(System.getProperty("bench.epsilon", "0.5")), seed,
                    sampling, props.getSloLatencyMs());
        }
    }

    private static List<com.iasr.core.guardrail.ActuatorGuardrail> policyRules(IasrProperties props) {
        var c = props.getConcurrency();
        var h = props.getHikari();
        return List.of(
                new com.iasr.core.guardrail.ActuatorGuardrail("concurrency_limit", c.getMinLimit(), c.getMaxLimit(),
                        c.getMaxStep(), c.getCooldownTicks(), c.isBlockIncreaseOnSloViolation(), c.getMaxStepDown()),
                new com.iasr.core.guardrail.ActuatorGuardrail("hikari_max_pool", h.getMinPoolSize(), h.getMaxPoolSize(),
                        h.getMaxStep(), h.getCooldownTicks(), h.isBlockIncreaseOnSloViolation()));
    }

    private static com.iasr.core.controller.Controller policyBaseline(IasrProperties props) {
        var cp = props.getController();
        return new com.iasr.core.controller.BaselineController(props.getSloLatencyMs(),
                cp.getComfortFactor(), cp.getConcurrencyIncreaseStep(), cp.getConcurrencyDecreaseFactor(),
                cp.getErrorRateThreshold(), cp.getTimeoutRateThreshold(), cp.getHikariStep(),
                cp.getHikariPendingThreshold(), cp.getHikariAcquireTimeThresholdMs(), cp.getUtilizationGate(),
                cp.getSloTargetHeadroom(), cp.getMinGradient(), cp.getHikariIdleShrinkThreshold());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class LearnedConfig {
        @org.springframework.context.annotation.Bean
        public com.iasr.core.controller.Controller learnedController(IasrProperties props,
                @org.springframework.beans.factory.annotation.Value("${bench.learned.modelPath}") String path) throws java.io.IOException {
            if (!Boolean.getBoolean("bench.allowPrototype")) {
                throw new IllegalArgumentException("learned requires -Dbench.allowPrototype=true; model is an experimental prototype");
            }
            double budgetMs = Double.parseDouble(System.getProperty("bench.inferenceBudgetMs", "20"));
            if (!Double.isFinite(budgetMs) || budgetMs <= 0 || budgetMs > 1000) throw new IllegalArgumentException("Invalid inference budget");
            var model = com.iasr.policy.FqiModel.loadPrototype(java.nio.file.Path.of(path));
            var rules = new LinkedHashMap<String, com.iasr.core.guardrail.ActuatorGuardrail>();
            policyRules(props).forEach(rule -> rules.put(rule.actuatorName(), rule));
            return new com.iasr.policy.LearnedController(model,
                    new com.iasr.policy.PolicyContext(props.getSloLatencyMs(), props.getWindowMs() / 1000.0,
                            Integer.getInteger("bench.poolPeriod", 5), rules), policyBaseline(props), (long) (budgetMs * 1e6));
        }
    }

    /**
     * A mode may carry a {@code -pinned} suffix, which fixes the connection
     * pool for that run and changes nothing else.
     * <p>
     * The suffix exists so that the pinned and free arms of the decisive
     * experiment can be interleaved inside a single run.  Selecting the arm
     * with a global system property forced them into two separate runs, and two
     * separate runs turned out to differ by more than the effect under test:
     * the same Gradient configuration measured 70.9 rps in one and 114.7 rps in
     * the other.  An experiment whose two arms cannot be measured alongside
     * each other cannot answer the question it was built for.
     */
    private static String baseMode(String mode) {
        return mode.endsWith(PINNED_SUFFIX)
                ? mode.substring(0, mode.length() - PINNED_SUFFIX.length())
                : mode;
    }

    private static final String PINNED_SUFFIX = "-pinned";

    private static Map<String, Object> iasrProperties(String mode) {
        Map<String, Object> props = new LinkedHashMap<>();
        String base = baseMode(mode);
        if ("off".equals(base)) {
            props.put("iasr.enabled", false);
            return props;
        }

        props.put("iasr.enabled", true);
        props.put("iasr.apply-actions", !"fixed".equals(base));
        props.put("iasr.slo-latency-ms", SLO_MS);
        // 1 s instead of the 5 s default: a 45 s scenario must contain enough
        // control ticks for the loop to converge, otherwise the benchmark
        // measures the loop's startup transient rather than its behaviour.
        props.put("iasr.window-ms", 1000);
        props.put("iasr.dataset-logging-enabled", true);

        props.put("iasr.concurrency.initial-limit", Integer.getInteger("bench.initialLimit", 100));
        props.put("iasr.concurrency.min-limit", 5);
        props.put("iasr.concurrency.max-limit", 400);
        props.put("iasr.concurrency.max-step", 20);
        override(props, "iasr.concurrency.max-step-down", "bench.maxStepDown");
        override(props, "iasr.controller.min-gradient", "bench.minGradient");
        override(props, "iasr.controller.hikari-idle-shrink-threshold", "bench.idleShrink");
        props.put("iasr.concurrency.cooldown-ticks", 1);
        props.put("iasr.concurrency.acquire-timeout-ms", 100);

        // Pinning the pool isolates the concurrency rules from the pool ones.
        // Gradient controls only concurrency, so any advantage IASR shows over
        // it could come either from better concurrency decisions or from moving
        // a second resource Gradient never touches — and those are different
        // claims. Fixing min == max leaves the actuator registered but immovable.
        // The controller's pool range has to follow the starting pool rather
        // than sit at literals: on the real database the dependency's optimum
        // is its core count (6), so the run starts at 4 to make the pool the
        // initial bottleneck, and a floor hardcoded at 5 would silently clamp
        // the pool up on the first tick — the harness would then be measuring a
        // starting point it never actually used.  Pinning likewise fixes the
        // pool at wherever the run started, so the pinned and free arms differ
        // only in whether the pool may move.  Defaults reproduce the previous
        // literals exactly, so earlier results stay comparable.
        boolean pinPool = Boolean.getBoolean("bench.pinPool") || mode.endsWith(PINNED_SUFFIX);
        int controlledPool = Integer.getInteger("bench.poolSize", 10);
        props.put("iasr.hikari.min-pool-size",
                pinPool ? controlledPool : Integer.getInteger("bench.poolMin", 5));
        props.put("iasr.hikari.max-pool-size",
                pinPool ? controlledPool : Integer.getInteger("bench.poolMax", 30));
        props.put("iasr.hikari.max-step", 2);
        props.put("iasr.hikari.cooldown-ticks", 2);

        // Additive-increase step per control tick.  With a 1 s window this is
        // also the increase per second, so 1 here reproduces the library's
        // default rate (step 5 per 5 s window) on the compressed timescale.
        // Anything larger silently makes the controller more aggressive than a
        // real deployment would be — measuring at 10 cost 71% of p95 under the
        // spike, which is a property of the tuning, not of the algorithm.
        props.put("iasr.controller.concurrency-increase-step",
                Integer.getInteger("bench.increaseStep", 1));

        override(props, "iasr.controller.utilization-gate", "bench.utilizationGate");
        override(props, "iasr.controller.slo-target-headroom", "bench.sloTargetHeadroom");
        return props;
    }

    /**
     * Apply a controller setting only when the run explicitly asks for it, so
     * that an unqualified run measures the library exactly as it ships.
     * <p>
     * Restating a library default here as a literal looks harmless and is not:
     * the two drift apart the moment one of them changes, and the harness then
     * silently measures a configuration nobody deploys. That happened — the
     * benchmark pinned {@code slo-target-headroom} to 1.0 while the library had
     * already moved its default to 0, so a rule documented as "off by default"
     * was switched on in every published number.
     */
    private static void override(Map<String, Object> props, String property, String benchKey) {
        String value = System.getProperty(benchKey);
        if (value != null && !value.isBlank()) {
            props.put(property, value);
        }
    }

    // ── dataset capture ──────────────────────────────────────────────────

    private static ListAppender<ILoggingEvent> captureDataset() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DatasetLogger.LOGGER_NAME);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void releaseDataset(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DatasetLogger.LOGGER_NAME);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static void quietenLogging() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("ROOT");
        root.setLevel(Level.WARN);
    }
}
