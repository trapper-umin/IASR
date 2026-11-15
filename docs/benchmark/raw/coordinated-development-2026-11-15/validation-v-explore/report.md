# IASR — результаты нагрузочного стенда

Прогон от 2026-11-15. Сгенерировано `iasr-benchmark`, воспроизводится командой в конце документа.

## Как читать

Сравнение, ради которого всё затевалось, — **fixed против adaptive**. В обоих режимах работает один и тот же лимитер с одинаковым стартовым значением, поэтому разница между ними относится к управляющему контуру, а не к самому факту наличия лимитера. Режим **off** показывает отдельно, чего стоит admission control вообще.

Латентность измеряется от момента, когда запрос **должен был** быть отправлен, а не когда фактически ушёл: иначе отставание клиента улучшало бы перцентили ровно тогда, когда системе плохо. Перцентили считаются только по успешным ответам, поэтому доля 429 и доля жёстких отказов приводятся рядом — без них картина неполна, а режим с агрессивным шеддингом выглядел бы незаслуженно хорошо.

SLO: **p95 ≤ 150 мс**.

## Сводка

| Сценарий | Режим | Goodput, rps | p95, мс | p99, мс | 429 | Жёсткие отказы | Нарушений SLO |
|---|---|---:|---:|---:|---:|---:|---:|
| steady | explore-coordinated | 35.9 | 644.0 | 1913.0 | 33.6% | 0.0% | 9.0% |
| steady | explore-coordinated-hold | 34.0 | 462.0 | 1580.0 | 37.1% | 0.0% | 17.9% |
| spike | explore-coordinated | 36.8 | 193.0 | 1098.0 | 66.6% | 0.0% | 6.8% |
| spike | explore-coordinated-hold | 37.0 | 331.0 | 1687.0 | 66.3% | 0.0% | 26.9% |
| ramp | explore-coordinated | 41.2 | 283.0 | 1171.0 | 67.3% | 0.0% | 30.0% |
| ramp | explore-coordinated-hold | 36.8 | 418.0 | 1804.0 | 70.8% | 0.0% | 21.9% |
| slow-dependency | explore-coordinated | 30.5 | 374.0 | 1669.0 | 54.8% | 0.0% | 36.2% |
| slow-dependency | explore-coordinated-hold | 30.2 | 408.0 | 1814.0 | 55.3% | 0.0% | 45.2% |

## Сценарий `steady`

_Does the loop do any harm when load is comfortably within capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 35.9 | 35.0 | 644.0 | 1913.0 | 2136.0 | 33.6% | 0.0% |
| explore-coordinated-hold | 34.0 | 79.0 | 462.0 | 1580.0 | 1972.0 | 37.1% | 0.0% |

## Сценарий `spike`

_Under a sudden 3x burst, does the loop protect latency, and does it recover after?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 36.8 | 121.0 | 193.0 | 1098.0 | 2164.0 | 66.6% | 0.0% |
| explore-coordinated-hold | 37.0 | 129.0 | 331.0 | 1687.0 | 2201.0 | 66.3% | 0.0% |

## Сценарий `ramp`

_As load crosses capacity, where does each configuration start to break?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 41.2 | 135.0 | 283.0 | 1171.0 | 2469.0 | 67.3% | 0.0% |
| explore-coordinated-hold | 36.8 | 134.0 | 418.0 | 1804.0 | 2385.0 | 70.8% | 0.0% |

## Сценарий `slow-dependency`

_When the dependency degrades under steady load, does the loop adapt to the new capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 30.5 | 128.0 | 374.0 | 1669.0 | 2111.0 | 54.8% | 0.0% |
| explore-coordinated-hold | 30.2 | 137.0 | 408.0 | 1814.0 | 2120.0 | 55.3% | 0.0% |

## Файлы

- `results.csv` — сводка по представительным прогонам
- `raw/actuator-trace.csv` — значения актюаторов во времени
- `raw/dataset-<сценарий>-<режим>.jsonl` — журнал представительного прогона
- `episodes/<runId>/` — все повторы отдельно, с конфигурацией и границами прогрева

## Воспроизведение

```bash
mvn -q install -DskipTests && mvn -pl iasr-benchmark exec:java
```
