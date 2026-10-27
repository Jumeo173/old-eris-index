---
title: "OldErisIndex — документация"
subtitle: "Сервис-индекс «старого» архива ERIS"
version: "1.0"
date: "2025-01-01"
lang: ru-RU
---

# Документация сервиса `OldErisIndex`

**Версия документа:** 1.0
**Статус:** рабочий документ
**Аудитория:** backend-разработчики, DevOps, QA, DBA, **техподдержка**

---

## Оглавление

1. [Постановка задачи](#1-постановка-задачи)
2. [Требования](#2-требования)
3. [Архитектура](#3-архитектура)
4. [API-контракт](#4-api-контракт)
5. [Модель данных и SQL](#5-модель-данных-и-sql)
6. [Компоненты системы](#6-компоненты-системы)
7. [Безопасность](#7-безопасность)
8. [Логирование](#8-логирование)
9. [Развёртывание](#9-развёртывание)
10. [Тестирование](#10-тестирование)
11. [Эксплуатация](#11-эксплуатация)
    - [11.7. Техподдержка: принудительный refresh на всех pod'ах](#117-техподдержка-принудительный-refresh-на-всех-podах)
    - [11.8. Мультиподинг и согласованность кэшей](#118-мультиподинг-и-согласованность-кэшей)
12. [Миграция Oracle → PostgreSQL](#12-миграция-oracle--postgresql)
13. [Roadmap и известные ограничения](#13-roadmap-и-известные-ограничения)
14. [Приложения](#14-приложения)

---

## 1. Постановка задачи

### 1.1. Контекст

В медицинской информационной системе на базе **Agfa** (PACS/архив DICOM) с СУБД Oracle хранятся исследования (studies), серии (series) и инстансы (instances) DICOM. Физически DICOM-файлы упакованы в **zip-архивы** и распределены по storage systems (NFS/S3/локальные тома); конфигурация storage systems описана в таблице **`dcm4che_config`** в виде вложенного JSON (путь `/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc`). Таблица меняется **крайне редко** — раз в неделю или месяц, обычно при изменении инфраструктуры storage.

Требуется REST-сервис **OldErisIndex**, который по **AN (Accession Number)** и/или **SUID (Study Instance UID)** исследования возвращает список файлов (SOP Instance UID + полный путь к zip-архиву), сгруппированных по цепочке study → series → instances.

### 1.2. Проблема

**Сейчас сервиса, который отдавал бы такие данные, не существует.** Всё, что есть — это **одноразовый SQL-запрос, написанный вручную в ходе исследования задачи**. Он проверен на реальных данных и подтверждает гипотезу «из БД можно получить полные пути к архивам по AN», но не пригоден для продуктивного использования:

- **SQL-инъекции** — AN подставляется в текст запроса вручную (`WHERE accession_no = '...'`). Любая интеграция повторяет эту практику и открывает дыру.
- **Нет API** — нет стандартного способа получить данные программно; каждый потребитель копирует SQL к себе, что порождает расхождения в поведении.
- **Нет кэширования, лимитов, таймаутов** — один «тяжёлый» запрос может положить БД; повторный ответ через 5 минут снова нагружает БД.
- **Поддерживается только AN** — поиск по SUID, нужный большинству DICOM-инструментов, отсутствует.
- **Нет поиска по двум критериям одновременно** — клиент, знающий оба идентификатора, вынужден делать два запроса и вручную сопоставлять результаты по путям.
- **Запрос захардкожен под Oracle** — нет переносимости на другие СУБД.
- **JSON собирается вручную в SQL** — спецсимволы (`\`, `"`) ломают ответ; формат не документирован.
- **Не описано, что именно `path`** — путь к dcm или к zip.
- **Нет диагностического лога** — при инциденте («по этому AN должен был найтись study, а вернулось 404») нет способа посмотреть, что выполнилось.
- **Прод-лог шумит** — без чёткой политики уровней невозможно оценить фактический трафик; легко получить дублирование или, наоборот, «немой» прод.
- **`dcm4che_config` парсится на каждом запросе** — при 50 RPS это 50 разборов JSON в секунду при том, что результат одинаков для всех запросов, а конфиг меняется раз в месяц. Паразитная нагрузка на Oracle и дублирование логики разбора между Oracle/PostgreSQL.

Иными словами, **исследовательский SQL нужно «упаковать» в продуктивный сервис**, который скрывает детали СУБД, обеспечивает безопасность, устойчивость к нагрузке, стабильный контракт, **чистый прод-лог, отключаемую диагностику и кэш редко меняющегося конфига**.

### 1.3. Цель

Создать REST-сервис **OldErisIndex** на Java/Spring Boot, который:

- принимает `{"an": "..."}`, `{"suid": "..."}` или **оба сразу**;
- при одном критерии — ищет по нему; при двух — выполняет **два независимых поиска** и возвращает **пересечение инстансов** по точному совпадению пары `(fileName, path)`;
- выполняет параметризованный SQL к БД Agfa без `JSON_TABLE`/`jsonb`/`XMLAGG` в горячем пути;
- возвращает JSON `{uid, series: [{uid, modality, instances: [{fileName, path}]}]}`, где `fileName` = SOP Instance UID, `path` = полный путь к **zip-архиву**;
- работает на Oracle 12c и PostgreSQL 12+ без изменения кода (только конфиг);
- защищён от SQL-инъекций, таймаутов и пиковой нагрузки;
- не хранит пароль БД в конфиге;
- **кэширует `dcm4che_config` в памяти** с двухуровневым обновлением (дешёвый fingerprint-полинг + full reload только при изменении), чтобы не нагружать Oracle;
- **имеет отключаемый debug-уровень логирования** (по умолчанию выключен, включается без пересборки), с отдельным файлом `olderis-index-debug.log`;
- **ведёт чистый прод-лог**: одна строка на cache-miss, одна на успех, одна на 502/504 — без дублей; 404/400 — только через метрики;
- **поддерживает мультиподинг**: каждая pod держит свой кэш; согласованность — через fingerprint-полинг и ручной force-refresh на всех pod'ах (см. §11.7).

### 1.4. Границы (scope)

**В scope:**

- REST `POST /api/v1/research`;
- admin: `POST /api/v1/admin/storage-config/refresh`, `GET /api/v1/admin/storage-config`;
- выборка по AN, SUID, пересечению по `(fileName, path)`;
- кэш studies (Caffeine), кэш storage config (fingerprint + full reload);
- пул потоков, таймауты, HTTP-коды;
- поддержка Oracle 12c+ и PostgreSQL 12+;
- отключаемый debug и чистый прод-лог;
- документация и тесты.

**Вне scope:**

- аутентификация/авторизация клиентов и admin-эндпоинтов (решается на уровне API Gateway);
- общий кэш между pod'ами (Redis и т.п.) — см. §11.8;
- запись данных, изменение исследований;
- работа с DICOM-файлами и zip-архивами (чтение/распаковка/пересылка) — только метаданные;
- UI / Swagger-UI.

### 1.5. Происхождение SQL

SQL-шаблон — **доработанный исследовательский запрос**, написанный вручную для проверки гипотезы «можно ли из БД получить полные пути к файлам по AN». Он **не является чьим-то сервисом**, не поддерживается и не сопровождается. Продуктивная реализация:

- сохраняет логику JOIN-цепочки (`study → series → instance → rel_instance_location → location`);
- заменяет литерал `WHERE accession_no = '...'` на bind-параметр;
- добавляет ветку по SUID;
- **выносит разбор `dcm4che_config` из SQL в Java** (парсинг один раз при загрузке кэша, а не на каждом запросе);
- **собирает JSON в Java** вместо `XMLAGG`/`jsonb_agg` — это позволяет один SQL на оба диалекта и устраняет SQL-инъекции через JSON-строки;
- **имена таблиц и путей в JSON-конфиге оставлены как были** (`dcm4che_config`, `.../dcm4chee-arc`) — это фактические имена в БД, менять не требуется.

### 1.6. Название

**OldErisIndex** — сервис-индекс «старого» архива ERIS.

---

## 2. Требования

### 2.1. Функциональные (FR)

| ID | Требование | Приоритет |
|---|---|---|
| FR-1 | Принять `POST /api/v1/research` с `an`, `suid` или обоими полями | Must |
| FR-2 | Если задано ни одного поля → `400 Bad Request` | Must |
| FR-3 | При одном поле — поиск по нему | Must |
| FR-4 | При двух полях — **два независимых поиска + пересечение по `(fileName, path)`** | Must |
| FR-5 | Вернуть JSON `{uid, series:[{uid, modality, instances:[{fileName, path}]}]}`, где `fileName` = `sop_iuid`, `path` = путь к zip-архиву | Must |
| FR-6 | Study не найден / пересечение пусто → `404` + `{"status":"NOT_FOUND"}` | Must |
| FR-7 | Ошибка БД → `502` + `{"status":"ERROR"}` | Must |
| FR-8 | Таймаут БД → `504` + `{"status":"ERROR"}` | Must |
| FR-9 | Успех → `200` + `{"status":"OK","studies":[...]}` | Must |
| FR-10 | Кэш успешных ответов (Caffeine, TTL 10 мин, ключ `an|suid`) | Must |
| FR-11 | Ограничение параллельных запросов к БД (пул 8–16) | Must |
| FR-12 | При пересечении study/series без инстансов удаляются из ответа | Must |
| FR-13 | Отключаемый debug (по умолчанию `INFO`, включается `DEBUG` без пересборки) | Must |
| FR-14 | Debug-сообщения — в отдельном `olderis-index-debug.log`; при `INFO` файл не создаётся | Must |
| FR-15 | При выключенном debug — нулевой runtime-overhead | Must |
| FR-16 | Прод-лог (INFO): `research miss` (1 строка на cache-miss) и `research OK` (1 строка на успех) | Must |
| FR-17 | 502/504 логируются ровно один раз — в `ErrorHandler` | Must |
| FR-18 | `404`/`400` не пишутся в прод-лог на INFO (видимость — через метрики) | Must |
| FR-19 | `dcm4che_config` загружается при старте; при ошибке/пустом результате — fail fast | Must |
| FR-20 | Периодический **fingerprint-полинг** (`fingerprint-interval: 1h`): `SELECT hash(content)`; при изменении — full reload | Must |
| FR-21 | Ручной full reload по `POST /api/v1/admin/storage-config/refresh` | Must |
| FR-22 | При ошибке refresh (fingerprint или full) — сохраняется предыдущая версия кэша | Must |
| FR-23 | `GET /api/v1/admin/storage-config` возвращает `size`, `version`, `lastLoadTime`, `path`, `fingerprint`, `lastFingerprintCheckTime` | Must |
| FR-24 | При research-запросе **не выполняется** `SELECT content FROM dcm4che_config` | Must |
| FR-25 | Fingerprint-полинг не блокирует research-запросы | Must |
| FR-26 | Fingerprint-полинг не делает full reload, если hash не изменился | Must |

### 2.2. Нефункциональные (NFR)

| ID | Требование | Значение |
|---|---|---|
| NFR-1 | Поддержка СУБД | Oracle 12c+ и PostgreSQL 12+ |
| NFR-2 | Пропускная способность | ≥50 RPS при кэш-хите, ≥10 RPS в БД |
| NFR-3 | Латентность p95 | ≤300 мс (кэш), ≤5 с (БД) |
| NFR-4 | Таймаут запроса к БД | 20 с (конфигурируется) |
| NFR-5 | Отсутствие SQL-инъекций | все значения — bind-параметры |
| NFR-6 | Пароль БД не в конфиге | env/Vault/Wallet |
| NFR-7 | Логирование ошибок с контекстом | `AN=...`/`SUID=...` |
| NFR-8 | Graceful shutdown | дождаться задач ≤30 с |
| NFR-9 | Overhead debug-вызовов при `INFO` | ≤ 0.1% latency |
| NFR-10 | Ротация debug-файла | 200 MB × 7 дней, total 2 GB |
| NFR-11 | Включение/выключение debug | без пересборки (yml/env/actuator) |
| NFR-12 | Ротация прод-лога | 100 MB × 30 дней, total 5 GB |
| NFR-13 | Дублей в прод-логе на одно событие | 0 (unit-тест) |
| NFR-14 | Нагрузка на `dcm4che_config` | 1 full SELECT при старте; 1 hash-SELECT раз в `fingerprint-interval`; full SELECT — только при изменении или вручную; 0 при research |
| NFR-15 | Refresh не блокирует research | `AtomicReference` + атомарный swap |
| NFR-16 | Lookup в кэш storage config | O(1), HashMap, ≤ 1 мкс |
| NFR-17 | Concurrency refresh | Одновременные refresh сериализуются (`synchronized`); research не блокируется |
| NFR-18 | Окно расхождения между pod'ами | ≤ `fingerprint-interval` (1h) автоматически; 0 после §11.7 |

### 2.3. Технологический стек

- Java 17, Spring Boot 3.3
- Spring JDBC (`NamedParameterJdbcTemplate`, `JdbcTemplate`)
- Spring Cache + Caffeine
- Spring Scheduling (`@Scheduled`, `@EnableScheduling`)
- Spring Boot Actuator (health/readiness/liveness, метрики, `loggers`)
- Jackson (`ObjectMapper` + `JsonNode`)
- Logback (`logback-spring.xml`)
- Oracle JDBC (ojdbc11) + PostgreSQL JDBC
- JUnit 5, Mockito, Testcontainers, AssertJ
- Maven

---

## 3. Архитектура

### 3.1. Компонентная диаграмма

```
┌──────────────────┐
│  Клиент          │
└────────┬─────────┘
         │ POST /api/v1/research
         ▼
┌───────────────────────────────────────────────────────┐
│                 OldErisIndex                          │
│                                                       │
│  ┌─────────────────────────────────────────────────┐  │
│  │  ResearchController                             │  │
│  └────────────────┬────────────────────────────────┘  │
│                   ▼                                   │
│  ┌─────────────────────────────────────────────────┐  │
│  │  ResearchService                                │  │
│  │  @Cacheable studies key=an|suid                 │  │
│  │  fetchAndMaybeIntersect (flat rows)             │  │
│  │  buildStudies (JSON из плоских строк)           │  │
│  │  INFO: research miss / research OK              │  │
│  └────────────────┬────────────────────────────────┘  │
│                   ▼                                   │
│  ┌─────────────────────────────────────────────────┐  │
│  │  StudyRepository                                │  │
│  │  SELECT … (без JSON_TABLE / XMLAGG)             │  │
│  │  → List<StudyRow>                               │  │
│  └────────────────┬────────────────────────────────┘  │
│                   │                                   │
│  ┌────────────────▼────────────────────────────────┐  │
│  │  StorageConfigService                           │  │
│  │  AtomicReference<Map<StorageKey, String>>       │  │
│  │  String currentFingerprint                      │  │
│  │  @PostConstruct init()              — full load │  │
│  │  @Scheduled fingerprintCheck()     — 1h         │  │
│  │  public refresh() (admin)           — full load │  │
│  └────────────────┬────────────────────────────────┘  │
│                   │ hash-SELECT (1h)                  │
│                   │ full SELECT (изменение/вручную)   │
│                   ▼                                   │
│  ┌─────────────────────────────────────────────────┐  │
│  │  ErrorHandler (@RestControllerAdvice)           │  │
│  │  StorageConfigController (admin)                │  │
│  └─────────────────────────────────────────────────┘  │
│                                                       │
│  ┌─────────────────────────────────────────────────┐  │
│  │  logback-spring.xml                             │  │
│  │  CONSOLE / FILE (INFO+)                         │  │
│  │  DEBUG_FILE (только для нашего пакета)          │  │
│  └─────────────────────────────────────────────────┘  │
└──────────┬──────────────────────────┬─────────────────┘
           │ JDBC                     │ JDBC
           ▼                          ▼
┌──────────────────────────────────────────────────────┐
│                   Agfa database                      │
│  study/series/instance/…        dcm4che_config       │
└──────────────────────────────────────────────────────┘
```

### 3.2. Поток research-запроса

1. Controller → Service (`@Valid`, debug-лог входящих `an`/`suid`).
2. Кэш studies: hit → ответ; miss → `INFO "research miss"` + в executor.
3. `fetchAndMaybeIntersect`:
   - только `an` → `repo.findByAn(an)`;
   - только `suid` → `repo.findBySuid(suid)`;
   - оба → `repo.findByAn` + `repo.findBySuid` + пересечение по `(fileName, fullPath)`.
4. `StudyRepository` выполняет плоский SQL, возвращает `List<StudyRow>` — по строке на (instance, location).
5. `ResearchService.buildStudies(rows)`:
   - группирует study → series → instances;
   - для каждой строки делает `storageConfigService.ssPath(groupId, storageId)` — O(1) из `HashMap`;
   - вычисляет `fullPath = ssPath + '/' + storagePath.replace('/', '\\')`;
   - собирает JSON.
6. `INFO "research OK"`, `return ResearchResponse.ok(...)`.

**Ключевое:** `dcm4che_config` **не** запрашивается при research. Никакого `JSON_TABLE`.

### 3.3. Поток StorageConfigService — двухуровневый refresh

**Старт (`@PostConstruct`):**

```
loadFingerprintAndContent():
  row = SELECT content FROM dcm4che_config WHERE path = ?
  if row empty → throw StorageConfigException → приложение не стартует
  json = row.content
  fingerprint = SHA-256(json)             // в Java
  map = parse(json)                        // Jackson
  cache.set(map); currentFingerprint = fingerprint; version=1; lastLoadTime=now()
  INFO "Storage config loaded on startup: N entries, version 1, fingerprint=ab12…"
```

**Fingerprint-полинг (`@Scheduled(fixedDelay = fingerprint-interval)`):**

```
fingerprintCheck():
  newFp = SELECT <hash>(content) FROM dcm4che_config WHERE path = ?   // дешёвый
  lastFingerprintCheckTime = now()
  if newFp == currentFingerprint:
      DEBUG "fingerprint unchanged"
      return
  INFO "fingerprint changed (ab12… → cd34…), starting full reload"
  refresh()
```

**Full reload (`refresh()`, `synchronized`):**

```
refresh():
  row = SELECT content FROM dcm4che_config WHERE path = ?
  if row empty → ERROR "full reload failed: no config row, keeping version V"; return
  json = row.content
  newFp = SHA-256(json)
  map = parse(json)
  if map.isEmpty() → ERROR "full reload produced empty map, keeping version V"; return
  cache.set(map); currentFingerprint = newFp; version++; lastLoadTime=now()
  INFO "Storage config refreshed: N entries, version V+1, fingerprint=cd34…"
```

**Ручной refresh (`POST /api/v1/admin/storage-config/refresh`):** вызывает `refresh()`.

**Свойства:**

- Fingerprint-полинг — один `SELECT hash`, без JSON и парсинга. **Очень дёшево.**
- Полный `SELECT content` + `parse` — только при изменении fingerprint или вручную. **При реальной частоте (неделя/месяц) — единицы раз в месяц.**
- Мультиподинг: каждая pod независимо обнаруживает изменение за ≤1 час; для мгновенного обновления всех pod'ов — §11.7.

### 3.4. Диаграмма последовательности (research, оба параметра)

```
Client → Controller → Service
Service → Cache: miss
Service → Log(INFO): "research miss"
Service → Repo: findByAn(ACC1)
Repo → DB: SELECT … WHERE accession_no = :an  (без dcm4che_config!)
DB → Repo: rows (flat)
Service → Repo: findBySuid(1.2.3)
Repo → DB: SELECT … WHERE study_iuid = :suid
DB → Repo: rows (flat)
Service → StorageConfigService: ssPath(groupId, storageId) × N  (in-memory)
Service → Service: intersect + buildStudies
Service → Log(INFO): "research OK"
Service → Controller → Client: 200 OK
```

---

## 4. API-контракт

### 4.1. `POST /api/v1/research`

| Заголовок | Значение |
|---|---|
| `Content-Type` | `application/json` |

| Поле | Тип | Обяз. | Описание |
|---|---|---|---|
| `an` | string (≤64) | хотя бы одно из `an`/`suid` | Accession Number |
| `suid` | string (≤64) | хотя бы одно из `an`/`suid` | Study Instance UID |

**Правила:**

| `an` | `suid` | Поведение |
|---|---|---|
| задан | — | Поиск по AN |
| — | задан | Поиск по SUID |
| задан | задан | Два независимых поиска + **пересечение инстансов по `(fileName, path)`**; пусто → `404` |
| — | — | `400 Bad Request` |

**Примеры:**

```json
{ "an": "ACC12345" }
```

```json
{ "suid": "1.2.840.113619.2.55.3.1234" }
```

```json
{ "an": "ACC12345", "suid": "1.2.840.113619.2.55.3.1234" }
```

### 4.2. Ответы `/api/v1/research`

| HTTP | `status` | Когда | Прод-лог |
|---|---|---|---|
| `200` | `OK` | Найдено | `INFO research OK` |
| `400` | `ERROR` | Ни одного поля / длина >64 | — (метрика) |
| `404` | `NOT_FOUND` | Не найдено / пересечение пусто | — (метрика) |
| `502` | `ERROR` | Ошибка БД / некорректный JSON | `ERROR 502 DB_ERROR` + stack |
| `504` | `ERROR` | Таймаут БД | `WARN 504 TIMEOUT` |

**200 OK:**

```json
{
  "an": "ACC12345",
  "suid": "1.2.840.113619.2.55.3.1234",
  "status": "OK",
  "studies": [
    {
      "uid": "1.2.840.113619.2.55.3.1234",
      "series": [
        {
          "uid": "1.2.840.113619.2.55.3.1234.1",
          "modality": "CT",
          "instances": [
            { "fileName": "1.2.840.113619.2.55.3.1234.1.1",
              "path": "/dicom/archive/2024/01/01/study_xxxx.zip" }
          ]
        }
      ]
    }
  ],
  "processedAt": "2025-01-01T12:00:00Z"
}
```

> В ответе возвращаются только те поля `an`/`suid`, которые были заданы в запросе (`@JsonInclude(NON_NULL)`).

**404 Not Found:**

```json
{ "an": "ACC99999", "status": "NOT_FOUND", "processedAt": "..." }
```

**400 Bad Request:**

```json
{ "status": "ERROR", "error": "Укажите хотя бы одно поле: 'an' или 'suid'", "processedAt": "..." }
```

**502 / 504** — `{"status":"ERROR","error":"...","processedAt":"..."}`.

### 4.3. `POST /api/v1/admin/storage-config/refresh`

**200 OK:**

```json
{
  "size": 12,
  "version": 42,
  "lastLoadTime": "2025-01-01T12:00:00Z",
  "path": "/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc",
  "fingerprint": "a3f5…e8c1",
  "lastFingerprintCheckTime": "2025-01-01T13:00:00Z"
}
```

**500 Internal Server Error** (старая версия сохранена):

```json
{
  "size": 12,
  "version": 41,
  "lastLoadTime": "2025-01-01T11:00:00Z",
  "path": "...",
  "fingerprint": "old…",
  "lastFingerprintCheckTime": "...",
  "error": "No config row for path ..."
}
```

### 4.4. `GET /api/v1/admin/storage-config`

Тот же формат без `error`. Используется техподдержкой для проверки состояния на каждом pod'е (§11.7).

### 4.5. Примеры curl

```bash
# research
curl -i -X POST http://localhost:8080/api/v1/research \
     -H "Content-Type: application/json" -d '{"an":"ACC12345"}'

# refresh (одна pod)
curl -i -X POST http://localhost:8080/api/v1/admin/storage-config/refresh

# состояние
curl -s http://localhost:8080/api/v1/admin/storage-config | jq
```

---

## 5. Модель данных и SQL

### 5.1. Используемые таблицы

| Таблица | Назначение | Кто использует |
|---|---|---|
| `study`, `series`, `instance`, `rel_instance_location`, `location` | research-данные | `StudyRepository` |
| `dcm4che_config` | JSON-конфиг storage systems | **только `StorageConfigService`** |

**Формат хранения.** DICOM-инстансы упакованы в **zip-архивы**. `location.storage_path` — путь к архиву; `instance.sop_iuid` — идентификатор файла внутри. В ответе `fileName` = `sop_iuid`, `path` = `ss_path + '/' + storage_path.replace('/', '\\')`.

### 5.2. Research SQL — `sql/find-studies.sql` (один на оба диалекта)

```sql
SELECT
    st.study_iuid,
    se.series_iuid,
    se.modality,
    i.sop_iuid        AS file_name,
    l.storage_group_id,
    l.storage_id,
    l.storage_path
FROM study st
JOIN series se ON se.study_fk = st.pk
JOIN instance i ON i.series_fk = se.pk
JOIN rel_instance_location rel ON rel.instance_fk = i.pk
JOIN location l ON l.pk = rel.location_fk
WHERE {{WHERE}}
ORDER BY st.study_iuid, se.series_iuid, i.sop_iuid, l.storage_path
```

`{{WHERE}}` заменяется на константу `WHERE_BY_AN` (`st.accession_no = :an`) или `WHERE_BY_SUID` (`st.study_iuid = :suid`).

**Ключевое:** нет `JSON_TABLE`, `jsonb_array_elements`, `XMLAGG`, `jsonb_agg`, `REPLACE(...)`; значения — `:an`/`:suid` bind-параметры.

### 5.3. Storage config SQL — 3 файла

**`sql/storage-config/load.sql`** (одинаков для диалектов):

```sql
SELECT content FROM dcm4che_config WHERE path = ?
```

**`sql/storage-config/fingerprint.oracle.sql`:**

```sql
SELECT RAWTOHEX(STANDARD_HASH(content, 'SHA256'))
FROM dcm4che_config
WHERE path = ?
```

> `STANDARD_HASH` доступен с Oracle 12.1 без дополнительных привилегий.

**`sql/storage-config/fingerprint.postgres.sql`:**

```sql
SELECT md5(content::text) FROM dcm4che_config WHERE path = ?
```

### 5.4. Различия диалектов

| Аспект | Oracle 12c | PostgreSQL 12+ |
|---|---|---|
| research SQL | идентичен | идентичен |
| load storage config | идентичен | идентичен |
| fingerprint | `RAWTOHEX(STANDARD_HASH(…))` | `md5(::text)` |
| CLOB | `rs.getString(1)` | `TEXT` |
| `''` = NULL | да | нет |

### 5.5. Логика пересечения (AN + SUID)

1. Для каждой `StudyRow` из `bySuid` — вычисляем `fullPath` (через `storageConfigService.ssPath`); строим `Set<String>` ключей `fileName + "|" + fullPath`. Строки без `ss_path` пропускаем.
2. Для каждой `StudyRow` из `byAn` — вычисляем `fullPath`; если ключ есть в `Set` — оставляем.
3. Итог — `List<StudyRow>`, из которого `buildStudies` собирает JSON. Пусто → `StudyNotFoundException` → `404`.

**Точное совпадение** — `String.equals` по `fileName` и по `fullPath` (без нормализации).

---

## 6. Компоненты системы

### 6.1. Пакеты

```
com.example.olderisindex
├── OldErisIndexApplication      (@SpringBootApplication @EnableCaching @EnableScheduling)
├── api
│   ├── ResearchController / ResearchRequest / ResearchResponse / ResearchService
│   ├── admin/StorageConfigController
│   └── error/
│       ├── ErrorHandler
│       ├── StudyNotFoundException
│       ├── ResearchDatabaseException
│       └── ResearchTimeoutException
├── config
│   ├── ExecutorConfig
│   └── SqlConfig
├── data
│   ├── StudyRepository
│   └── StudyRow
└── storage
    ├── StorageConfigService
    ├── StorageKey
    └── StorageConfigException
```

### 6.2. Ответственности и уровни логирования

| Класс | Ответственность | INFO | WARN | ERROR | DEBUG |
|---|---|---|---|---|---|
| `ResearchController` | HTTP, `@Valid` | — | — | — | POST-запрос |
| `ResearchService` | Кэш, executor, таймаут, пересечение, сборка JSON | **research miss / OK** | — | — | cacheKey, sizes, per-row resolve |
| `StudyRepository` | SQL → `List<StudyRow>` | — | — | — | SQL name, bind, size, duration |
| `StorageConfigService` | Кэш, fingerprint, full reload | **startup load**, **fingerprint changed → reload**, **reload success** | — | **reload failed (keeping V)** | **fingerprint unchanged**, parse counts |
| `ErrorHandler` | Mapping → HTTP | — | **504** | **502** | 404 / 400 |
| `StorageConfigController` | admin | — | — | — | — |

**Инвариант:** 502/504 логируются **только** в `ErrorHandler`. `ResearchService` для `TimeoutException`/`ExecutionException` пишет `log.debug` (детали), не `warn`/`error`.

### 6.3. Кэш-ключ studies

```java
key = "(#req.an != null ? #req.an : '') + '|' + (#req.suid != null ? #req.suid : '')"
```

| `an` | `suid` | ключ |
|---|---|---|
| `ACC1` | `null` | `ACC1|` |
| `null` | `1.2.3` | `|1.2.3` |
| `ACC1` | `1.2.3` | `ACC1|1.2.3` |

### 6.4. Конфигурация (`application.yml`)

```yaml
server:
  port: 8080

spring:
  application:
    name: old-eris-index

  datasource:
    url: jdbc:oracle:thin:@//oracle-host:1521/DCMDB
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 5000
      validation-timeout: 3000
      pool-name: olderis-hikari

  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=2000,expireAfterWrite=10m

app:
  db:
    dialect: oracle             # oracle | postgres
  research:
    db-timeout-seconds: 20
    executor-core: 8
    executor-max: 16
    executor-queue: 100
  storage-config:
    path: /dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc
    fingerprint-interval: 1h    # 30s / 1h / 24h; 0 = выключить fingerprint
    initial-load-fail-fast: true

management:
  endpoints.web.exposure.include: health,info,metrics,prometheus,loggers
  endpoint.health.probes.enabled: true

logging:
  level:
    root: INFO
    com.example.olderisindex: INFO
  file:
    name: /var/log/olderis-index/application.log
```

**Про `fingerprint-interval`:**

| Значение | Когда подходит |
|---|---|
| `1h` (по умолчанию) | Типичный кейс: изменения раз в неделю/месяц, окно ≤1 часа приемлемо |
| `30m` | Если важно быстрее узнавать об изменениях |
| `24h` | Минимальная нагрузка, допустимо ждать сутки |
| `0` | Fingerprint отключён; обновление только `POST /refresh` или перезапуск |

### 6.5. `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <property name="LOG_DIR"   value="${LOG_DIR:-/var/log/olderis-index}"/>
    <property name="APP_LOG"   value="${LOG_DIR}/application.log"/>
    <property name="DEBUG_LOG" value="${LOG_DIR}/olderis-index-debug.log"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${APP_LOG}</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${APP_LOG}.%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
    </appender>

    <appender name="DEBUG_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${DEBUG_LOG}</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${DEBUG_LOG}.%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>7</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
        <filter class="ch.qos.logback.classic.filter.LevelFilter">
            <level>DEBUG</level>
            <onMatch>ACCEPT</onMatch>
            <onMismatch>DENY</onMismatch>
        </filter>
    </appender>

    <logger name="com.example.olderisindex" level="INFO" additivity="true">
        <appender-ref ref="DEBUG_FILE"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### 6.6. Класс-точка входа

```java
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class OldErisIndexApplication {
    public static void main(String[] args) {
        SpringApplication.run(OldErisIndexApplication.class, args);
    }
}
```

---

## 7. Безопасность

### 7.1. SQL-инъекции

- research: `:an`/`:suid` — bind-параметры; SQL фиксированный.
- storage config: `?` — bind во всех 3 SQL-файлах.
- Валидация `@Size(max=64)`.

### 7.2. Пароль БД

**Никогда** в `application.yml`/`git`. Варианты:

| Вариант | Плюсы | Минусы |
|---|---|---|
| Env `DB_PASSWORD` (K8s Secret) | просто | нет ротации |
| Oracle Wallet | пароль не в коде | только Oracle |
| HashiCorp Vault | ротация, короткоживущие | инфраструктура |

**K8s Secret:**

```yaml
env:
  - name: DB_USER
    valueFrom: { secretKeyRef: { name: db-creds, key: username } }
  - name: DB_PASSWORD
    valueFrom: { secretKeyRef: { name: db-creds, key: password } }
```

**Oracle Wallet:**

```bash
mkstore -wrl /etc/oracle/wallet -createCredential DCMDB dcm_user
# password: "" в yml
java -Doracle.net.wallet_location=/etc/oracle/wallet -jar app.jar
```

**Vault (Spring Cloud Vault):**

```yaml
spring.cloud.vault:
  uri: https://vault.company.com:8200
  authentication: KUBERNETES
  kubernetes.role: old-eris-index
```

### 7.3. Сетевая безопасность

- Сервис слушает `:8080` во внутренней сети.
- TLS-терминация на ingress/API Gateway.
- **`/api/v1/admin/**` закрыт от внешнего ingress**; вызывается только из ops/monitoring.
- Ограничение входящих IP.

### 7.4. PII в логах

- AN/SUID в `research miss`/`research OK` и 502/504 — как есть.
- `StorageConfigService` **не логирует содержимое `dcm4che_config`** — только fingerprint (первые символы), количество записей, версию.
- При требовании регулятора — хэшировать AN/SUID (`sha256Hex`), 2–3 места (`ResearchService`, `ErrorHandler`).

---

## 8. Логирование

### 8.1. Уровни и приёмники

| Уровень | Файл | При `INFO` |
|---|---|---|
| INFO | `application.log`, console | ✅ |
| WARN | `application.log`, console | ✅ |
| ERROR | `application.log`, console | ✅ |
| DEBUG | `olderis-index-debug.log` | ❌ (файл не создаётся) |

### 8.2. Прод-лог при `INFO` — что пишется

| Событие | Уровень | Строк | Откуда |
|---|---|---|---|
| Старт приложения | INFO | ~5 | Spring Boot |
| Storage config: startup load | INFO | 1 | `StorageConfigService` |
| Storage config: fingerprint changed → reload | INFO | 1 | `StorageConfigService` |
| Storage config: full reload success | INFO | 1 | `StorageConfigService` |
| Storage config: full reload failed (keeping V) | ERROR | 1 + stack | `StorageConfigService` |
| Cache-miss | INFO | 1 (`research miss: ...`) | `ResearchService` |
| `200 OK` | INFO | 1 (`research OK: ..., studies=N, T мс`) | `ResearchService` |
| `504` | WARN | 1 (`504 TIMEOUT: ...`) | `ErrorHandler` |
| `502` | ERROR | 1 + stack (`502 DB_ERROR: ...`) | `ErrorHandler` |

**НЕ пишется:**

- ❌ fingerprint unchanged (только DEBUG);
- ❌ кэш-хит (метрика);
- ❌ `404` / `400` (метрики);
- ❌ ss_path lookup (O(1)).

**Дублей нет:** 502/504 логируются только в `ErrorHandler`.

### 8.3. Debug-лог при `DEBUG`

**Controller:** `POST /api/v1/research: an='...', suid='...'`.

**ResearchService:**
- `process(): AN='...', SUID='...', cacheKey='...'`;
- `fetchAndMaybeIntersect(): byAn=N, bySuid=M`;
- `intersect(): keys in bySuid = K`;
- `buildStudies(): rows=N, studies=M, series=K, instances=L`;
- `buildStudies(): row dropped (no ss_path for (groupId, storageId))`;
- `process(): empty result (T мс)`;
- `process(): timeout after 20s`;
- `process(): db error: <cause>`.

**StudyRepository:** `SQL findByAn: bind an='...'`, `SQL findByAn вернул N за T мс`.

**StorageConfigService:**
- `parse(): groups=N, systems=M, keys=K`;
- `fingerprintCheck(): current=ab12…, db=ab12… → unchanged`;
- `fingerprintCheck(): current=ab12…, db=cd34… → changed, reloading`;
- `resolve(): (groupId, storageId) → ssPath` / `NOT FOUND (row dropped)`.

**ErrorHandler:** `404 NOT_FOUND: <msg>`, `400 VALIDATION: <msg>`.

### 8.4. Включение/выключение debug

| Способ | Команда |
|---|---|
| yml | `logging.level.com.example.olderisindex: DEBUG` |
| env | `LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX=DEBUG java -jar app.jar` |
| CLI | `--logging.level.com.example.olderisindex=DEBUG` |
| Actuator | `POST /actuator/loggers/com.example.olderisindex -d '{"configuredLevel":"DEBUG"}'` |

Возврат — те же способы со значением `INFO`.

### 8.5. Overhead при `INFO`

- Все `log.debug` — под `if (log.isDebugEnabled())`. При `INFO` аргументы не вычисляются.
- `LevelFilter` в `DEBUG_FILE` отсекает всё до открытия writer'а — файл не создаётся.
- Ожидаемый overhead — ≤ 0.1%.
- INFO-логи тоже дешёвые: две строки на запрос, без stack trace.

### 8.6. Пример прод-лога при `INFO`

```
2025-01-01T12:00:00.100 INFO  [main]         c.e.o.storage.StorageConfigService - Storage config loaded on startup: 12 entries, version 1, fingerprint=a3f5…e8c1
2025-01-01T12:00:00.400 INFO  [main]         c.e.olderisindex.OldErisIndexApplication - Started OldErisIndexApplication in 2.5 seconds
2025-01-01T13:00:00.050 INFO  [scheduling-1] c.e.o.storage.StorageConfigService - Storage config: fingerprint changed (a3f5… → d7b2…), starting full reload
2025-01-01T13:00:00.180 INFO  [scheduling-1] c.e.o.storage.StorageConfigService - Storage config refreshed: 13 entries, version 2, fingerprint=d7b2…f4a8
2025-01-01T13:06:00.123 INFO  [http-nio-…]   c.e.o.api.ResearchService - research miss: AN=ACC12345
2025-01-01T13:06:00.145 INFO  [db-1]         c.e.o.api.ResearchService - research OK: AN=ACC12345, studies=1, 22 мс
```

### 8.7. Пример debug-трейса

```
2025-01-01T13:06:00.123 DEBUG [http-nio-…] c.e.o.api.ResearchController - POST /api/v1/research: an='ACC12345', suid='1.2.840.113619.2.55.3.1234'
2025-01-01T13:06:00.124 DEBUG [http-nio-…] c.e.o.api.ResearchService    - process(): AN='ACC12345', SUID='1.2.840…', cacheKey='ACC12345|1.2.840…'
2025-01-01T13:06:00.125 DEBUG [db-1]       c.e.o.data.StudyRepository  - SQL findByAn: bind an='ACC12345'
2025-01-01T13:06:00.130 DEBUG [db-1]       c.e.o.data.StudyRepository  - SQL findByAn вернул 1 study за 5 мс
2025-01-01T13:06:00.131 DEBUG [db-1]       c.e.o.data.StudyRepository  - SQL findBySuid: bind suid='1.2.840…'
2025-01-01T13:06:00.135 DEBUG [db-1]       c.e.o.data.StudyRepository  - SQL findBySuid вернул 1 study за 4 мс
2025-01-01T13:06:00.136 DEBUG [db-1]       c.e.o.api.ResearchService    - fetchAndMaybeIntersect(): byAn=3 rows, bySuid=3 rows
2025-01-01T13:06:00.137 DEBUG [db-1]       c.e.o.storage.StorageConfigService - resolve(): (G1, ST1) → /nfs
2025-01-01T13:06:00.138 DEBUG [db-1]       c.e.o.api.ResearchService    - buildStudies(): rows=2, studies=1, series=1, instances=2
2025-01-01T13:06:00.145 INFO  [db-1]       c.e.o.api.ResearchService    - research OK: AN=ACC12345, studies=1, 22 мс
```

---

## 9. Развёртывание

### 9.1. Сборка

```bash
mvn -q clean package -DskipTests
ls -lh target/old-eris-index-1.0.0.jar
```

### 9.2. Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/old-eris-index-1.0.0.jar app.jar
RUN mkdir -p /var/log/olderis-index
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
```

### 9.3. Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: old-eris-index
spec:
  replicas: 2
  selector:
    matchLabels: { app: old-eris-index }
  template:
    metadata:
      labels: { app: old-eris-index }
    spec:
      containers:
        - name: app
          image: registry.example.com/old-eris-index:1.0.0
          ports: [{ containerPort: 8080 }]
          env:
            - name: DB_USER
              valueFrom: { secretKeyRef: { name: db-creds, key: username } }
            - name: DB_PASSWORD
              valueFrom: { secretKeyRef: { name: db-creds, key: password } }
            - name: LOG_DIR
              value: /var/log/olderis-index
            - name: LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX
              value: INFO
            - name: APP_STORAGE_CONFIG_FINGERPRINT_INTERVAL
              value: 1h
          volumeMounts:
            - name: logs
              mountPath: /var/log/olderis-index
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 15
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 30
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { cpu: 1000m, memory: 1Gi }
      volumes:
        - name: logs
          emptyDir: {}
```

### 9.4. Проверка после деплоя

```bash
kubectl port-forward svc/old-eris-index 8080:8080

curl -i -X POST http://localhost:8080/api/v1/research \
     -H "Content-Type: application/json" -d '{"an":"ACC12345"}'

curl -s http://localhost:8080/api/v1/admin/storage-config | jq
```

---

## 10. Тестирование

### 10.1. Пирамида

```
         ╱╲            E2E (MockMvc)
        ╱  ╲
       ╱────╲          Integration (Testcontainers)
      ╱      ╲
     ╱────────╲        Unit (Service, Repository, StorageConfig, DTO, Logging)
    ╱──────────╲
```

### 10.2. Unit-тесты

#### 10.2.1. `ResearchRequestTest`

```java
class ResearchRequestTest {
    private final Validator validator = Validation
            .buildDefaultValidatorFactory().getValidator();

    @Test void onlyAn_isValid()   { var r = new ResearchRequest(); r.setAn("ACC1");
                                    assertTrue(validator.validate(r).isEmpty()); }
    @Test void onlySuid_isValid() { var r = new ResearchRequest(); r.setSuid("1.2.3");
                                    assertTrue(validator.validate(r).isEmpty()); }
    @Test void both_isValid()     { var r = new ResearchRequest(); r.setAn("ACC1"); r.setSuid("1.2.3");
                                    assertTrue(validator.validate(r).isEmpty()); }
    @Test void neither_isInvalid(){ assertFalse(validator.validate(new ResearchRequest()).isEmpty()); }
    @Test void tooLongAn_isInvalid()  { var r = new ResearchRequest(); r.setAn("X".repeat(65));
                                        assertFalse(validator.validate(r).isEmpty()); }
    @Test void tooLongSuid_isInvalid(){ var r = new ResearchRequest(); r.setSuid("X".repeat(65));
                                        assertFalse(validator.validate(r).isEmpty()); }
}
```

#### 10.2.2. `ResearchServiceTest`

```java
@ExtendWith(MockitoExtension.class)
class ResearchServiceTest {

    @Mock StudyRepository repo;
    @Mock StorageConfigService storageConfig;
    @Mock ThreadPoolTaskExecutor executor;
    ObjectMapper mapper = new ObjectMapper();
    ResearchService service;

    @BeforeEach void setUp() {
        service = new ResearchService(repo, storageConfig, mapper, executor, 20);
        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            CompletableFuture<Void> f = new CompletableFuture<>();
            try { ((Runnable) inv.getArgument(0)).run(); f.complete(null); }
            catch (Exception e) { f.completeExceptionally(e); }
            return f;
        });
    }

    @Test void byAn_buildsJsonFromFlatRows() {
        when(repo.findByAn("ACC1")).thenReturn(List.of(
                new StudyRow("U1", "SER1", "CT", "I1", "G1", "ST1", "a/b.zip")));
        when(storageConfig.ssPath("G1", "ST1")).thenReturn(Optional.of("/nfs"));

        var req = new ResearchRequest(); req.setAn("ACC1");
        var resp = service.process(req);

        assertEquals("OK", resp.getStatus());
        var study = (Map<String, Object>) resp.getStudies().get(0);
        var series = (List<Map<String, Object>>) study.get("series");
        var instances = (List<Map<String, Object>>) series.get(0).get("instances");
        assertEquals("I1", instances.get(0).get("fileName"));
        assertEquals("/nfs/a\\b.zip", instances.get(0).get("path"));
    }

    @Test void rowWithoutSsPath_isDropped() {
        when(repo.findByAn("ACC1")).thenReturn(List.of(
                new StudyRow("U1", "SER1", "CT", "I1", "G_UNKNOWN", "ST1", "a.zip")));
        when(storageConfig.ssPath("G_UNKNOWN", "ST1")).thenReturn(Optional.empty());

        var req = new ResearchRequest(); req.setAn("ACC1");
        assertThrows(StudyNotFoundException.class, () -> service.process(req));
    }

    @Test void byBoth_intersectionByFileNameAndPath() {
        when(repo.findByAn("ACC1")).thenReturn(List.of(
                new StudyRow("U1", "SER1", "CT", "I1", "G1", "ST1", "a.zip"),
                new StudyRow("U1", "SER1", "CT", "I2", "G1", "ST1", "b.zip")));
        when(repo.findBySuid("1.2.3")).thenReturn(List.of(
                new StudyRow("U1", "SER1", "CT", "I1", "G1", "ST1", "a.zip")));
        when(storageConfig.ssPath("G1", "ST1")).thenReturn(Optional.of("/nfs"));

        var req = new ResearchRequest(); req.setAn("ACC1"); req.setSuid("1.2.3");
        var resp = service.process(req);

        var study = (Map<String, Object>) resp.getStudies().get(0);
        var series = (List<Map<String, Object>>) study.get("series");
        var instances = (List<Map<String, Object>>) series.get(0).get("instances");
        assertEquals(1, instances.size());
        assertEquals("I1", instances.get(0).get("fileName"));
    }
}
```

#### 10.2.3. `StorageConfigServiceTest`

```java
class StorageConfigServiceTest {

    @Mock JdbcTemplate jdbc;
    ObjectMapper mapper = new ObjectMapper();
    StorageConfigService service;

    @BeforeEach void setUp() {
        service = new StorageConfigService(
                jdbc, mapper,
                "/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc",
                "oracle", true);
    }

    @Test void init_loadsAndComputesFingerprint() {
        when(jdbc.queryForObject(contains("SELECT content"), eq(String.class), anyString()))
                .thenReturn(jsonWith("G1", "S1", "/p1"));
        service.init();

        assertEquals(1, service.size());
        assertEquals(Optional.of("/p1"), service.ssPath("G1", "S1"));
        assertEquals(1, service.getVersion());
        assertNotNull(service.getCurrentFingerprint());
    }

    @Test void fingerprintCheck_unchanged_skipsReload() {
        String json = jsonWith("G1", "S1", "/p1");
        String hash = sha256(json);
        when(jdbc.queryForObject(contains("SELECT content"), eq(String.class), anyString()))
                .thenReturn(json);
        when(jdbc.queryForObject(contains("STANDARD_HASH"), eq(String.class), anyString()))
                .thenReturn(hash);
        service.init();
        int v = service.getVersion();

        service.fingerprintCheck();

        assertEquals(v, service.getVersion());
        verify(jdbc, times(1)).queryForObject(contains("SELECT content"),
                eq(String.class), anyString());
    }

    @Test void fingerprintCheck_changed_triggersFullReload() {
        when(jdbc.queryForObject(contains("SELECT content"), eq(String.class), anyString()))
                .thenReturn(jsonWith("G1", "S1", "/p1"))
                .thenReturn(jsonWith("G1", "S1", "/p2"));
        when(jdbc.queryForObject(contains("STANDARD_HASH"), eq(String.class), anyString()))
                .thenReturn("DIFFERENT");
        service.init();

        service.fingerprintCheck();

        assertEquals(2, service.getVersion());
        assertEquals(Optional.of("/p2"), service.ssPath("G1", "S1"));
    }

    @Test void fingerprintCheck_dbError_keepsCache() {
        when(jdbc.queryForObject(contains("SELECT content"), eq(String.class), anyString()))
                .thenReturn(jsonWith("G1", "S1", "/p1"));
        service.init();
        int v = service.getVersion();

        when(jdbc.queryForObject(contains("STANDARD_HASH"), eq(String.class), anyString()))
                .thenThrow(new RuntimeException("db down"));
        service.fingerprintCheck();

        assertEquals(v, service.getVersion());
        assertEquals(Optional.of("/p1"), service.ssPath("G1", "S1"));
    }

    @Test void refresh_keepsOldCacheOnFailure() {
        when(jdbc.queryForObject(contains("SELECT content"), eq(String.class), anyString()))
                .thenReturn(jsonWith("G1", "S1", "/p1"))
                .thenThrow(new RuntimeException("db down"));
        service.init();
        int v = service.getVersion();

        service.refresh();

        assertEquals(v, service.getVersion());
        assertEquals(Optional.of("/p1"), service.ssPath("G1", "S1"));
    }

    private static String jsonWith(String g, String s, String path) { /* … */ }
    private static String sha256(String s) { /* … */ }
}
```

#### 10.2.4. `StudyRepositoryTest`

```java
class StudyRepositoryTest {
    @Test void findByAn_usesAccessionNoBind_andNoDcm4cheeConfig() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        var repo = new StudyRepository(jdbc,
                new ClassPathResource("sql/find-studies.sql"));
        repo.findByAn("ACC1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("st.accession_no = :an"));
        assertFalse(sql.getValue().contains("JSON_TABLE"));
        assertFalse(sql.getValue().contains("dcm4che_config"));
        assertFalse(sql.getValue().contains("{{WHERE}}"));
    }

    @Test void findBySuid_usesStudyIuidBind() { /* аналогично */ }
}
```

#### 10.2.5. `ErrorHandlerTest`

```java
class ErrorHandlerTest {

    private final ErrorHandler handler = new ErrorHandler();

    @Test void onNotFound_returns404_andNoInfoLogs() {
        var appender = newAppender();
        var r = handler.onNotFound(new StudyNotFoundException("ACC1", null));
        assertEquals(404, r.getStatusCode().value());
        assertEquals(0, appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO
                          || e.getLevel() == Level.WARN
                          || e.getLevel() == Level.ERROR).count());
    }

    @Test void onTimeout_returns504_andExactlyOneWarn() {
        var appender = newAppender();
        var r = handler.onTimeout(new ResearchTimeoutException("ACC1", null,
                new java.util.concurrent.TimeoutException()));
        assertEquals(504, r.getStatusCode().value());
        assertEquals(1, appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN).count());
    }

    @Test void onDb_returns502_andExactlyOneError() {
        var appender = newAppender();
        var r = handler.onDb(new ResearchDatabaseException("ACC1", null, new RuntimeException("x")));
        assertEquals(502, r.getStatusCode().value());
        assertEquals(1, appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR).count());
    }

    private static ListAppender<ILoggingEvent> newAppender() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ErrorHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
```

### 10.3. Интеграционные (Testcontainers)

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test-pg")
class PgIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15")
            .withInitScript("testdata/pg-init.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired ResearchService service;
    @Autowired StorageConfigService storageConfig;
    @Autowired JdbcTemplate jdbc;

    @Test void storageConfigLoadedOnStartup() {
        assertTrue(storageConfig.size() > 0);
        assertNotNull(storageConfig.getCurrentFingerprint());
    }

    @Test void fingerprintCheck_detectsChange() {
        jdbc.update("UPDATE dcm4che_config SET content = ? WHERE path = ?", newContent, path);
        storageConfig.fingerprintCheck();
        assertTrue(storageConfig.getVersion() > 1);
    }

    @Test void findsByAn()               { /* … */ }
    @Test void findsBySuid()             { /* … */ }
    @Test void findsByBoth_intersection() { /* … */ }
    @Test void bothMismatch_404()        { /* … */ }
    @Test void notFound_404()            { /* … */ }
}
```

Oracle-вариант — через `OracleContainer("gvenzl/oracle-xe:21-slim")`, nightly.

### 10.4. E2E (`MockMvc`)

```java
@SpringBootTest
@AutoConfigureMockMvc
class ResearchControllerE2ETest {
    @Autowired MockMvc mvc;

    @Test void ok_byAn() throws Exception {
        mvc.perform(post("/api/v1/research")
                .contentType(MediaType.APPLICATION_JSON).content("{\"an\":\"ACC12345\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OK"));
    }
    @Test void ok_byBoth() throws Exception { /* … */ }
    @Test void validation_400() throws Exception { /* … */ }
    @Test void notFound_404() throws Exception { /* … */ }
}

@SpringBootTest
@AutoConfigureMockMvc
class AdminE2ETest {
    @Autowired MockMvc mvc;

    @Test void refreshStorageConfig_returns200() throws Exception {
        mvc.perform(post("/api/v1/admin/storage-config/refresh"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.size").isNumber())
           .andExpect(jsonPath("$.version").isNumber())
           .andExpect(jsonPath("$.fingerprint").exists())
           .andExpect(jsonPath("$.lastFingerprintCheckTime").exists());
    }

    @Test void getStorageConfig_returnsState() throws Exception {
        mvc.perform(get("/api/v1/admin/storage-config"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.path").value("/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc"));
    }
}
```

### 10.5. Тестирование логирования

```java
class DebugLoggingTest {
    @Test void debugNotWrittenAtInfo() { /* ListAppender: 0 DEBUG при INFO */ }
    @Test void debugWrittenAtDebug()   { /* ListAppender: ≥1 DEBUG при DEBUG */ }
}

@SpringBootTest
class ProdLogIntegrationTest {
    @MockBean StudyRepository repo;

    @Test void missAndOk_loggedOnce() {
        when(repo.findByAn("ACC1")).thenReturn(List.of(
                new StudyRow("U1", "SER1", "CT", "I1", "G1", "ST1", "a.zip")));
        // ListAppender на ResearchService
        // assert: ровно 1 "research miss" и 1 "research OK"
    }
}

class StorageConfigLogTest {
    @Test void fingerprintUnchanged_logsNothing() { /* 0 INFO/WARN/ERROR */ }
    @Test void fingerprintChanged_logsTwoInfo()   { /* detected + refreshed */ }
    @Test void refreshFailed_logsOneError()       { /* + keeping */ }
}
```

### 10.6. Матрица тестов

| Тип | Что проверяет | CI |
|---|---|---|
| Unit | `StorageConfigService`: init / fingerprint unchanged / changed / failure / atomic swap | ✅ PR |
| Unit | `ResearchService`: flat rows → JSON, пересечение, drop без ss_path, отсутствие WARN/ERROR | ✅ PR |
| Unit | `StudyRepository`: SQL без `dcm4che_config`/`JSON_TABLE` | ✅ PR |
| Unit | `ErrorHandler`: ровно один WARN/ERROR на 504/502; 404/400 без INFO | ✅ PR |
| Unit | `research miss`/`research OK` по одному разу; debug on/off | ✅ PR |
| Integration PG | startup + fingerprint check + research | ✅ PR |
| Integration Oracle | то же, nightly | ⚠️ nightly |
| E2E | research 200/400/404, admin refresh/get | ✅ PR |
| Integration | `olderis-index-debug.log` создаётся только при DEBUG | ✅ PR |
| Load | RPS, p95 при INFO не хуже базовой | ⚠️ еженедельно |

### 10.7. Ручной чек-лист (smoke)

- [ ] При старте: 1 строка `Storage config loaded on startup: N entries, version 1, fingerprint=…`.
- [ ] `GET /api/v1/admin/storage-config` → 200, есть `fingerprint`, `lastFingerprintCheckTime`.
- [ ] `POST /api/v1/admin/storage-config/refresh` → 200, `version` вырос.
- [ ] Изменить `dcm4che_config` → через `fingerprint-interval` — `fingerprint changed` + `refreshed`.
- [ ] Изменить unrelated column `dcm4che_config` без изменения `content` → fingerprint не меняется → full reload не запускается.
- [ ] Остановить БД → fingerprint check пишет `keeping version V`, research продолжает работать.
- [ ] При старте с пустой `dcm4che_config` → приложение не стартует.
- [ ] `{"an":"<real>"}` → 200, в прод-логе `research miss` + `research OK`.
- [ ] `{"an":"<real>","suid":"<real_intersect>"}` → 200, только общие инстансы.
- [ ] `{"an":"<real>","suid":"<real_no_intersect>"}` → 404, в прод-логе **ничего**.
- [ ] `{}` → 400, в прод-логе **ничего**.
- [ ] Повторный запрос ≤10 мин → hit cache, в прод-логе **ничего**.
- [ ] Остановить БД → 502/504, в прод-логе **одна** строка.
- [ ] `logging.level...=INFO` → `olderis-index-debug.log` не создан.
- [ ] `logging.level...=DEBUG` → `olderis-index-debug.log` содержит `POST`, `SQL findByAn`, `buildStudies()`, `resolve()`.
- [ ] Actuator → переключение DEBUG↔INFO без рестарта.
- [ ] §11.7: `refresh-all-pods.sh refresh` — все pod'ы возвращают 200 и одинаковый `fingerprint`.
- [ ] k6: p95 при `INFO` — в норме.

---

## 11. Эксплуатация

### 11.1. Метрики (Actuator + Micrometer)

`management.endpoints.web.exposure.include: health,info,metrics,prometheus,loggers`

| Метрика | Что означает | Alert |
|---|---|---|
| `http_server_requests_seconds{p95}` | латентность | p95 > 5 s → warn |
| `http_server_requests_total{status="404"}` | видимость 404 | rate > X → info |
| `http_server_requests_total{status="400"}` | видимость 400 | rate > X → info |
| `cache_gets{result="hit"}` | hit rate studies | < 70% → info |
| `hikaricp_connections_active` | коннекты | > 8 из 10 → warn |
| `executor_pool_size` | потоки пула | = max 16 > 5 мин → warn |
| `jvm_memory_used_bytes` | память | > 80% heap → warn |
| `logback_events_total{level="debug"}` | debug-события | > 0 в prod > 15 мин → info |
| `olderis_storage_config_size` | записей в кэше | **0 → critical** |
| `olderis_storage_config_version` | версия кэша | — |
| `olderis_storage_config_last_load_age_seconds` | возраст последней загрузки | > 24h → info |
| `olderis_storage_config_fingerprint_age_seconds` | возраст последней fingerprint-проверки | > 2× `fingerprint-interval` → warn |
| `olderis_storage_config_full_reloads_total` | кол-во full reload'ов | не растёт > недели при ожидаемых изменениях → info |
| `olderis_storage_config_fingerprint_mismatches_total` | кол-во обнаруженных изменений | — |

### 11.2. Прод-лог при `INFO` — сводка

См. §8.2. **Fingerprint unchanged — не пишется ничего.** Fingerprint changed — 2 строки. Reload failed — 1 ERROR. `research miss`/`OK` — по 1 строке. 502/504 — по 1 строке.

**Не пишется:** кэш-хит (метрика), 404/400 (метрики), ss_path lookup.

### 11.3. Admin-эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `POST` | `/api/v1/admin/storage-config/refresh` | Немедленный full reload на **этом** pod'е |
| `GET` | `/api/v1/admin/storage-config` | Состояние кэша |
| `DELETE` | `/api/v1/admin/studies/cache` | (опционально) очистка кэша studies |

**Ограничение доступа:** `/api/v1/admin/**` закрыт от внешнего ingress; вызывается только из ops/monitoring.

### 11.4. Runbook (общие инциденты)

| Симптом | Причина | Действие |
|---|---|---|
| 502/504 массово | БД недоступна / перегружена | `hikaricp_connections_*`; Alert DBA |
| 404 на пару `an+suid`, которая должна пересекаться | `(fileName, path)` не совпадает | включить debug на 5 мин; смотреть `buildStudies()`/`resolve()` |
| research отдаёт устаревший `path` | fingerprint не сработал, или reload падал | §11.6 / §11.7 |
| `Storage config refresh failed` в логах, research работает | БД недоступна на момент reload | проверить БД; кэш сохранён; форсировать после восстановления (§11.7) |
| `olderis_storage_config_fingerprint_age_seconds` > 2× interval | `@Scheduled` не работает / pool занят | проверить логи scheduler; форсировать `POST /refresh` (§11.7) |
| Приложение не стартует | `dcm4che_config` пуста или нет строки | `SELECT content FROM dcm4che_config WHERE path = '...'`; поправить `app.storage-config.path` |
| Кэш studies не работает | Bean self-invocation | `@Cacheable` через прокси |
| OOM | большой JSON в кэше studies | снизить `maximumSize` / weighted cache |
| debug-файл разросся | забыли выключить | `POST /actuator/loggers/... {"configuredLevel":"INFO"}` |
| Не видно 404 | так задумано | `http_server_requests_total{status="404"}` |
| Двойные строки 502/504 | баг | обновить до актуальной версии (инвариант: только `ErrorHandler` пишет) |

### 11.5. Политика debug в prod

- По умолчанию — `INFO`.
- Debug включается **только по инциденту** и **только на конкретном pod'е** (actuator или `kubectl exec`).
- **Ограничение по времени:** через 15 минут вернуть `INFO` — иначе алерт (`logback_events_total{level="debug"} > 0`).
- **Никогда** не включать `DEBUG` глобально (`logging.level.root: DEBUG`).
- `olderis-index-debug.log` не выгружается в ELK/Loki — локальная диагностика, удаляется при ротации.

### 11.6. Штатное изменение `dcm4che_config`

**По умолчанию — ничего делать не нужно.** Изменение подхватится автоматически на **каждой** pod'е за ≤1 час. В логах появятся:

```
INFO Storage config: fingerprint changed (a3f5… → d7b2…), starting full reload
INFO Storage config refreshed: 13 entries, version 2, fingerprint=d7b2…f4a8
```

Проверить, что все pod'ы подхватили:

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s localhost:8080/api/v1/admin/storage-config \
    | jq -c '{version, fingerprint, size}'
done
```

**Если ждать час нельзя** — §11.7.

---

### 11.7. Техподдержка: принудительный refresh на всех pod'ах

> **Раздел для техподдержки.** Использовать при:
> - срочном изменении `dcm4che_config` в Agfa и невозможности ждать `fingerprint-interval`;
> - подозрении, что одна или несколько pod'ов держат устаревший кэш (`olderis_storage_config_fingerprint_age_seconds` высокий, `fingerprint` отличается между pod'ами);
> - после ручного изменения `dcm4che_config` (например, через SQL) для отладки.

**Ключевой момент:** `POST /api/v1/admin/storage-config/refresh` через Service/Ingress попадёт **на одну** pod'у. Чтобы обновить **все** — обратиться к **каждой** pod'е напрямую.

#### Шаг 1. Посмотреть состояние всех pod'ов ДО

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s localhost:8080/api/v1/admin/storage-config \
    | jq -c '{version, fingerprint, size, lastLoadTime, lastFingerprintCheckTime}'
done
```

Ожидаемый вывод — одинаковые `version`, `fingerprint`, `size` на всех pod'ах.

#### Шаг 2. Форсировать refresh на всех pod'ах

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo "=== $p ==="
  kubectl exec "$p" -- curl -s -X POST localhost:8080/api/v1/admin/storage-config/refresh \
    | jq -c '{version, fingerprint, size, lastLoadTime, error}'
done
```

Каждая pod'а должна вернуть **200** и **без поля `error`**. `version` вырастет на 1; `fingerprint` — прежний (если content не менялся) или новый.

#### Шаг 3. Проверить после

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s localhost:8080/api/v1/admin/storage-config \
    | jq -c '{version, fingerprint, size}'
done
```

**Все pod'ы должны показать одинаковый `fingerprint`.** Если различаются — повторить шаг 2 на проблемной; если снова не совпадает — проверить, что pod'ы ходят в одну БД.

#### Готовый скрипт (k8s)

Сохранить как `scripts/refresh-all-pods.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-default}"
LABEL="${LABEL:-app=old-eris-index}"
ENDPOINT="/api/v1/admin/storage-config"
ACTION="${1:-refresh}"   # refresh | status

print_state() {
  kubectl -n "$NAMESPACE" get pods -l "$LABEL" -o name | while read -r p; do
    echo -n "$p: "
    kubectl -n "$NAMESPACE" exec "$p" -- curl -sf "localhost:8080$ENDPOINT" \
      | jq -c '{version, fingerprint, size, error}' || echo "CALL FAILED"
  done
}

if [[ "$ACTION" == "refresh" ]]; then
  echo "=== BEFORE ==="
  print_state
  echo
  echo "=== REFRESH ==="
  kubectl -n "$NAMESPACE" get pods -l "$LABEL" -o name | while read -r p; do
    echo -n "$p: "
    kubectl -n "$NAMESPACE" exec "$p" -- \
      curl -sf -X POST "localhost:8080$ENDPOINT/refresh" \
      | jq -c '{version, fingerprint, size, error}' || echo "REFRESH FAILED"
  done
  echo
  echo "=== AFTER ==="
  print_state
else
  print_state
fi
```

Использование:

```bash
chmod +x scripts/refresh-all-pods.sh

# посмотреть состояние
./scripts/refresh-all-pods.sh status

# форсировать refresh на всех pod'ах
NAMESPACE=prod ./scripts/refresh-all-pods.sh refresh
```

#### Аналоги для других окружений

**Docker (несколько контейнеров на хосте):**

```bash
docker ps --filter "name=old-eris-index" --format "{{.Names}}" | while read c; do
  echo -n "$c: "
  docker exec "$c" curl -s -X POST localhost:8080/api/v1/admin/storage-config/refresh \
    | jq -c '{version, fingerprint, size, error}'
done
```

**Bare-metal (SSH + systemd):**

```bash
for host in oei-1 oei-2 oei-3; do
  echo -n "$host: "
  ssh "$host" 'curl -s -X POST localhost:8080/api/v1/admin/storage-config/refresh' \
    | jq -c '{version, fingerprint, size, error}'
done
```

**Ansible:**

```yaml
- hosts: old_eris_index
  become: false
  tasks:
    - name: Force storage config refresh
      uri:
        url: http://localhost:8080/api/v1/admin/storage-config/refresh
        method: POST
        return_content: true
      register: r

    - name: Show result
      debug:
        msg: "{{ inventory_hostname }}: {{ r.json | to_json }}"
```

**Prometheus/Alertmanager:** при срабатывании алерта `olderis_storage_config_fingerprint_age_seconds > 7200` автоматически запускать refresh (webhook → ansible / k8s job).

#### Что делать, если одна pod не отвечает

1. Проверить `Running`/`Ready` и `livenessProbe`:
   ```bash
   kubectl get pods -l app=old-eris-index
   kubectl describe pod <pod-name>
   ```
2. Проверить процесс:
   ```bash
   kubectl exec <pod-name> -- curl -sv localhost:8080/actuator/health
   ```
3. Проверить, что pod ходит в ту же БД:
   ```bash
   kubectl exec <pod-name> -- curl -s localhost:8080/api/v1/admin/storage-config | jq
   ```
4. Если `POST /refresh` возвращает 500 `No config row` — проверить доступ к `dcm4che_config` из этой pod'ы (Vault/Secret, сеть).
5. Если `POST /refresh` возвращает 200, но `fingerprint` не меняется — проверить, что `dcm4che_config` в БД **действительно** изменилась:
   ```sql
   SELECT RAWTOHEX(STANDARD_HASH(content,'SHA256')) FROM dcm4che_config WHERE path = '...';
   ```
6. Если ничего не помогает — перезапустить pod:
   ```bash
   kubectl delete pod <pod-name>
   ```

#### Что НЕ делать

- ❌ Не вызывать `POST /refresh` через Service `http://old-eris-index/api/...` — попадёт на **одну** pod'у.
- ❌ Не перезапускать все pod'ы одновременно — downtime; использовать `POST /refresh`.
- ❌ Не редактировать `dcm4che_config` вручную в prod без согласования с DBA.
- ❌ Не снижать `fingerprint-interval` до секунд — вернётся нагрузка на Oracle.

#### Чек-лист техподдержки

- [ ] `./scripts/refresh-all-pods.sh status` — состояние всех pod'ов.
- [ ] Если `fingerprint` различается → `./scripts/refresh-all-pods.sh refresh`.
- [ ] Если после refresh всё ещё различается → проверить, что все pod'ы в одну БД; перезапустить отстающую.
- [ ] Проверить research на известный AN — `path` соответствует новым storage.
- [ ] Зафиксировать в тикете: `version` до/после, `fingerprint` до/после, время.

---

### 11.8. Мультиподинг и согласованность кэшей

**По дизайну каждая pod держит свой кэш `dcm4che_config` в своём heap.** Общего кэша (Redis и т.п.) нет.

| Свойство | Значение |
|---|---|
| Память | `Map<StorageKey, String>` на pod'у — единицы–десятки KB |
| Согласованность в норме | ≤ `fingerprint-interval` (1h) — каждая pod независимо обнаруживает изменение |
| Согласованность при форсировании | 0 — после §11.7 все pod'ы одинаковы |
| Сеть между pod'ами | Не требуется |
| Leader election | Не требуется |
| Single point of failure | Нет |
| Что если pod упала | При старте — full load; кэш пересоздаётся |

**Когда стоит пересмотреть (в сторону общего кэша):**

- pod'ов ≥5 и 1h-окно неприемлемо;
- жёсткое требование «все pod'ы видят одинаковый конфиг в одну секунду»;
- кэш storage config вырастет до сотен MB (маловероятно для Agfa).

В этих случаях — Redis + pub/sub на инвалидацию (вне scope текущей версии). Сейчас локальный кэш + fingerprint + §11.7 — оптимум.

---

## 12. Миграция Oracle → PostgreSQL

### 12.1. Что нужно сделать

| Шаг | Действие |
|---|---|
| 1 | Перенести данные (все таблицы, включая `dcm4che_config`) |
| 2 | `app.db.dialect: postgres` |
| 3 | `spring.datasource.url` + `driver-class-name` |
| 4 | Integration-тесты PG |
| 5 | Smoke на stage |
| 6 | Prod (blue/green) |

**SQL менять не нужно:** `find-studies.sql` и `load.sql` идентичны для обоих диалектов; меняется только fingerprint (`STANDARD_HASH` ↔ `md5`) — сервис выбирает файл по `app.db.dialect`.

### 12.2. Что НЕ меняется

Java-код, `StorageConfigService`, `ResearchService`, кэш, executor, API-контракт (включая admin), логирование, метрики, безопасность, имена таблиц.

### 12.3. Подводные камни

| Oracle | PostgreSQL | Решение |
|---|---|---|
| `''` = NULL | `''` ≠ NULL | валидация `!isBlank()` |
| CLOB | TEXT | `rs.getString(1)` |
| `STANDARD_HASH` | `md5()` | разные файлы fingerprint |

---

## 13. Roadmap и известные ограничения

### 13.1. Roadmap

| Версия | Что | Приоритет |
|---|---|---|
| **1.0 (текущая)** | AN/SUID/пересечение, кэш studies, пул, Oracle+PG, безопасность, кэш storage config с fingerprint, чистый прод-лог, отключаемый debug, admin-endpoints, §11.7 | ✅ |
| 1.1 | Prometheus + Grafana dashboard (включая storage config метрики) | Should |
| 1.2 | Rate limiting (Bucket4j) | Should |
| 1.3 | Аутентификация (JWT через API Gateway), включая admin | Must (prod) |
| 1.4 | Общий кэш `dcm4che_config` (Redis) — при pod'ов ≥5 и 1h-окне | Nice |
| 1.5 | `GET /api/v1/research?an=...&suid=...` | Nice |
| 1.6 | Стриминговый ответ для больших studies | Nice |
| 1.7 | OpenAPI/Swagger | Should |

### 13.2. Известные ограничения

1. Кэш studies не инвалидируется автоматически — TTL 10 мин.
2. Ответ держится целиком в памяти — при study >100k instances возможен OOM; при необходимости — стриминг.
3. Нет аутентификации клиентов и admin-эндпоинтов — внутренняя сеть / Gateway.
4. Каждая pod держит свой кэш `dcm4che_config`; окно расхождения ≤ `fingerprint-interval` (1h) автоматически; 0 после §11.7 (§11.8).
5. `fingerprint-interval: 0` отключает автоматическое обнаружение — обновление только вручную/при перезапуске.
6. `STANDARD_HASH` (Oracle 12.1+) и `md5()` (PG) — при переходе на другие СУБД нужен свой fingerprint-файл.
7. Парсинг в Java — при очень крупном JSON (сотни MB) старт может быть медленным; для Agfa (десятки–сотни KB) — незначимо.
8. PII в логах (AN/SUID в `research miss`/`OK` и 502/504) — при требовании хэшировать.
9. Точное совпадение `(fileName, path)` без нормализации — регистр/пробелы/`/` vs `\` сравниваются как есть.
10. `version` — локальный счётчик успешных full reload'ов на pod'е (не глобальный); для сравнения между pod'ами использовать `fingerprint` (§11.7).
11. **Размер кэша storage config в памяти:** постоянно живёт только `Map<StorageKey, String>` (единицы–десятки KB). Сырой JSON и `JsonNode` освобождаются после парсинга. На refresh короткий момент в памяти два `Map` (~2× кэша), после атомарного swap старый собирается GC. При неудачном refresh кэш не меняется.

---

## 14. Приложения

### A. Структура проекта

```
old-eris-index/
├── pom.xml
├── Dockerfile
├── README.md
├── scripts/
│   └── refresh-all-pods.sh              ← §11.7
├── src/main/java/com/example/olderisindex/
│   ├── OldErisIndexApplication.java
│   ├── api/
│   │   ├── ResearchController / ResearchRequest / ResearchResponse / ResearchService
│   │   ├── admin/StorageConfigController
│   │   └── error/…
│   ├── config/
│   │   ├── ExecutorConfig
│   │   └── SqlConfig
│   ├── data/
│   │   ├── StudyRepository
│   │   └── StudyRow
│   └── storage/
│       ├── StorageConfigService
│       ├── StorageKey
│       └── StorageConfigException
├── src/main/resources/
│   ├── application.yml
│   ├── logback-spring.xml
│   └── sql/
│       ├── find-studies.sql
│       └── storage-config/
│           ├── load.sql
│           ├── fingerprint.oracle.sql
│           └── fingerprint.postgres.sql
├── src/test/java/…
├── src/test/resources/testdata/pg-init.sql
└── k6/load.js
```

### B. Переменные окружения

| Переменная | Обязательна | Описание |
|---|---|---|
| `DB_USER` | да | Логин БД |
| `DB_PASSWORD` | да | Пароль |
| `LOG_DIR` | нет | `/var/log/olderis-index` |
| `LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX` | нет | `INFO` (default) / `DEBUG` |
| `APP_STORAGE_CONFIG_FINGERPRINT_INTERVAL` | нет | `1h` (default); `0` — выключить fingerprint |
| `APP_STORAGE_CONFIG_PATH` | нет | путь к строке в `dcm4che_config` |
| `JAVA_OPTS` | нет | `-XX:MaxRAMPercentage=75` |

### C. Контрольный список приёмки (DoD)

- [ ] Unit: `StorageConfigService` — init / fingerprint unchanged / changed / failure / atomic swap.
- [ ] Unit: `ResearchService` — flat rows → JSON, пересечение, drop без ss_path, **не пишет WARN/ERROR**.
- [ ] Unit: `StudyRepository` — SQL без `dcm4che_config`/`JSON_TABLE`, с `:an`/`:suid`.
- [ ] Unit: `ErrorHandler` — ровно один WARN/ERROR на 504/502; 404/400 без INFO.
- [ ] Unit: `research miss`/`research OK` — по одному разу на запрос.
- [ ] Unit: debug при INFO не пишется; `olderis-index-debug.log` не создаётся.
- [ ] Integration PG: startup + fingerprint check + research.
- [ ] Integration Oracle: то же, nightly.
- [ ] E2E: research 200/400/404; admin refresh/get.
- [ ] **Smoke: fingerprint unchanged не делает full SELECT (AWR / pg_stat_statements).**
- [ ] **Smoke: §11.7 `refresh-all-pods.sh refresh` — все pod'ы возвращают 200 и одинаковый `fingerprint`.**
- [ ] k6: p95 при `INFO` в норме (≤300 мс hit, ≤5 с miss).
- [ ] Пароль не в git (`git grep DB_PASSWORD`).
- [ ] Runbook обновлён (§11.4, §11.6, §11.7, §11.8).
- [ ] **§11.7 протестирован на stage минимум на 2 pod'ах.**
- [ ] Дашборд Grafana с метриками §11.1, включая `olderis_storage_config_*`.
- [ ] Alertmanager: `olderis_storage_config_size == 0` → critical; `olderis_storage_config_fingerprint_age_seconds > 7200` → warn; 5xx > 1%; debug > 15 мин.
- [ ] Admin-эндпоинты закрыты от внешнего ingress.
- [ ] **Техподдержка ознакомлена с §11.7; скрипт `refresh-all-pods.sh` в репозитории и доступен on-call.**
- [ ] Политика debug (§11.5) согласована с on-call.

### D. Глоссарий

| Термин | Расшифровка |
|---|---|
| **OldErisIndex** | Название сервиса |
| **AN** | Accession Number |
| **SUID** | Study Instance UID |
| **Agfa** | PACS/архив DICOM; имена таблиц при этом исторические (`dcm4che_config`) |
| **ERIS** | Архив медицинских исследований |
| **Study / Series / Instance** | Исследование / серия / один DICOM-файл, физически упакованный в zip |
| **Zip-архив** | Контейнер хранения DICOM-файлов; путь — в поле `path` |
| **Storage config** | Кэш `Map<(group_id, storage_id) → ss_path>`, построенный из `dcm4che_config` |
| **`StorageKey`** | Пара `(storage_group_id, storage_id)` |
| **`ss_path`** | Базовый путь storage system |
| **`full_path`** | `ss_path + '/' + storage_path.replace('/', '\\')` |
| **Пересечение (AN+SUID)** | Инстансы с совпадающей парой `(fileName, path)` в обоих поисках |
| **Fingerprint** | SHA-256 (Oracle) / MD5 (PG) от `content`; дешёвый детект изменений |
| **`fingerprint-interval`** | Период fingerprint-полинга (1h) |
| **Full reload** | Полный `SELECT content` + парсинг + атомарный swap |
| **`version`** | Локальный счётчик успешных reload'ов на pod'е |
| **`lastLoadTime` / `lastFingerprintCheckTime`** | Временные метки успешной загрузки / проверки |
| **`research miss` / `research OK`** | INFO-строки о cache-miss / успехе |
| **`olderis-index-debug.log`** | Отдельный файл для DEBUG |
| **§11.7 / §11.8** | Разделы для техподдержки и мультиподинга |
| **RPS / TTL** | Requests Per Second / Time To Live |

---

**Конец документа.** При изменении контракта, добавлении endpoint, изменении уровней логирования, политики debug, **политики refresh storage config** или **процедуры §11.7** — обновляйте разделы 2, 4, 8, 10, 11.2, 11.4, 11.6, 11.7, 11.8 и 13 одновременно.