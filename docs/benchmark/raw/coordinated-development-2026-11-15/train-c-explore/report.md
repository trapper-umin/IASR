# IASR — результаты нагрузочного стенда

Прогон от 2026-11-15. Сгенерировано `iasr-benchmark`, воспроизводится командой в конце документа.

## Как читать

Сравнение, ради которого всё затевалось, — **fixed против adaptive**. В обоих режимах работает один и тот же лимитер с одинаковым стартовым значением, поэтому разница между ними относится к управляющему контуру, а не к самому факту наличия лимитера. Режим **off** показывает отдельно, чего стоит admission control вообще.

Латентность измеряется от момента, когда запрос **должен был** быть отправлен, а не когда фактически ушёл: иначе отставание клиента улучшало бы перцентили ровно тогда, когда системе плохо. Перцентили считаются только по успешным ответам, поэтому доля 429 и доля жёстких отказов приводятся рядом — без них картина неполна, а режим с агрессивным шеддингом выглядел бы незаслуженно хорошо.

SLO: **p95 ≤ 150 мс**.

## Сводка

| Сценарий | Режим | Goodput, rps | p95, мс | p99, мс | 429 | Жёсткие отказы | Нарушений SLO |
|---|---|---:|---:|---:|---:|---:|---:|
| steady | explore-coordinated | 56.6 | 684.0 | 1999.0 | 21.4% | 0.0% | 10.2% |
| steady | explore-coordinated-hold | 58.3 | 143.0 | 739.0 | 19.1% | 0.0% | 4.9% |
| spike | explore-coordinated | 58.5 | 182.0 | 762.0 | 60.1% | 0.0% | 7.1% |
| spike | explore-coordinated-hold | 61.1 | 316.0 | 1257.0 | 58.3% | 0.0% | 23.2% |
| ramp | explore-coordinated | 60.0 | 182.0 | 797.0 | 64.3% | 0.0% | 10.6% |
| ramp | explore-coordinated-hold | 59.7 | 207.0 | 947.0 | 64.5% | 0.0% | 17.5% |
| slow-dependency | explore-coordinated | 44.0 | 372.0 | 1811.0 | 51.1% | 0.0% | 20.6% |
| slow-dependency | explore-coordinated-hold | 46.8 | 1378.0 | 2077.0 | 48.0% | 0.0% | 34.6% |

## Сценарий `steady`

_Does the loop do any harm when load is comfortably within capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 56.6 | 27.0 | 684.0 | 1999.0 | 2403.0 | 21.4% | 0.0% |
| explore-coordinated-hold | 58.3 | 26.0 | 143.0 | 739.0 | 1268.0 | 19.1% | 0.0% |

## Сценарий `spike`

_Under a sudden 3x burst, does the loop protect latency, and does it recover after?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 58.5 | 118.0 | 182.0 | 762.0 | 1753.0 | 60.1% | 0.0% |
| explore-coordinated-hold | 61.1 | 34.0 | 316.0 | 1257.0 | 2202.0 | 58.3% | 0.0% |

## Сценарий `ramp`

_As load crosses capacity, where does each configuration start to break?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 60.0 | 124.0 | 182.0 | 797.0 | 1660.0 | 64.3% | 0.0% |
| explore-coordinated-hold | 59.7 | 123.0 | 207.0 | 947.0 | 2045.0 | 64.5% | 0.0% |

## Сценарий `slow-dependency`

_When the dependency degrades under steady load, does the loop adapt to the new capacity?_

| Режим | Goodput, rps | p50 | p95 | p99 | max | 429 | Жёсткие отказы |
|---|---:|---:|---:|---:|---:|---:|---:|
| explore-coordinated | 44.0 | 31.0 | 372.0 | 1811.0 | 2193.0 | 51.1% | 0.0% |
| explore-coordinated-hold | 46.8 | 29.0 | 1378.0 | 2077.0 | 3002.0 | 48.0% | 0.0% |

## Файлы

- `results.csv` — сводка по представительным прогонам
- `raw/actuator-trace.csv` — значения актюаторов во времени
- `raw/dataset-<сценарий>-<режим>.jsonl` — журнал представительного прогона
- `episodes/<runId>/` — все повторы отдельно, с конфигурацией и границами прогрева

## Воспроизведение

```bash
mvn -q install -DskipTests && mvn -pl iasr-benchmark exec:java
```
