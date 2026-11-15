# IASR — результаты нагрузочного стенда

Прогон от 2026-11-15. Сгенерировано `iasr-benchmark`, воспроизводится командой в конце документа.

## Как читать

Сравнение, ради которого всё затевалось, — **fixed против adaptive**. В обоих режимах работает один и тот же лимитер с одинаковым стартовым значением, поэтому разница между ними относится к управляющему контуру, а не к самому факту наличия лимитера. Режим **off** показывает отдельно, чего стоит admission control вообще.

Латентность измеряется от момента, когда запрос **должен был** быть отправлен, а не когда фактически ушёл: иначе отставание клиента улучшало бы перцентили ровно тогда, когда системе плохо. Перцентили считаются только по успешным ответам, поэтому доля 429 и доля жёстких отказов приводятся рядом — без них картина неполна, а режим с агрессивным шеддингом выглядел бы незаслуженно хорошо.

SLO: **p95 ≤ 150 мс**.

## Сводка

| Сценарий | Режим | Goodput, rps | p95, мс | p99, мс | 429 | Жёсткие отказы | Нарушений SLO |
|---|---|---:|---:|---:|---:|---:|---:|
| steady | explore-coordinated | 29.4 | 228.0 | 844.0 | 23.4% | 0.0% | 10.4% |
| steady | explore-coordinated-hold | 29.8 | 488.0 | 1213.0 | 22.5% | 0.0% | 8.8% |
| spike | explore-coordinated | 30.0 | 200.0 | 968.0 | 61.7% | 0.0% | 10.5% |
| spike | explore-coordinated-hold | 32.0 | 381.0 | 1050.0 | 59.1% | 0.0% | 33.2% |
| ramp | explore-coordinated | 30.5 | 506.0 | 1744.0 | 66.0% | 0.0% | 37.5% |
| ramp | explore-coordinated-hold | 31.0 | 335.0 | 1414.0 | 65.3% | 0.0% | 33.1% |
| slow-dependency | explore-coordinated | 24.2 | 231.0 | 1613.0 | 49.6% | 0.0% | 36.5% |
| slow-dependency | explore-coordinated-hold | 24.6 | 302.0 | 2121.0 | 48.8% | 0.0% | 42.2% |

## Сценарий `steady`

_Does the loop do any harm when load is comfortably within capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 29.4 | 41.0 | 228.0 | 844.0 | 1254.0 | 23.4% | 0.0% |
| explore-coordinated-hold | 29.8 | 41.0 | 488.0 | 1213.0 | 2012.0 | 22.5% | 0.0% |

## Сценарий `spike`

_Under a sudden 3x burst, does the loop protect latency, and does it recover after?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 30.0 | 132.0 | 200.0 | 968.0 | 2159.0 | 61.7% | 0.0% |
| explore-coordinated-hold | 32.0 | 109.0 | 381.0 | 1050.0 | 2453.0 | 59.1% | 0.0% |

## Сценарий `ramp`

_As load crosses capacity, where does each configuration start to break?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 30.5 | 142.0 | 506.0 | 1744.0 | 2160.0 | 66.0% | 0.0% |
| explore-coordinated-hold | 31.0 | 141.0 | 335.0 | 1414.0 | 1914.0 | 65.3% | 0.0% |

## Сценарий `slow-dependency`

_When the dependency degrades under steady load, does the loop adapt to the new capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 24.2 | 136.0 | 231.0 | 1613.0 | 2036.0 | 49.6% | 0.0% |
| explore-coordinated-hold | 24.6 | 136.0 | 302.0 | 2121.0 | 2412.0 | 48.8% | 0.0% |

## Файлы

- `results.csv` — сводка по представительным прогонам
- `raw/actuator-trace.csv` — значения актюаторов во времени
- `raw/dataset-<сценарий>-<режим>.jsonl` — журнал представительного прогона
- `episodes/<runId>/` — все повторы отдельно, с конфигурацией и границами прогрева

## Воспроизведение

```bash
mvn -q install -DskipTests && mvn -pl iasr-benchmark exec:java
```
