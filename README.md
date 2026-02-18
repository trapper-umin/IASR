# IASR — Intelligent Adaptive Soft-Resource Library

Runtime-контур управления (control loop) для динамической адаптации мягких ресурсов JVM-приложения **без перезапуска**.

## Архитектура

```
┌─────────────────────────────────────────────────────────┐
│                   iasr-core (0 deps*)                   │
│  ┌──────────┐  ┌────────────┐  ┌───────────────────┐    │
│  │ Metrics  │  │ Controller │  │    Actuators      │    │
│  │ Provider │──│ (Baseline/ │──│ ConcurrencyLimiter│    │
│  │ (SPI)    │  │  ML-ready) │  │ HikariPoolActuator│    │
│  └──────────┘  └────────────┘  └───────────────────┘    │
│         │              │               │                │
│         ▼              ▼               ▼                │
│  ┌──────────────────────────────────────────────────┐   │
│  │            ControlEngine (Δt loop)               │   │
│  │   collect → decide → guardrail → apply → log     │   │
│  └──────────────────────────────────────────────────┘   │
│         │                                               │
│  ┌──────────────┐  ┌────────────┐                       │
│  │ Guardrails   │  │ Dataset    │                       │
│  │ (min/max/    │  │ Logger     │                       │
│  │  cooldown)   │  │ (JSONL)    │                       │
│  └──────────────┘  └────────────┘                       │
└─────────────────────────────────────────────────────────┘
         * только slf4j-api + HikariCP (provided)

┌──────────────────────┐   ┌────────────────────────────┐
│  iasr-micrometer     │   │  iasr-spring-boot-starter  │
│  MicrometerMetrics   │   │  AutoConfiguration         │
│  Provider            │   │  ConcurrencyLimitFilter    │
│  IasrMeterBinder     │   │  IasrProperties            │
└──────────────────────┘   └────────────────────────────┘
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
│       │   ├── MetricsProvider.java       SPI — абстрактный источник метрик
│       │   ├── MetricsSnapshot.java       Immutable bag of named double values
│       │   └── MetricNames.java           Константы ключей метрик
│       ├── actuator/
│       │   ├── Actuator.java              SPI — управляемый ресурс
│       │   ├── ConcurrencyLimiter.java    Semaphore + runtime resize
│       │   └── HikariPoolActuator.java    HikariCP maximumPoolSize
│       ├── controller/
│       │   ├── Controller.java            SPI — стратегия принятия решений
│       │   ├── ControlAction.java         Immutable set of desired values
│       │   └── BaselineController.java    AIMD threshold controller
│       ├── guardrail/
│       │   ├── ActuatorGuardrail.java     Per-actuator constraints (record)
│       │   └── Guardrails.java            Enforcer: clamp, cooldown, SLO block
│       ├── dataset/
│       │   ├── DatasetRecord.java         JSONL row + hand-rolled serialization
│       │   └── DatasetLogger.java         SLF4J logger "softres.dataset"
│       ├── config/
│       │   └── ControlEngineConfig.java   Builder-based immutable config
│       └── engine/
│           └── ControlEngine.java         ScheduledExecutorService control loop
│
├── iasr-micrometer/                   ← Адаптер к Micrometer
│   ├── pom.xml
│   └── src/main/java/com/iasr/micrometer/
│       ├── MicrometerMetricsProvider.java  MetricsProvider → MeterRegistry
│       └── IasrMeterBinder.java           Регистрация кастомных Gauge/Counter
│
└── iasr-spring-boot-starter/          ← Spring Boot 3.x Starter
    ├── pom.xml
    └── src/main/java/com/iasr/spring/
    │   ├── IasrAutoConfiguration.java     @AutoConfiguration + bean wiring
    │   ├── IasrProperties.java            @ConfigurationProperties (prefix "iasr")
    │   └── ConcurrencyLimitFilter.java    Servlet Filter (429 при таймауте)
    └── src/main/resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```
## Ключевые решения архитектуры
- Разделение ядро / адаптеры — iasr-core не имеет compile-time зависимостей на Micrometer, Spring, Jackson. Ядро максимально лёгкое.
- SPI-интерфейсы — MetricsProvider, Controller, Actuator — все точки расширения. ML-контроллер подключается заменой одного интерфейса.
- ConcurrencyLimiter — fair Semaphore с атомарным runtime-resize: увеличение — мгновенный release(delta), уменьшение — eager reclaim через tryAcquire() без блокировки.
- BaselineController (AIMD) — additive increase +k, multiplicative decrease ×0.85. Для Hikari — ещё более консервативная логика (изменение реже и на 1 шаг).
- Guardrails — min/max + maxStep + cooldown + SLO violation block — применяются поверх решений любого контроллера.
- Dataset Logger — state_t → action_t → outcome_{t+1} через отложенное заполнение outcome на следующем тике. Логируется через SLF4J logger softres.dataset в формате JSONL.
- Spring Boot Starter — автоматически создаёт ConcurrencyLimiter, находит HikariDataSource, регистрирует фильтр и запускает ControlEngine. Все настройки через application.yml.

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
    <version>1.0.0-SNAPSHOT</version>
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
<appender name="DATASET" class="ch.qos.logback.core.FileAppender">
    <file>logs/iasr-dataset.jsonl</file>
    <encoder>
        <pattern>%msg%n</pattern>
    </encoder>
</appender>

<logger name="softres.dataset" level="INFO" additivity="false">
    <appender-ref ref="DATASET"/>
</logger>
```

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
