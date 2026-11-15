# IASR — результаты нагрузочного стенда

Прогон от 2026-11-15. Сгенерировано `iasr-benchmark`, воспроизводится командой в конце документа.

## Как читать

Сравнение, ради которого всё затевалось, — **fixed против adaptive**. В обоих режимах работает один и тот же лимитер с одинаковым стартовым значением, поэтому разница между ними относится к управляющему контуру, а не к самому факту наличия лимитера. Режим **off** показывает отдельно, чего стоит admission control вообще.

Латентность измеряется от момента, когда запрос **должен был** быть отправлен, а не когда фактически ушёл: иначе отставание клиента улучшало бы перцентили ровно тогда, когда системе плохо. Перцентили считаются только по успешным ответам, поэтому доля 429 и доля жёстких отказов приводятся рядом — без них картина неполна, а режим с агрессивным шеддингом выглядел бы незаслуженно хорошо.

SLO: **p95 ≤ 150 мс**.

## Сводка

| Сценарий | Режим | Goodput, rps | p95, мс | p99, мс | 429 | Жёсткие отказы | Нарушений SLO |
|---|---|---:|---:|---:|---:|---:|---:|
| steady | explore-coordinated | 48.0 | 33.0 | 38.0 | 0.0% | 0.0% | 0.0% |
| steady | explore-coordinated-hold | 48.0 | 33.0 | 37.0 | 0.0% | 0.0% | 0.0% |
| spike | explore-coordinated | 80.9 | 214.0 | 225.0 | 17.2% | 0.0% | 32.2% |
| spike | explore-coordinated-hold | 88.4 | 173.0 | 201.0 | 9.6% | 0.0% | 18.3% |
| ramp | explore-coordinated | 53.2 | 192.0 | 245.0 | 52.5% | 0.0% | 42.6% |
| ramp | explore-coordinated-hold | 45.1 | 203.0 | 233.0 | 59.8% | 0.0% | 63.4% |
| slow-dependency | explore-coordinated | 45.3 | 264.0 | 322.0 | 24.6% | 0.0% | 27.6% |
| slow-dependency | explore-coordinated-hold | 39.1 | 669.0 | 1794.0 | 34.9% | 0.0% | 37.6% |

## Сценарий `steady`

_Does the loop do any harm when load is comfortably within capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 48.0 | 29.0 | 33.0 | 38.0 | 61.0 | 0.0% | 0.0% |
| explore-coordinated-hold | 48.0 | 29.0 | 33.0 | 37.0 | 76.0 | 0.0% | 0.0% |

## Сценарий `spike`

_Under a sudden 3x burst, does the loop protect latency, and does it recover after?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 80.9 | 134.0 | 214.0 | 225.0 | 492.0 | 17.2% | 0.0% |
| explore-coordinated-hold | 88.4 | 128.0 | 173.0 | 201.0 | 388.0 | 9.6% | 0.0% |

## Сценарий `ramp`

_As load crosses capacity, where does each configuration start to break?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 53.2 | 147.0 | 192.0 | 245.0 | 1025.0 | 52.5% | 0.0% |
| explore-coordinated-hold | 45.1 | 156.0 | 203.0 | 233.0 | 467.0 | 59.8% | 0.0% |

## Сценарий `slow-dependency`

_When the dependency degrades under steady load, does the loop adapt to the new capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 45.3 | 53.0 | 264.0 | 322.0 | 404.0 | 24.6% | 0.0% |
| explore-coordinated-hold | 39.1 | 119.0 | 669.0 | 1794.0 | 2435.0 | 34.9% | 0.0% |

## Файлы

- `results.csv` — сводка по представительным прогонам
- `raw/actuator-trace.csv` — значения актюаторов во времени
- `raw/dataset-<сценарий>-<режим>.jsonl` — журнал представительного прогона
- `episodes/<runId>/` — все повторы отдельно, с конфигурацией и границами прогрева

## Воспроизведение

```bash
mvn -q install -DskipTests && mvn -pl iasr-benchmark exec:java
```
