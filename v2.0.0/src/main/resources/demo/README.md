# Local demo — H2 in-memory, 2 clusters

Мини-стенд для проверки multi-cluster семантики `OldErisIndex` без Oracle и Docker.

## Что содержит seed

### Кластер A (`init-a.sql`, «амбулаторный»)

- **Study U1** (AN=`ACC12345`)
  - **S1** (CT): I1, I2 — оба с path `/dicom/archive-A/2024\01\study_a.zip`
  - **S2** (MR): I3 — path `/dicom/archive-A/2024\01\study_a2.zip`

### Кластер B (`init-b.sql`, «стационарный»)

- **Study U1** (AN=`ACC12345`) — тот же, что в A (миграция пациента)
  - **S1** (CT): I1 (тот же `sop_iuid`, что в A — **дубль**, path `/dicom/archive-B/2024\01\study_b.zip`), I4 (только в B)
- **Study U2** (AN=`ACC-B-ONLY`) — только в B
  - **S3** (US): I5 — path `/dicom/archive-B/2024\02\study_c.zip`

## Ключевые проверки

### 1. Union + dedupe по `sop_iuid`

    curl -s -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' -d '{"an":"ACC12345"}' | jq

**Ожидаемо:**

- Study `U1` — **один** объект
- **S1** (CT): I1, I2, I4 — все 3
- **I1** — **один раз** (есть в A и B, dedupe по `sop_iuid`)
- Path I1 — `/dicom/archive-A/...` (кластер A первый в `app.clusters[]`)
- **S2** (MR): I3 — только из A

### 2. Study только в B

    curl -s -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' -d '{"an":"ACC-B-ONLY"}' | jq

**Ожидаемо:** Study `U2`, series `S3` (US), instance `I5`, path `/dicom/archive-B/...`.

### 3. Несуществующий AN

    curl -i -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' -d '{"an":"NOPE"}'

**Ожидаемо:** `HTTP/1.1 404`, тело `{"status":"NOT_FOUND"}`.

### 4. Пересечение AN + SUID

    # непусто (U1 есть и по AN=ACC12345, и по SUID=U1)
    curl -s -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' \
      -d '{"an":"ACC12345","suid":"U1"}' | jq

    # пусто (ACC12345 → U1, ACC-B-ONLY → U2; пересечения нет)
    curl -i -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' \
      -d '{"an":"ACC12345","suid":"U2"}'

**Ожидаемо:** первое — `200` с U1 и всеми 4 инстансами; второе — `404`.

## Тест partial (кластер B недоступен)

Запуск с двумя профилями:

    export LOG_DIR=/tmp/olderis-index && mkdir -p "$LOG_DIR"
    mvn spring-boot:run -Dspring-boot.run.profiles=demo,partial

Профиль `partial` подменяет URL кластера B на `jdbc:h2:tcp://localhost:9999/nope` и ставит у B `initial-load-fail-fast: false`. Приложение стартует на A (`require-any`).

### Проверки

    # 1. Статус кластеров — A живой, B в ошибке
    curl -s localhost:8080/api/v1/admin/storage-config | jq

    # 2. ACC12345 — данные только из A (без I4, потому что B недоступен)
    curl -s -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' -d '{"an":"ACC12345"}' | jq

    # 3. WARN в логе
    grep PARTIAL /tmp/olderis-index/application.log | tail -3

    # 4. ACC-B-ONLY — 404 + WARN
    curl -i -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' -d '{"an":"ACC-B-ONLY"}'

**Ожидаемо:**

- admin: A — `"ready": true`, B — `"ready": false` + `"lastError"`
- ACC12345 — `200 OK`, S1 содержит **I1, I2** (без I4), S2 содержит I3
- лог: `WARN … research PARTIAL: AN=ACC12345, clusters_ok=[A], clusters_failed=[B] … Результат может быть неполным …`
- ACC-B-ONLY — `404` + `WARN research NOT_FOUND (partial): … Исследование может существовать в недоступном кластере`

## Где смотреть логи

    tail -50 /tmp/olderis-index/application.log
    grep PARTIAL /tmp/olderis-index/application.log

Если включить debug:

    LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX=DEBUG \
    LOG_DIR=/tmp/olderis-index \
    mvn spring-boot:run -Dspring-boot.run.profiles=demo

Debug пишется в `/tmp/olderis-index/olderis-index-debug.log`.

## Очистка

    rm -rf /tmp/olderis-index

H2 in-memory умирает вместе с JVM — при следующем запуске данные создаются заново из `init-a.sql` / `init-b.sql`.

## Связанные файлы

- `application-demo.yml` — конфиг: оба кластера через H2 in-memory
- `application-demo-partial.yml` — оверрайд: кластер B недоступен
- `demo/init-a.sql` — schema + seed для A
- `demo/init-b.sql` — schema + seed для B

## Замечания

- `password:` в demo пустой, `username: sa` — стандартный H2. Это не секреты, можно коммитить.
- `dialect: h2` — `StorageConfigService` использует `RAWTOHEX(HASH('SHA256', content))` для fingerprint. Если H2 начнёт ругаться на `HASH` — заменить на `MD5(content)` (одна строка).
- Профили можно комбинировать: `-Dspring-boot.run.profiles=demo,partial` — оба сразу; `demo` — только нормальный стенд.
