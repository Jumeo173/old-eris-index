# OldErisIndex (v2.0, мультикластерная версия)

REST-сервис для выборки инстансов DICOM по AN и/или SUID из двух кластеров Agfa.

## Endpoint

    POST /api/v1/research

    {"an":"ACC12345"}                       — поиск по AN в обоих кластерах
    {"suid":"1.2.840.113619..."}            — поиск по SUID в обоих кластерах
    {"an":"ACC12345","suid":"1.2.840..."}   — пересечение union(AN) ∩ union(SUID) по sop_iuid

Один sop_iuid в двух кластерах → одна запись в ответе (path из первого кластера в конфиге).
Один кластер недоступен → ответ из живого + WARN в логе о возможной неполноте.

## Admin

    GET  /api/v1/admin/storage-config               — массив статусов всех кластеров
    GET  /api/v1/admin/storage-config/{cluster}     — статус одного
    POST /api/v1/admin/storage-config/refresh       — refresh всех кластеров (на этой pod'е)
    POST /api/v1/admin/storage-config/{cluster}/refresh — refresh одного

## Запуск

    export DB_USER_A=... DB_PASSWORD_A=...
    export DB_USER_B=... DB_PASSWORD_B=...
    export LOG_DIR=/tmp/olderis-index    # для локального запуска
    mvn spring-boot:run

## Сборка

    mvn clean package
    java -jar target/old-eris-index-2.0.0.jar

## СУБД

У каждого кластера свой `app.clusters[].dialect` (oracle | postgres).
SQL-файлы `find-studies.sql`, `load.sql` идентичны для обоих диалектов;
для fingerprint — `fingerprint.oracle.sql` / `fingerprint.postgres.sql`.

## Debug

    LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX=DEBUG java -jar app.jar

или `logging.level.com.example.olderisindex: DEBUG` в application.yml.
Debug пишется в `olderis-index-debug.log`; при INFO — не создаётся.

## Форсировать refresh на всех pod'ах

    ./scripts/refresh-all-pods.sh status
    ./scripts/refresh-all-pods.sh refresh

## Local demo (H2, без Oracle)

Для локальной проверки multi-cluster логики (union, dedupe, partial) — без Oracle и Docker.

### Обычный запуск — оба кластера живы

    export LOG_DIR=/tmp/olderis-index && mkdir -p "$LOG_DIR"
    mvn spring-boot:run -Dspring-boot.run.profiles=demo

### Тест partial — кластер B недоступен

    export LOG_DIR=/tmp/olderis-index && mkdir -p "$LOG_DIR"
    mvn spring-boot:run -Dspring-boot.run.profiles=demo,partial

Ожидаемо: `200 OK` с данными из A + `WARN research PARTIAL` в логе.

### Проверка

    curl -s localhost:8080/api/v1/admin/storage-config | jq
    curl -s -X POST localhost:8080/api/v1/research \
      -H 'Content-Type: application/json' -d '{"an":"ACC12345"}' | jq

Подробная инструкция и список тестов — [`src/main/resources/demo/README.md`](src/main/resources/demo/README.md).
