# OldErisIndex

REST-сервис для выборки инстансов DICOM по AN и/или SUID из БД Agfa (Oracle / PostgreSQL).

## Endpoint

    POST /api/v1/research

    {"an":"ACC12345"}
    {"suid":"1.2.840.113619..."}
    {"an":"ACC12345","suid":"1.2.840..."}   — пересечение по (fileName, path)

## Admin

    POST /api/v1/admin/storage-config/refresh
    GET  /api/v1/admin/storage-config

## Запуск

    export DB_USER=... DB_PASSWORD=...
    mvn spring-boot:run

## Сборка

    mvn clean package
    java -jar target/old-eris-index-1.0.0.jar

## СУБД

app.db.dialect: oracle | postgres  — переключение без изменения кода.

## Debug

    logging.level.com.example.olderisindex: DEBUG

или LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX=DEBUG. Пишется в olderis-index-debug.log.

## Форсировать refresh на всех pod'ах

    ./scripts/refresh-all-pods.sh refresh
