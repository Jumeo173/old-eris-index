# OldErisIndex

REST-сервис для выборки инстансов DICOM из кластеров Agfa.

| Версия | Папка | Назначение | Кластеров |
|---|---|---|---|
| 1.0.0 | [`v1.0.0/`](v1.0.0/) | Одно-кластерная версия | 1 |
| 2.0.0 | [`v2.0.0/`](v2.0.0/) | Мультикластерная (A + B, fan-out, dedupe по `sop_iuid`, partial при падении одного) | 2 |

## Документация

Общая — [`docs/`](docs/):

- [OldErisIndex-v1.0.md](docs/OldErisIndex-v1.0.md) — спецификация v1.0
- [OldErisIndex-v2.0.md](docs/OldErisIndex-v2.0.md) — спецификация v2.0

Копии также лежат в `v1.0.0/docs/` и `v2.0.0/docs/` — чтобы не искать далеко от кода.

## Быстрый старт

### v2.0.0 (мультикластерная)

    cd v2.0.0
    export DB_USER_A=... DB_PASSWORD_A=...
    export DB_USER_B=... DB_PASSWORD_B=...
    export LOG_DIR=/tmp/olderis-index && mkdir -p "$LOG_DIR"
    mvn spring-boot:run

### v1.0.0 (одно-кластерная)

    cd v1.0.0
    export DB_USER=... DB_PASSWORD=...
    export LOG_DIR=/tmp/olderis-index && mkdir -p "$LOG_DIR"
    mvn spring-boot:run

## Проверка после старта

    curl -s localhost:8080/api/v1/admin/storage-config | jq      # v2.0.0 — массив кластеров
    curl -X POST localhost:8080/api/v1/research \
         -H 'Content-Type: application/json' -d '{"an":"ACC12345"}'
