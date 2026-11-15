# ПРИЛОЖЕНИЕ А. ПАРАМЕТРЫ ЭКСПЕРИМЕНТОВ

Таблица: Параметры ограничителей и базового регулятора на стенде {#tbl:guard}
| Параметр | Предел конкурентности | Пул соединений |
|---|---|---|
| Диапазон | 2–400 | 2–16 |
| Наибольший шаг увеличения | 20 | 2 |
| Наибольший шаг уменьшения | 50 | 2 |
| Период охлаждения, тактов | 1 | 2 |
| Блокировка увеличения при нарушении SLO | да | нет |
| Ожидание разрешения фильтром, мс | 100 | — |

Параметры базового регулятора: доля SLO для комфортного состояния 0,7; шаг увеличения предела 1; множитель геометрического снижения 0,85; нижняя граница градиента 0,9; порог востребованности 0,9; пороги долей ошибок и отклонений 0,01 и 0,005; шаг пула 1; порог времени получения соединения 10 мс. SLO 150 мс, окно управления 1 с, бюджет времени решения политики 20 мс.

Таблица: Гиперпараметры обучения итоговой модели {#tbl:hyper}
| Параметр | Значение |
|---|---|
| Число итераций FQI | 30 |
| Число деревьев в ансамблях V и A | 60 |
| Минимальное число примеров в листе | 10 |
| Доля признаков при разбиении | 0,6 |
| Коэффициент дисконтирования | 0,9 с⁻¹ |
| Минимальная поддержка направления в виде состояния | 8 |
| Коэффициент доверия κ | 0,25 |
| Порог преимущества θ | 0 |
| Вес сглаживания признаков α | 0,4 |
| Цена изменения в вознаграждении | 0,02 |
| Зерно генератора | 20261115 |
| Обучающих переходов | 4375 |
| SHA-256 модели | 7bab153565a422aa00dd391e0c419d782bb6c515439a9a6b0b1fc036ae810a6e |

Таблица: Планы экспериментов с согласованной политикой {#tbl:plans}
| План | Назначение | Режимы | Эпизодов |
|---|---|---|---|
| development-2026-11-15 | сбор обучающих и валидационных данных | исследование (два якоря), AIMD | 92 |
| policy-iteration-2026-11-15 | итерация политики на обучающих условиях | политика | 16 |
| validation-closed-loop | три валидации в замкнутом контуре | AIMD, политика | по 8 пар |
| evaluation-2026-11-15 | отложенная проверка | AIMD, политика, политика с закреплённым пулом | 72 |
| confirmation-2026-11-15 | подтверждающий прогон | AIMD, политика | 128 |

# ПРИЛОЖЕНИЕ Б. ФОРМАТ ЖУРНАЛА ЭПИЗОДА

Пример записи журнала (схема версии 3, состояние и исход сокращены):

```
{"schemaVersion": 3, "sequence": 4,
 "timestamp": "2026-11-15T08:59:35.017Z", "windowSec": 1,
 "resourcesBefore": {"concurrency_limit": 9, "hikari_max_pool": 6},
 "cooldownRemaining": {"concurrency_limit": 1, "hikari_max_pool": 1},
 "state": {"latency_p95_ms": 48.3, "latency_mean_ms": 42.3,
           "slo_good_fraction": 1, "goodput_rps": 60.9,
           "error_rate": 0, "timeout_rate": 0,
           "inflight": 2, "queue_length": 0,
           "admission_wait_mean_ms": 0, "service_mean_ms": 42.3,
           "hikari_active": 2, "hikari_pending": 0,
           "hikari_acquire_mean_ms": 0, "dependency_mean_ms": 42.3, ...},
 "action": {"concurrency_limit": 9, "hikari_max_pool": 6},
 "intent": {},
 "reasons": {"concurrency_limit": "coordinated_baseline:hold_limit_not_binding",
             "hikari_max_pool": "coordinated_baseline:hold"},
 "outcome": {"latency_p95_ms": 177.7, "slo_good_fraction": 0.939,
             "goodput_rps": 32.9, "timeout_rate": 0.298, ...}}
```

Поле `intent` содержит итоговое предложение регулятора до ограничений (в окнах отступления обученной политики — её решение) и пусто, если регулятор предложил сохранить значения. В журналах, записанных после экспериментов главы 4, обученная политика добавляет поле `baselineIntent` с предложением базового регулятора. Поле `reasons` указывает для каждого ресурса, кто принял решение: `coordinated_policy` — обученная политика, `coordinated_baseline:<причина>` — базовый регулятор.

# ПРИЛОЖЕНИЕ В. ПРАВИЛО ПРИНЯТИЯ РЕШЕНИЯ (ЭТАЛОННАЯ РЕАЛИЗАЦИЯ)

Эталонная реализация правила раздела 2.6 на Python (модуль `iasr_learning.runtime`); реализация в JVM (`PolicyModel.choose`) повторяет её и проверяется на совпадение решений.

```
def improvement(policy, state, move, reference):
    """Нижняя доверительная граница преимущества хода над опорой."""
    a = tree_values(policy["forest"], state + move)
    b = tree_values(policy["forest"], state + reference)
    d = [x - y for x, y in zip(a, b)]
    n = len(d)
    mean = sum(d) / n
    var = sum((x - mean) ** 2 for x in d) / (n - 1) if n > 1 else 0.0
    return mean - policy.get("confidence", 0.0) * var ** 0.5


def decide(policy, point):
    counts = policy["support"]["bins"]
    minimum = policy["support"]["minSamples"]
    reference = point.get("reference", point["capacities"])
    reference_features = point.get("referenceFeatures",
                                   point["candidateFeatures"][0])
    best, best_score, best_distance = None, None, None
    for option, action in zip(point["candidates"],
                              point["candidateFeatures"]):
        direction = F.direction(point["capacities"], option)
        if option == reference or \
           counts.get(point["bin"], {}).get(direction, 0) < minimum:
            continue
        score = improvement(policy, point["state"], action,
                            reference_features)
        distance = sum(abs(t - r) for r, t in zip(reference, option))
        if best is None or score > best_score or \
           (score == best_score and distance < best_distance):
            best, best_score, best_distance = option, score, distance
    if best is None or best_score <= policy["margin"]:
        return list(reference), "baseline"
    return best, "policy"
```

# ПРИЛОЖЕНИЕ Г. ВОСПРОИЗВЕДЕНИЕ РЕЗУЛЬТАТОВ

Сборка библиотеки и тесты (Java 17, Maven):

```
mvn clean install
```

Фиксация classpath стенда и запуск плана на временном кластере PostgreSQL:

```
python3 iasr-learning/tools/stand.py freeze /tmp/iasr-classpath
python3 iasr-learning/tools/stand.py run \
    iasr-learning/plans/confirmation-2026-11-15.json \
    --out /tmp/iasr-confirm --classpath /tmp/iasr-classpath
```

Парное сравнение режимов:

```
cd iasr-learning
python3 -m iasr_learning.compare /tmp/iasr-confirm --reference adaptive
```

Обучение модели по собранным эпизодам и экспорт эталонных решений для JVM:

```
python3 -m iasr_learning.train --train <обучающие эпизоды> \
    --validation <валидационные эпизоды> --out <каталог модели>
python3 -m iasr_learning.fixtures <каталог модели>/policy.json <эпизод> \
    --out fixture.json
```

Исходные данные всех серий хранятся в каталоге `docs/benchmark/raw` репозитория, отчёты и сравнения — в `docs/benchmark/analysis`, модель — в `docs/benchmark/models/coordinated-2026-11-15`. Каждый прогон содержит файл `execution.json` с SHA-256 плана, версией кода и версией PostgreSQL.
