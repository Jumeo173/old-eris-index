## Версии и папки

| Папка | Содержимое (pom.xml) | Тег / релиз | Назначение |
|---|---|---|---|
| `v1.0.0/` | `<version>1.0.0</version>` | `v1.0.0` | Single-cluster |
| `v2.0.0/` | `<version>2.0.1</version>` | `v2.0.1` (latest) | Multi-cluster + demo |

Имя папки = мажорная версия. Патч-версия — в `pom.xml` и в теге/релизе.

---

# OldErisIndex

REST-сервис для выборки инстансов DICOM из кластеров Agfa.


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
