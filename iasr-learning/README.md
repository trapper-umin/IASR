# iasr-learning

Обучение согласованной политики управления мягкими ресурсами по записанным эпизодам. Устройство политики описано в [docs/dissertation/coordinated-policy.md](../docs/dissertation/coordinated-policy.md); здесь — как пользоваться конвейером.

## Состав

| Путь | Назначение |
|---|---|
| `iasr_learning/features.py` | Контракт признаков версии 3 и пространство действий; двойник `PolicyFeatures` и `CoordinatedActionSpace` в JVM |
| `iasr_learning/dataset.py` | Эпизоды → точки решения и переходы `(s, a, r, s')` |
| `iasr_learning/fqi.py` | Fitted Q Iteration и экспорт политики в JSON |
| `iasr_learning/runtime.py` | Эталонное принятие решения без scikit-learn |
| `iasr_learning/train.py` | Обучение с отчётом |
| `iasr_learning/fixtures.py` | Эталонные решения для теста совпадения с JVM |
| `iasr_learning/compare.py` | Парное сравнение режимов по повторам |
| `tools/stand.py` | Запуск плана экспериментов на временном PostgreSQL |
| `plans/` | Планы сбора данных и проверок |
| `tests/` | Тесты конвейера |

## Установка

Нужны Python 3.9+, JDK 17, Maven и локальный PostgreSQL (`initdb`, `pg_ctl`, `createdb` в `PATH`).

```bash
python3 -m pip install -r iasr-learning/requirements.txt
```

## Полный цикл

Все команды выполняются из корня репозитория.

Собрать проект и зафиксировать classpath стенда. Копия нужна, чтобы пересборка во время долгого прогона не подменила измеряемый код:

```bash
mvn clean install
```

```bash
python3 iasr-learning/tools/stand.py freeze /tmp/iasr-classpath
```

Собрать данные по плану. Каждый случай плана — отдельный процесс стенда со своими условиями, режимами и сценариями; результат пишется вне репозитория:

```bash
python3 iasr-learning/tools/stand.py run iasr-learning/plans/development-2026-11-15.json --out /tmp/iasr-dev --classpath /tmp/iasr-classpath
```

Обучить политику. Каталоги `train-*` идут в обучение, `validation-*` — только в отчёт:

```bash
cd iasr-learning && python3 -m iasr_learning.train --train /tmp/iasr-dev/train-* --validation /tmp/iasr-dev/validation-* --out /tmp/iasr-policy
```

Запустить стенд с политикой (режим `coordinated`, путь задаётся `bench.policy` в плане) и сравнить режимы попарно:

```bash
cd iasr-learning && python3 -m iasr_learning.compare /tmp/iasr-eval --reference adaptive
```

## Использование политики в приложении

```yaml
iasr:
  policy:
    model-path: /etc/iasr/policy.json
```

При заданном пути управляет обученная политика, а правила становятся резервным регулятором. Журнал переходов приложения в режиме наблюдения (`iasr.apply-actions: false`) или под управлением правил пригоден как обучающие данные: формат тот же, что пишет стенд.

## Тесты

```bash
cd iasr-learning && python3 -m unittest discover -s tests
```

Совпадение с JVM проверяет `CoordinatedControllerTest` в модуле `iasr-policy`. Его эталон пересоздаётся так:

```bash
cd iasr-learning && python3 -m iasr_learning.fixtures ../iasr-policy/src/test/resources/coordinated/policy.json <каталог эпизода> --out ../iasr-policy/src/test/resources/coordinated/fixture.json
```
