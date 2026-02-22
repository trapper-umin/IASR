# IASR — Intelligent Adaptive Soft-Resource Library

Runtime-контур управления (control loop) для динамической адаптации мягких ресурсов JVM-приложения **без перезапуска**.

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

`ConcurrencyLimitFilter` фильтрует инфраструктурные пути (`/actuator`, `/health`, `/prometheus`, `/live`, `/ready`) — их латентность не попадает в tracker и не загрязняет сигнал управляющего контура.

### 5. BaselineController (AIMD) + idle-shrink
Детерминированный контроллер с пороговой логикой. Подробнее — в разделе [Baseline Controller](#baseline-controller-aimd).

Дополнительно: при отсутствии реального трафика (`goodput < 1 RPS`) более 24 тиков подряд контроллер постепенно уменьшает лимиты (`idle-shrink`), возвращая их к минимальным значениям. Это освобождает системные ресурсы в периоды простоя и гарантирует, что при возобновлении нагрузки контроллер начнёт с небольшого значения и будет наращивать его по AIMD, а не сохранит пик предыдущей сессии.

### 6. Guardrails: многослойный предохранитель
Guardrail — это независимый уровень безопасности поверх любого контроллера. Порядок применения для каждого актюатора:
1. **Cooldown check** — подавить изменение, если после последнего прошло меньше N тиков.
2. **SLO violation block** — запретить увеличение ресурса при нарушении SLO (защита от раскачки).
3. **maxStep clamp** — ограничить шаг изменения за один тик.
4. **absolute clamp** — зажать итоговое значение в `[min, max]`.

Счётчик cooldown не уменьшается при подавлении изменения — только при реальном применении. Это гарантирует корректную длину cooldown вне зависимости от того, сколько раз было подавлено изменение.

### 7. Dataset Logger: state → action → outcome через отложенный flush
На тике **t**: запись `state_t` и `action_t` сохраняется в памяти (`pending`).
На тике **t+1**: `pending` дополняется `outcome_t = state_{t+1}` и логируется в виде одной JSON-строки в SLF4J-логгер `softres.dataset`.
При shutdown: `flush()` записывает последнюю запись с `outcome: null`.

Это единственный способ построить датасет формата `(s, a, s')` без задержки хранения — вся логика умещается в одном объекте `DatasetLogger`, без внешней БД.

### 8. Spring Boot Starter: classpath-safe автоконфигурация
`IasrAutoConfiguration` активируется только при наличии `MeterRegistry` в контексте. Hikari-специфичный код вынесен во внутренний `@Configuration`-класс `HikariActuatorConfiguration`, защищённый **строковым** `@ConditionalOnClass` (имена классов как строки). При использовании `.class`-литералов Java разрешает их при загрузке outer-класса, что вызывает `NoClassDefFoundError`, если HikariCP отсутствует. Строковые условия Spring вычисляет через ASM-байткод без загрузки классов.

`ControlEngine` получает все `Actuator`-бины через `List<Actuator>` из контекста Spring, а не через typed reference — это позволяет подключать произвольные кастомные актюаторы без изменения кода автоконфигурации.

### 9. Observe-only режим
При `iasr.enabled: false` control loop запускается, собирает метрики и пишет датасет, но **не применяет** действия к актюаторам. Это позволяет:
- безопасно ввести IASR в production в режиме наблюдения;
- накопить обучающий датасет с человеческой/внешней политикой управления;
- валидировать, что метрики собираются корректно, до включения автоуправления.

## Модули

| Модуль | Artifact | Описание |
|--------|----------|----------|
| **iasr-core** | `com.iasr:iasr-core` | Ядро: интерфейсы, контроллер, актюаторы, guardrails, dataset logger. Нет зависимостей на Spring/Micrometer |
| **iasr-micrometer** | `com.iasr:iasr-micrometer` | Адаптер MetricsProvider → Micrometer MeterRegistry |
| **iasr-spring-boot-starter** | `com.iasr:iasr-spring-boot-starter` | Автоконфигурация для Spring Boot 3.x |

## Быстрый старт (Spring Boot)

### 1. Добавить зависимость

```xml
<dependency>
    <groupId>com.iasr</groupId>
    <artifactId>iasr-spring-boot-starter</artifactId>
    <version>1.0.12</version>
</dependency>
```

### 2. Настроить `application.yml`

```yaml
iasr:
  enabled: true
  slo-latency-ms: 200
  window-ms: 5000
  dataset-logging-enabled: true

  concurrency:
    initial-limit: 100
    min-limit: 10
    max-limit: 500
    max-step: 20
    cooldown-ticks: 3
    acquire-timeout-ms: 500

  hikari:
    min-pool-size: 5
    max-pool-size: 50
    max-step: 2
    cooldown-ticks: 5

  controller:
    comfort-factor: 0.7
    concurrency-increase-step: 5
    concurrency-decrease-factor: 0.85
    error-rate-threshold: 0.01
    timeout-rate-threshold: 0.005
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
        .addGuardrail(new ActuatorGuardrail("concurrency_limit", 10, 500, 20, 3))
        .addGuardrail(new ActuatorGuardrail("hikari_max_pool", 5, 50, 2, 5))
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
| `latency_p95 < 0.7 × SLO` И `errors ≈ 0` И `timeouts ≈ 0` | `limit += k` (additive increase) |
| `latency_p95 > SLO` ИЛИ `timeouts↑` ИЛИ `errors↑` | `limit *= β` (multiplicative decrease, β=0.85) |
| Иначе | Hold (без изменений) |

### Hikari Pool Size

| Условие | Действие |
|---------|----------|
| `pending > 0` И `acquire_time↑` И `latency < SLO` | `pool += 1` |
| Нет pending 10+ тиков | `pool -= 1` |
| `latency > SLO` И `latency↑↑` | `pool -= 1` (возможная перегрузка БД) |
| Иначе | Hold |

## Guardrails

Поверх решений контроллера применяются ограничения:

- **min / max** — абсолютные границы параметра
- **maxStep** — максимальное изменение за один тик
- **cooldownTicks** — минимальный интервал между изменениями
- **SLO violation block** — запрет на увеличение при нарушении SLO

## JSONL Dataset Schema

Каждая итерация логирует строку JSON в логгер `softres.dataset`:

```json
{
  "timestamp": "2026-02-14T11:20:05.123Z",
  "windowSec": 5,
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
    "hikari_acquire_p95_ms": 12.5
  },
  "action": {
    "concurrency_limit": 140,
    "hikari_max_pool": 28
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
| `latency_p95_ms` | Латентность p95 в мс |
| `latency_p99_ms` | Латентность p99 в мс |
| `goodput_rps` | RPS успешных ответов |
| `error_rate` | Доля ошибок (0..1) |
| `timeout_rate` | Доля таймаутов concurrency limiter (0..1) |
| `inflight` | Текущее число запросов в обработке |
| `cpu_proc` | Загрузка CPU процесса (0..1) |
| `gc_pause_p95_ms` | GC pause p95 в мс |
| `hikari_active` | Активные соединения Hikari |
| `hikari_idle` | Свободные соединения Hikari |
| `hikari_pending` | Ожидающие соединения (pending) |
| `hikari_acquire_p95_ms` | Время получения соединения p95 в мс |

### Micrometer Meters (iasr-micrometer)

| Meter | Тип | Описание |
|-------|-----|----------|
| `iasr.concurrency.inflight` | Gauge | In-flight запросы |
| `iasr.concurrency.limit` | Gauge | Текущий лимит |
| `iasr.concurrency.queue` | Gauge | Очередь ожидающих permit |
| `iasr.concurrency.timeouts` | Counter | Кумулятивные таймауты |
| `iasr.http.errors` | Counter | Кумулятивные 5xx ошибки |

## Сборка

```bash
mvn clean install
```

## Требования

- Java 17+
- Maven 3.8+
- Spring Boot 3.x (для starter-модуля)
