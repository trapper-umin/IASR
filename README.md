# IASR — Intelligent Adaptive Soft-Resource Library

Runtime-контур управления (control loop) для динамической адаптации мягких ресурсов JVM-приложения **без перезапуска**.

План развития в рамках магистерской диссертации — [docs/dissertation/plan.md](docs/dissertation/plan.md).

**Обучаемая согласованная политика.** Любой мягкий ресурс описывается единообразно (`SoftResource`), совместные действия над ними задаёт `CoordinatedActionSpace`, а обученная политика включается одним свойством `iasr.policy.model-path` и следует правилам там, где данных для уверенного решения нет. Метод — [coordinated-policy.md](docs/dissertation/coordinated-policy.md), обучение — [iasr-learning](iasr-learning/README.md), результат на PostgreSQL — [отчёт](docs/benchmark/analysis/coordinated-2026-11-15/README.md). Ниже описаны прежний прототип и история его проверки.

Постановка прежнего прототипа — [спецификация обучения](docs/dissertation/learning-spec.md). Семантика метрик описана в [контракте](docs/dissertation/metrics-contract.md), сохранение всех повторов и `schemaVersion: 3` — в [формате эпизодов](docs/benchmark/episodes.md).

Исследующая политика `explore`, матрица условий, разбиение train/validation/test и аудит покрытия описаны в [конвейере данных](docs/dissertation/data-pipeline.md).

Offline обучение Q-функции и экспорт переносимой JSON-модели — в [описании прототипа FQI](docs/dissertation/fqi-prototype.md).

JVM inference через `Controller`, бюджет времени и baseline fallback реализованы в отдельном `iasr-policy`; запуск и ограничения — [в инструкции](docs/dissertation/jvm-policy.md).

[Диагностика совместного покрытия и новая итерация данных](docs/dissertation/joint-data-iteration.md) описывают `explore-balanced`, обучение без чтения test и ограничения второй модели.

[Сохранение прежнего train и сравнение FQI](docs/dissertation/retained-train-iteration.md) фиксируют неизменяемый validation, защиту held-out групп, сравнение трёх кандидатов и технический контроль двух моделей.

[Q-функция по совместным действиям](docs/dissertation/action-conditioned-fqi.md) описывает отдельные регрессоры для дискретных пар действий, переносимую JSON-модель версии 2 и совместимый JVM runtime.

## Архитектура

```
┌────────────────────────────────────────────────────────────────┐
│                      iasr-core (0 deps*)                       │
│                                                                │
│  ┌─────────────────────┐  ┌────────────┐  ┌────────────────┐   │
│  │     metrics/        │  │ controller/│  │   actuator/    │   │
│  │  MetricsProvider    │  │ Controller │  │ Concurrency-   │   │
│  │  (SPI)              │──│ (SPI)      │──│  Limiter       │   │
│  │  MetricsSnapshot    │  │ Baseline-  │  │ HikariPool-    │   │
│  │  WindowedLatency-   │  │ Controller │  │  Actuator      │   │
│  │   Tracker           │  │ (AIMD)     │  │                │   │
│  └─────────────────────┘  └────────────┘  └────────────────┘   │
│           │                    │                  │            │
│           ▼                    ▼                  ▼            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │               ControlEngine (Δt loop)                   │   │
│  │    collect → decide → guardrail → apply → log           │   │
│  │    (observe-only mode: apply skipped when disabled)     │   │
│  └─────────────────────────────────────────────────────────┘   │
│           │                                                    │
│  ┌──────────────────┐  ┌──────────────────┐                    │
│  │   guardrail/     │  │    dataset/      │                    │
│  │ ActuatorGuardrail│  │  DatasetLogger   │                    │
│  │ Guardrails       │  │  DatasetRecord   │                    │
│  │ (cooldown/step/  │  │  (JSONL, state→  │                    │
│  │  SLO block/clamp)│  │   action→outcome)│                    │
│  └──────────────────┘  └──────────────────┘                    │
│                                                                │
│     config/ControlEngineConfig  (Builder, immutable)           │
└────────────────────────────────────────────────────────────────┘
         * только slf4j-api + HikariCP (provided)

  HTTP-запрос
      │  record(durationMs)
      ▼
  WindowedLatencyTracker ──drainAndCompute()──▶ MicrometerMetricsProvider
  (ConcurrentLinkedQueue,                        (читает MeterRegistry +
   lock-free write,                               per-window перцентили)
   per-window drain)

┌──────────────────────────┐   ┌────────────────────────────────┐
│    iasr-micrometer       │   │    iasr-spring-boot-starter    │
│  MicrometerMetrics-      │   │  IasrAutoConfiguration         │
│   Provider               │   │  ConcurrencyLimitFilter        │
│  IasrMeterBinder         │   │   (429, latency tracking,      │
│  IasrMeterFilter-        │   │    infra-path exclusion)       │
│   Configurer             │   │  IasrProperties                │
│  (percentile histograms) │   │   (@ConfigurationProperties)   │
└──────────────────────────┘   └────────────────────────────────┘
```

## Папочная структура
```
IASR/
├── pom.xml                            ← Parent POM (multi-module)
├── README.md                          ← Документация
│
├── iasr-core/                         ← Ядро (0 внешних зависимостей кроме SLF4J + HikariCP provided)
│   ├── pom.xml
│   └── src/main/java/com/iasr/core/
│       ├── metrics/
│       │   ├── MetricsProvider.java          SPI — абстрактный источник метрик
│       │   ├── MetricsSnapshot.java          Immutable bag of named double values
│       │   ├── MetricNames.java              Константы ключей метрик
│       │   └── WindowedLatencyTracker.java   Lock-free per-window p95/p99 (ConcurrentLinkedQueue + drain)
│       ├── actuator/
│       │   ├── Actuator.java              SPI — управляемый ресурс
│       │   ├── ConcurrencyLimiter.java    Fair semaphore + O(1) runtime resize (permit debt)
│       │   └── HikariPoolActuator.java    HikariCP maximumPoolSize
│       ├── controller/
│       │   ├── Controller.java            SPI — стратегия принятия решений
│       │   ├── ControlAction.java         Immutable set of desired values
│       │   └── BaselineController.java    AIMD threshold controller + idle-shrink
│       ├── guardrail/
│       │   ├── ActuatorGuardrail.java     Per-actuator constraints (record)
│       │   └── Guardrails.java            Enforcer: cooldown → SLO block → step clamp → abs clamp
│       ├── dataset/
│       │   ├── DatasetRecord.java         JSONL row + hand-rolled serialization
│       │   └── DatasetLogger.java         SLF4J logger "softres.dataset", state→action→outcome
│       ├── config/
│       │   └── ControlEngineConfig.java   Builder-based immutable config
│       └── engine/
│           └── ControlEngine.java         ScheduledExecutorService control loop, observe-only mode
│
├── iasr-micrometer/                   ← Адаптер к Micrometer
│   ├── pom.xml
│   └── src/main/java/com/iasr/micrometer/
│       ├── MicrometerMetricsProvider.java    MetricsProvider → MeterRegistry + WindowedLatencyTracker
│       ├── IasrMeterBinder.java              Регистрация кастомных Gauge/Counter
│       └── IasrMeterFilterConfigurer.java    MeterFilter — включение percentile histograms
│
└── iasr-spring-boot-starter/          ← Spring Boot 3.x Starter
    ├── pom.xml
    └── src/main/java/com/iasr/spring/
    │   ├── IasrAutoConfiguration.java     @AutoConfiguration, classpath-safe HikariCP detection,
    │   │                                  auto-discovery Actuator-бинов через List<Actuator>
    │   ├── IasrProperties.java            @ConfigurationProperties (prefix "iasr")
    │   └── ConcurrencyLimitFilter.java    Servlet Filter: 429, latency tracking,
    │                                      исключение /actuator /health /prometheus
    └── src/main/resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports

├── iasr-benchmark/                    ← Нагрузочный стенд (не публикуется)
│   └── src/main/java/com/iasr/benchmark/
│       ├── app/BenchmarkApplication.java   Приложение под нагрузкой
│       ├── app/BackendModel.java           Синтетическая зависимость с известной ёмкостью
│       ├── load/LoadGenerator.java         Открытый цикл, защита от coordinated omission
│       ├── load/Scenario.java              Профили нагрузки и деградации
│       ├── report/RunResult.java           Статистика одного прогона
│       └── BenchmarkRunner.java            main(): 3 конфигурации × сценарии
│
└── docs/benchmark/                    ← Результаты замеров
    ├── methodology.md                      Устройство эксперимента и его ограничения
    ├── report.md                           Результаты (генерируется прогоном)
    ├── results.csv                         Сводка
    └── raw/                                Траектории актюаторов и датасеты IASR
```
## Ключевые решения архитектуры

### 1. Разделение ядро / адаптеры
`iasr-core` не имеет compile-time зависимостей на Micrometer, Spring, Jackson — только `slf4j-api` и `HikariCP` в scope `provided`. Адаптеры (`iasr-micrometer`, `iasr-spring-boot-starter`) знают о ядре, но не наоборот. Это позволяет использовать ядро в любом JVM-приложении без транзитивных зависимостей.

### 2. SPI-интерфейсы — три точки расширения
`MetricsProvider`, `Controller`, `Actuator` — единственные интерфейсы, которые нужно реализовать для интеграции с произвольным стеком:

| Интерфейс | Реализации | Для замены |
|-----------|-----------|------------|
| `MetricsProvider` | `MicrometerMetricsProvider` | Prometheus scrape, OpenTelemetry, custom |
| `Controller` | `BaselineController` | RL-агент, MPC, ONNX-модель |
| `Actuator` | `ConcurrencyLimiter`, `HikariPoolActuator` | Thread pool, rate limiter, cache size |

ML-контроллер подключается заменой одного бина/параметра без изменения остального кода.

### 3. ConcurrencyLimiter: permit debt через ResizableSemaphore
Семафор fair (FIFO). Изменение лимита в runtime:
- **Увеличение** — `semaphore.release(delta)`, заблокированные потоки пробуждаются немедленно, O(1).
- **Уменьшение** — `ResizableSemaphore.reducePermits(delta)` (тонкий subclass, открывающий `protected` метод JDK) атомарно снижает внутренний счётчик — допустимо до отрицательных значений ("permit debt"). Долг естественно погашается последующими вызовами `release()` без дополнительных циклов drain или счётчиков. Это исключает race condition между уменьшением лимита и уже летящими запросами.

### 4. WindowedLatencyTracker: per-window vs. decay-window перцентили
Micrometer Timer хранит гистограмму с экспоненциальным затуханием (~2 мин). Два соседних 5-секундных снапшота дают почти идентичные p95/p99, что делает `state ≈ outcome` в датасете — данные непригодны для обучения ML.

`WindowedLatencyTracker` решает это накоплением длительностей запросов в `ConcurrentLinkedQueue` (lock-free write из потоков запросов) и атомарным drain'ом в control-loop потоке один раз за тик. После drain буфер пуст — следующий тик получает только свежие наблюдения. Перцентили вычисляются сортировкой массива за O(n log n).

Фильтр передаёт в tracker только успешные ответы HTTP 2xx. Для каждого запроса задержка складывается из ожидания допуска и обслуживания; процентиль вычисляется уже по суммам отдельных запросов. `service_mean_ms` сохраняет отдельное среднее время обслуживания для декомпозиции. Сеть и очередь приёма servlet-контейнера в это измерение не входят.

Отклонённые запросы не логируются поштучно. Шеддинг по определению приходит пачками, и 300 отброшенных запросов в секунду означали бы 300 строк лога в секунду — этот ввод-вывод ложится на уже перегруженное приложение и усиливает ровно ту перегрузку, которую лимитер сдерживает. Поэтому детали идут в DEBUG, а в WARN раз в 5 секунд уходит агрегированная строка; точный счёт всегда есть в счётчике `iasr.concurrency.timeouts`.

`ConcurrencyLimitFilter` пропускает инфраструктурные пути (`/actuator`, `/health`, `/prometheus`, `/live`, `/ready`) мимо себя целиком: они не занимают permit и не попадают в tracker. Важны обе половины. Исключение из латентности не даёт дешёвому трафику health-check'ов размывать сигнал контура, а освобождение от лимита оставляет пробы оркестратора отвечающими под насыщением — 429 на `/health` убил бы инстанс ровно тогда, когда IASR сбрасывает нагрузку, чтобы его спасти.

Оговорка: per-window перцентили доведены только до HTTP-латентности. `gc_pause_p95_ms` и `hikari_acquire_p95_ms` читаются из перцентильных гистограмм Micrometer с его собственным затухающим окном (~2 мин) — инструментировать эти точки вызова напрямую библиотека не может. Для времени получения соединения дополнительно публикуется `hikari_acquire_mean_ms`, посчитанное по дельтам count/totalTime и покрывающее ровно одно окно Δt.

### 5. BaselineController (AIMD) + idle-shrink
Детерминированный контроллер с пороговой логикой. Подробнее — в разделе [Baseline Controller](#baseline-controller-aimd).

Дополнительно: при отсутствии реального трафика (`goodput < 1 RPS`) более 24 тиков подряд контроллер постепенно уменьшает лимиты (`idle-shrink`), возвращая их к минимальным значениям. Это освобождает системные ресурсы в периоды простоя и гарантирует, что при возобновлении нагрузки контроллер начнёт с небольшого значения и будет наращивать его по AIMD, а не сохранит пик предыдущей сессии.

### 6. Guardrails: многослойный предохранитель
Guardrail — это независимый уровень безопасности поверх любого контроллера. Порядок применения для каждого актюатора:
1. **Cooldown check** — подавить изменение, если после последнего прошло меньше N тиков.
2. **SLO violation block** — запретить увеличение ресурса при нарушении SLO (защита от раскачки).
3. **maxStep clamp** — ограничить шаг изменения за один тик.
4. **absolute clamp** — зажать итоговое значение в `[min, max]`.

Счётчик cooldown не уменьшается при подавлении изменения — только при реальном применении. Это гарантирует корректную длину cooldown вне зависимости от того, сколько раз было подавлено изменение. Пока актюатор ни разу не менялся, счётчик держится на «бесконечности», поэтому холостые тики на старте не складываются в искусственный cooldown и первое изменение всегда разрешено сразу.

**SLO-блок настраивается для каждого актюатора отдельно** (`blockIncreaseOnSloViolation`). Для лимита конкурентности он включён: впускать больше запросов, когда латентность уже за порогом, обычно только усугубляет нарушение. Для пула соединений — выключен, и это осознанная асимметрия. Потоки, стоящие в очереди за соединением, проявляются именно как высокая латентность запросов, то есть нехватка соединений — вероятная **причина** нарушения, а расширение пула — лекарство. Если бы guardrail блокировал и это увеличение, контур застрял бы ровно в том состоянии, из которого пытается выйти. Контроллер просит расширить пул только когда видит одновременно pending-потоки и высокое время получения соединения.

### 7. Dataset Logger: state → action → outcome через отложенный flush
На тике **t**: запись `state_t` и `action_t` сохраняется в памяти (`pending`).
На тике **t+1**: `pending` дополняется `outcome_t = state_{t+1}` и логируется в виде одной JSON-строки в SLF4J-логгер `softres.dataset`.
При shutdown: `flush()` записывает последнюю запись с `outcome: null`.

Это единственный способ построить датасет формата `(s, a, s')` без задержки хранения — вся логика умещается в одном объекте `DatasetLogger`, без внешней БД.

### 8. Spring Boot Starter: classpath-safe автоконфигурация
`IasrAutoConfiguration` активируется только при наличии `MeterRegistry` в контексте. Hikari-специфичный код вынесен во внутренний `@Configuration`-класс `HikariActuatorConfiguration`, защищённый **строковым** `@ConditionalOnClass` (имена классов как строки). При использовании `.class`-литералов Java разрешает их при загрузке outer-класса, что вызывает `NoClassDefFoundError`, если HikariCP отсутствует. Строковые условия Spring вычисляет через ASM-байткод без загрузки классов.

`ControlEngine` получает все `Actuator`-бины через `List<Actuator>` из контекста Spring, а не через typed reference — это позволяет подключать произвольные кастомные актюаторы без изменения кода автоконфигурации.

### 9. Observe-only режим
При `iasr.apply-actions: false` control loop запускается, собирает метрики и пишет датасет, но **не применяет** действия к актюаторам. Это позволяет:
- безопасно ввести IASR в production в режиме наблюдения;
- накопить обучающий датасет с человеческой/внешней политикой управления;
- валидировать, что метрики собираются корректно, до включения автоуправления.

Флаг намеренно отделён от `iasr.enabled`. `enabled: false` — это master-выключатель: автоконфигурация полностью отключается, бины не создаются, датасет не пишется. Совмещать два смысла в одном свойстве нельзя: единственный способ «наблюдать не управляя» иначе выключал бы и сам сбор данных, ради которого режим существует.

| Свойство | Control loop | Сбор метрик и датасет | Изменение актюаторов |
|----------|--------------|-----------------------|----------------------|
| `enabled: true`, `apply-actions: true` (по умолчанию) | запущен | да | да |
| `enabled: true`, `apply-actions: false` | запущен | да | **нет** |
| `enabled: false` | не создаётся | нет | нет |

## Модули

| Модуль | Artifact | Описание |
|--------|----------|----------|
| **iasr-core** | `com.iasr:iasr-core` | Ядро: интерфейсы, контроллер, актюаторы, guardrails, dataset logger. Нет зависимостей на Spring/Micrometer |
| **iasr-micrometer** | `com.iasr:iasr-micrometer` | Адаптер MetricsProvider → Micrometer MeterRegistry |
| **iasr-policy** | `com.iasr:iasr-policy` | Обучаемые политики: согласованная политика версии 3 (`coordinated`) и прежний прототип FQI |
| **iasr-spring-boot-starter** | `com.iasr:iasr-spring-boot-starter` | Автоконфигурация для Spring Boot 3.x; включает обученную политику свойством `iasr.policy.model-path` |
| **iasr-learning** | — (Python) | Сбор эпизодов, обучение политики, эталонные решения для JVM, парное сравнение режимов |
| **iasr-benchmark** | — (не публикуется) | Нагрузочный стенд: измеряет, помогает ли контур и какой ценой |

## Быстрый старт (Spring Boot)

### 1. Добавить зависимость

```xml
<dependency>
    <groupId>com.iasr</groupId>
    <artifactId>iasr-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

### 2. Настроить `application.yml`

```yaml
iasr:
  enabled: true          # master-выключатель: false отключает IASR целиком
  apply-actions: true    # false = observe-only (метрики и датасет без управления)
  slo-latency-ms: 200
  window-ms: 5000
  dataset-logging-enabled: true

  concurrency:
    initial-limit: 100
    min-limit: 10
    max-limit: 500
    max-step: 20
    max-step-down: 50                       # снижать можно быстрее, чем повышать
    cooldown-ticks: 3
    acquire-timeout-ms: 500
    block-increase-on-slo-violation: true   # см. раздел Guardrails

  hikari:
    min-pool-size: 5
    max-pool-size: 50
    max-step: 2
    cooldown-ticks: 5
    block-increase-on-slo-violation: false  # нехватка соединений — причина, а не следствие

  controller:
    comfort-factor: 0.7
    concurrency-increase-step: 5
    concurrency-decrease-factor: 0.85
    error-rate-threshold: 0.01
    timeout-rate-threshold: 0.005
    utilization-gate: 0.9        # не растить лимит, пока он не мешает
    min-gradient: 0.5            # градиентное снижение; 0 = старое ×0.85
    slo-target-headroom: 0       # цель по закону Литтла; включать осознанно
    hikari-idle-shrink-threshold: 2   # сколько соединений должно простаивать
```

### 3. Настроить логгер для JSONL-датасета

В `logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>

    <property name="DATASET_DIR" value="${DATASET_DIR:-logs}"/>
    <appender name="DATASET" class="ch.qos.logback.core.FileAppender">
        <file>${DATASET_DIR}/iasr-dataset.jsonl</file>
        <append>true</append>
        <encoder>
            <pattern>%msg%n</pattern>
        </encoder>
    </appender>

    <logger name="softres.dataset" level="INFO" additivity="false">
        <appender-ref ref="DATASET"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

</configuration>
```

```DATASET_DIR=./service-name/logs```

### 4. Готово!

При старте приложения IASR автоматически:
- Создаёт `ConcurrencyLimiter` и регистрирует HTTP-фильтр
- Находит `HikariDataSource` и подключает `HikariPoolActuator`
- Запускает control loop с периодом Δt
- Логирует JSONL-датасет в `softres.dataset`

## Использование без Spring Boot

```java
// 1. Создать актюаторы
ConcurrencyLimiter limiter = new ConcurrencyLimiter(100, 10, 500);
HikariPoolActuator hikariActuator = new HikariPoolActuator(dataSource, 5, 50);

// 2. Реализовать MetricsProvider (или использовать iasr-micrometer)
MetricsProvider provider = () -> MetricsSnapshot.builder()
        .put("latency_p95_ms", computeP95())
        .put("error_rate", computeErrorRate())
        // ... другие метрики
        .build();

// 3. Собрать конфигурацию
ControlEngineConfig config = ControlEngineConfig.builder()
        .windowMs(5000)
        .sloLatencyMs(200)
        .metricsProvider(provider)
        .addActuator(limiter)
        .addActuator(hikariActuator)
        // (name, min, max, maxStep, cooldownTicks) — SLO-блок включён
        .addGuardrail(new ActuatorGuardrail("concurrency_limit", 10, 500, 20, 3))
        // шестой аргумент выключает SLO-блок: нехватка соединений — причина нарушения
        .addGuardrail(new ActuatorGuardrail("hikari_max_pool", 5, 50, 2, 5, false))
        // .applyActions(false) — observe-only: метрики и датасет без управления
        .build();

// 4. Запустить
ControlEngine engine = new ControlEngine(config);
engine.start();

// 5. Использовать limiter в обработчиках запросов
if (limiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
    try {
        handleRequest();
    } finally {
        limiter.release();
    }
} else {
    return Response.status(429).build();
}

// 6. Остановить при shutdown
engine.stop();
```

## Baseline Controller (AIMD)

Детерминированный контроллер с пороговой логикой:

### Concurrency Limit

| Условие | Действие |
|---------|----------|
| `latency_p95 < 0.7 × SLO` И `errors ≈ 0` И `timeouts ≈ 0` И **лимит связывает** | `limit += k` (additive increase) |
| `latency_p95 > SLO` | `limit × max(0.5, SLO / latency)` (градиент) |
| `timeouts↑` ИЛИ `errors↑` без нарушения латентности | `limit × β` (β=0.85) |
| Иначе | Hold (без изменений) |

Три уточнения к классическому AIMD, все обоснованы замерами на стенде.

**Увеличение привязано к спросу.** Лимит наращивается только когда он реально мешает. Признак связывания — либо непустая очередь за permit (`queue_length > 0`, то есть кто-то прямо сейчас заблокирован на лимите), либо, если очередь пуста, загрузка выше `utilization-gate`. Очередь первична: она констатирует факт, тогда как загрузка лишь намекает и требует угаданного порога. Без этого условия правило не знает слова «достаточно»: сервис, спокойно работающий на 7 одновременных запросах, доводит лимит до потолка и встречает следующий всплеск вообще без admission control. Отключается через `utilization-gate: 0`.

**Снижение градиентное, а не геометрическое.** Вместо фиксированной доли берётся отношение `SLO / observed`: латентность втрое выше цели делит лимит на три. Геометрическое затухание одинаково реагирует на лёгкий и на десятикратный промах, поэтому время схождения зависит не от величины ошибки, а от того, насколько далеко оказался лимит: ×0.85 за тик тратит ~19 тиков на путь со 190 до 33 — дольше многих всплесков, и контур доходит до цели ровно тогда, когда возмущение кончилось. Градиент делает поправку пропорциональной ошибке и сам затухает, потому что отношение стремится к единице по мере приближения к SLO. Нижняя граница `min-gradient` не даёт одному плохому замеру обрушить лимит. Подход из Gradient в Netflix `concurrency-limits`; отключается через `min-gradient: 0`.

**Цель снижения по закону Литтла — опция, по умолчанию выключена.** `L = λ × W`: чтобы обслуживать λ запросов в секунду с латентностью W, ровно `λ × W` из них находятся в обработке; подставив в W целевой SLO, получаем concurrency, которую цель допускает. При нарушении SLO контур может спускаться сразу к этой величине, а не нащупывать её.

λ здесь — **предложенная** нагрузка, `goodput / (1 − timeout_rate)`, а не достигнутая: достигнутая уже придавлена тем самым шеддингом, размер которого мы вычисляем, поэтому оценка от неё систематически занижена.

Но и с исправленной λ правило **зависит от режима**, поэтому в дефолты не годится. Замеры на трёх режимах:

| Режим | Результат |
|---|---|
| SLO 150 мс при обслуживании 55 мс | p95 ниже на 37–44% без потерь |
| SLO 60 мс при обслуживании 53 мс | сброс 10% трафика, которого никто не требовал |
| глубокая перегрузка | потеря 22–49% пропускной способности |

Правило привязано к внешней цели. Пока цель комфортно выше достижимого минимума, оно работает; когда цель приближается к нему, выполнить её можно только перестав обслуживать — и контур честно к этому идёт. Библиотека не может определить, в каком режиме находится, поэтому включать `slo-target-headroom: 1.0` стоит там, где вы знаете, что SLO с запасом достижим.

## Guardrails

Поверх решений контроллера применяются ограничения:

- **min / max** — абсолютные границы параметра
- **maxStep** — максимальное увеличение за один тик
- **maxStepDown** — максимальное уменьшение за один тик; по умолчанию больше, чем `maxStep`
- **cooldownTicks** — минимальный интервал между изменениями
- **SLO violation block** — запрет на увеличение при нарушении SLO; включается для каждого актюатора отдельно (`blockIncreaseOnSloViolation`, по умолчанию `true`)

Шаг ограничен асимметрично намеренно. Увеличивать ресурс слишком быстро — способ устроить инцидент, поэтому рост ограничен жёстко. Уменьшать — способ из инцидента выйти, и одинаковое ограничение в обе стороны душило бы аварийный тормоз контура так же сильно, как и его газ. Контроллер, решивший вдвое срезать лимит, должен иметь возможность это сделать.

## JSONL Dataset Schema

Каждая итерация логирует строку JSON в логгер `softres.dataset`:

```json
{
  "schemaVersion": 3,
  "sequence": 1,
  "timestamp": "2026-02-14T11:20:05.123Z",
  "stateWindowStartedAt": "2026-02-14T11:20:00.123Z",
  "outcomeTimestamp": "2026-02-14T11:20:10.123Z",
  "windowSec": 5,
  "resourcesBefore": { "concurrency_limit": 120, "hikari_max_pool": 28 },
  "cooldownRemaining": { "concurrency_limit": 0, "hikari_max_pool": 0 },
  "state": {
    "latency_p95_ms": 42.1,
    "latency_p99_ms": 88.7,
    "goodput_rps": 315.4,
    "error_rate": 0.002,
    "timeout_rate": 0.000,
    "inflight": 120,
    "cpu_proc": 0.63,
    "gc_pause_p95_ms": 3.2,
    "hikari_active": 18,
    "hikari_idle": 6,
    "hikari_pending": 4,
    "hikari_acquire_p95_ms": 12.5,
    "hikari_acquire_mean_ms": 6.1
  },
  "action": {
    "concurrency_limit": 140,
    "hikari_max_pool": 28
  },
  "intent": {
    "concurrency_limit": 165
  },
  "reasons": {
    "concurrency_limit": "increase_headroom|clamped_max_step",
    "hikari_max_pool": "hold|no_change"
  },
  "outcome": {
    "latency_p95_ms": 47.9,
    "latency_p99_ms": 95.1,
    "goodput_rps": 332.0,
    "error_rate": 0.001,
    "timeout_rate": 0.000,
    "inflight": 134,
    "hikari_pending": 1,
    "hikari_acquire_p95_ms": 7.3
  }
}
```

Записи без `schemaVersion` относятся к прежней семантике: goodput мог включать отклонения, а HTTP-задержка исключала ожидание допуска. Их нельзя автоматически объединять с новыми данными для обучения. Архивные эксперименты требуют повторения на новой реализации.

### Намерение и причины

`action` — что применено, `intent` — чего хотел контроллер до guardrails, `reasons` — какое правило сработало и что с ним стало.

Обе половины нужны. Обучение только на применённом значении выучит guardrail, а не политику, и разметка молча станет неверной при первой же перенастройке ограничителей. А `reasons` отвечает на вопрос «почему контур не среагировал», который иначе требует отладчика на проде: тик без изменений может означать метрики в нейтральной полосе, идущий cooldown, SLO-блок или demand gate, не увидевший очереди, — и это разные состояния мира.

Причина записывается через `|`: слева решение контроллера, справа судьба этого решения в guardrails.

| Контроллер | |
|---|---|
| `increase_headroom` | лимит связывает, латентность комфортна |
| `hold_limit_not_binding` | расти незачем, в лимит никто не упирается |
| `hold_at_slo_target` | дальше расти не позволяет закон Литтла |
| `hold_in_band` | метрики между «комфортно» и «нарушение» |
| `decrease_slo_gradient` | снижение по градиенту `SLO / latency` |
| `decrease_slo_target` | цель по закону Литтла оказалась ниже градиента |
| `decrease_errors` / `decrease_timeouts` | снижение по ошибкам или таймаутам |
| `idle_hold` / `idle_shrink` | нет активного трафика |
| `increase_connection_deficit` | pending-потоки и высокое время получения соединения |
| `decrease_idle_connections` | соединения простаивают устойчиво |
| `rollback_after_increase` | латентность ухудшилась сразу после расширения пула |

| Guardrail | |
|---|---|
| `applied` | применено как есть |
| `clamped_max_step` / `clamped_max_step_down` | ограничено шагом |
| `clamped_bounds` | упёрлось в `[min, max]` |
| `suppressed_cooldown` | не прошло из-за cooldown |
| `suppressed_slo_block` | увеличение запрещено при нарушении SLO |
| `no_change` | решение совпало с текущим значением |

Первый же прогон стенда показал, ради чего это: `suppressed_cooldown` встретился в половине всех решений, а у регулятора пула — в двух третях. Пока причины не писались, узнать это было неоткуда.

### Тайминг outcome

- На тике **t**: записывается `state_t` и `action_t`
- На тике **t+1**: `outcome_t` заполняется метриками `state_{t+1}`, затем запись `t` логируется
- Последняя запись сессии может иметь `outcome: null`

## Расширяемость

Архитектура позволяет заменить baseline-контроллер на ML без изменения API:

```java
// Кастомный ML-контроллер
public class RLController implements Controller {
    @Override
    public ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        // ... inference вашей модели ...
        return ControlAction.builder()
                .set("concurrency_limit", predictedLimit)
                .set("hikari_max_pool", predictedPool)
                .build();
    }
}

// Подключение
ControlEngineConfig config = ControlEngineConfig.builder()
        .controller(new RLController())
        // ... остальная конфигурация ...
        .build();
```

## Метрики

### Ключи MetricNames (iasr-core)

| Ключ | Описание |
|------|----------|
| `latency_p95_ms` | p95 успешных запросов, включая ожидание допуска, мс |
| `latency_p99_ms` | p99 успешных запросов, включая ожидание допуска, мс |
| `latency_mean_ms` | Средняя задержка успешного запроса с ожиданием допуска, мс |
| `service_mean_ms` | Среднее время успешного запроса после допуска, мс |
| `goodput_rps` | RPS успешных бизнес-ответов HTTP 2xx |
| `error_rate` | Доля ошибок (0..1) |
| `timeout_rate` | Доля таймаутов concurrency limiter (0..1) |
| `inflight` | Текущее число запросов в обработке |
| `queue_length` | Потоки, ждущие permit прямо сейчас |
| `cpu_proc` | Загрузка CPU процесса (0..1) |
| `gc_pause_p95_ms` | GC pause p95 в мс |
| `hikari_active` | Активные соединения Hikari |
| `hikari_idle` | Свободные соединения Hikari |
| `hikari_pending` | Ожидающие соединения (pending) |
| `hikari_acquire_p95_ms` | Время получения соединения p95 в мс (затухающее окно Micrometer) |
| `hikari_acquire_mean_ms` | Среднее время получения соединения за окно Δt |

Метрики Hikari читаются по **фактическому имени пула**: Micrometer помечает каждый метр `hikaricp.*` тегом `pool`, и чтение под неверным именем не даёт ошибки — просто возвращает нули, из-за чего регулятор пула никогда не срабатывает, а в датасете появляется столбец нулей. Starter берёт имя у живого `HikariDataSource` и резолвит его лениво, потому что HikariCP присваивает сгенерированное имя только при старте пула.

Перцентильные метрики (`gc_pause_p95_ms`, `hikari_acquire_p95_ms`) требуют включённых percentile-гистограмм — этим занимается `IasrMeterFilterConfigurer`, который starter регистрирует автоматически. Без него значения недоступны, и провайдер один раз пишет предупреждение в лог и откатывается к максимуму по окну (заведомая переоценка) вместо того, чтобы молча отдавать нули.

### Micrometer Meters (iasr-micrometer)

| Meter | Тип | Описание |
|-------|-----|----------|
| `iasr.concurrency.inflight` | Gauge | In-flight запросы |
| `iasr.concurrency.limit` | Gauge | Текущий лимит |
| `iasr.concurrency.queue` | Gauge | Очередь ожидающих permit |
| `iasr.concurrency.timeouts` | Counter | Кумулятивные таймауты |
| `iasr.http.errors` | Counter | Кумулятивные 5xx ошибки |
| `iasr.http.requests` | Counter | Завершённые бизнес-запросы, включая отклонения и ошибки |
| `iasr.http.successes` | Counter | Успешные бизнес-ответы HTTP 2xx |

## Сборка

```bash
mvn clean install
```

## Нагрузочный стенд

Модуль `iasr-benchmark` отвечает на вопрос, который тесты не покрывают: **лучше ли с контуром, чем без него, и какой ценой**. Он поднимает приложение с синтетической зависимостью известной ёмкости, подаёт нагрузку по сценариям (пик, плавный рост, деградация зависимости) и сравнивает три конфигурации — без admission control, со статическим лимитом и с работающим контуром.

```bash
mvn -q install -DskipTests && mvn -pl iasr-benchmark exec:java
```

Методология и ограничения — [docs/benchmark/methodology.md](docs/benchmark/methodology.md), результаты последнего прогона — [docs/benchmark/report.md](docs/benchmark/report.md).

Ключевой момент методологии: содержательное сравнение — это **статический лимит против адаптивного**, а не «IASR против без IASR». В обоих режимах работает один и тот же лимитер с одинаковым стартовым значением, поэтому разница относится к управляющему контуру, а не к самому факту наличия семафора. Второй вариант записал бы на счёт контура работу обычного лимитера.

## Тесты

```bash
mvn test
```

Покрыты инварианты, которые нельзя проверить чтением кода: permit debt семафора под конкурентным resize, длина cooldown и его поведение на старте, политика SLO-блока по каждому актюатору, протокол `state → action → outcome` с отложенным flush, независимость соседних окон латентности, чтение настоящих перцентилей (а не максимума) и откат к максимуму без percentile-гистограмм, а также разводка `enabled` / `apply-actions` в автоконфигурации Spring Boot.

Тесты писались от конкретных дефектов: каждый регрессионный тест проверен обратной мутацией — баг возвращается в исходный код, и тест обязан упасть. Тест, проходящий и с багом, и без него, ничего не охраняет.

## Требования

- Java 17+
- Maven 3.8+
- Spring Boot 3.x (для starter-модуля)
