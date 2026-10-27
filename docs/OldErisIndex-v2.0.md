---
title: "OldErisIndex — документация"
subtitle: "Сервис-индекс «старого» архива ERIS (мультикластерная версия)"
version: "2.0"
date: "2025-01-01"
lang: ru-RU
---

# Документация сервиса `OldErisIndex`

**Версия документа:** 2.0
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
    - [11.9. Мультикластерность: семантика и правила](#119-мультикластерность-семантика-и-правила)
12. [Миграция Oracle → PostgreSQL](#12-миграция-oracle--postgresql)
13. [Roadmap и известные ограничения](#13-roadmap-и-известные-ограничения)
14. [Приложения](#14-приложения)

---

## 1. Постановка задачи

### 1.1. Контекст

В медицинской информационной системе на базе **Agfa** (PACS/архив DICOM) с СУБД Oracle работают **два кластера**:

- **A** — амбулаторный;
- **B** — стационарный.

В каждом кластере — свои storage systems (NFS/S3/локальные тома), своя таблица `dcm4che_config` с JSON-конфигом (путь `/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc`), своя БД (или как минимум отдельное подключение). DICOM-файлы упакованы в **zip-архивы**. `dcm4che_config` меняется **крайне редко** — раз в неделю или месяц.

Кластеры **не пересекаются по смыслу** (амбулаторные vs стационарные пациенты), но **при миграции пациентов** исследования могли быть скопированы из одного кластера в другой. В результате один и тот же инстанс DICOM (уникальный `sop_iuid`) может физически лежать в обоих кластерах с **разными путями** к zip-архивам.

Требуется REST-сервис **OldErisIndex**, который по **AN (Accession Number)** и/или **SUID (Study Instance UID)** исследования возвращает список файлов (SOP Instance UID + полный путь к zip-архиву), **консультируя оба кластера** и объединяя результаты.

### 1.2. Проблема

**Сейчас сервиса нет.** Есть **одноразовый SQL-запрос**, написанный вручную, который работает с **одной** БД и проверен на реальных данных. Он подтверждает гипотезу «из БД Agfa можно получить полные пути к архивам по AN», но не решает задачу в целом:

- **Только один кластер.** Чтобы получить полную картину, интеграция должна сама опрашивать две БД, склеивать результаты и разбираться с дублями.
- **SQL-инъекции** — AN подставляется в текст запроса вручную. Любая интеграция повторяет эту практику.
- **Нет API** — нет стандартного способа получить данные программно.
- **Нет кэширования, лимитов, таймаутов** — тяжёлый запрос может положить БД.
- **Поддерживается только AN** — поиск по SUID отсутствует.
- **Запрос захардкожен под Oracle** — нет переносимости.
- **JSON собирается вручную в SQL** — спецсимволы (`\`, `"`) ломают ответ.
- **Не описано, что именно `path`** — путь к dcm или к zip.
- **Нет диагностического лога** — при инциденте («должен был найтись study, а 404») нет способа посмотреть, что выполнилось.
- **Прод-лог шумит** — легко получить дублирование или, наоборот, «немой» прод.
- **`dcm4che_config` парсится на каждом запросе** — при 50 RPS это 50 разборов JSON в секунду ×2 кластера, при том что конфиг меняется раз в месяц.

Иными словами, **исследовательский SQL нужно «упаковать» в продуктивный мультикластерный сервис**, скрывающий детали СУБД, обеспечивающий безопасность, устойчивость к нагрузке, стабильный контракт, чистый прод-лог, отключаемую диагностику и кэш редко меняющегося конфига.

### 1.3. Цель

Создать REST-сервис **OldErisIndex** на Java/Spring Boot, который:

- принимает `{"an": "..."}`, `{"suid": "..."}` или **оба сразу**;
- при одном критерии — ищет по нему; при двух — ищет пересечение;
- **опрашивает оба кластера параллельно**, объединяет результаты;
- возвращает JSON `{uid, series: [{uid, modality, instances: [{fileName, path}]}]}`, где `fileName` = SOP Instance UID, `path` = полный путь к zip-архиву;
- **дедуплицирует инстансы по `sop_iuid`**: если один и тот же инстанс есть в обоих кластерах — одна запись, путь из **первого** кластера в конфиге;
- **объединяет study/series по `study_iuid` / `series_iuid`**: если один study есть в обоих кластерах — один объект, серии и инстансы — union;
- **работает с живым кластером, даже если второй недоступен**, и **явно логирует WARN** о возможной неполноте результата;
- работает на Oracle 12c и PostgreSQL 12+ без изменения кода (только конфиг);
- защищён от SQL-инъекций, таймаутов и пиковой нагрузки;
- не хранит пароли БД в конфиге;
- **кэширует `dcm4che_config` в памяти** отдельно для каждого кластера, с двухуровневым обновлением (fingerprint-полинг + full reload при изменении);
- **имеет отключаемый debug-уровень** с отдельным `olderis-index-debug.log`;
- **ведёт чистый прод-лог**;
- **поддерживает мультиподинг** (каждая pod — свой кэш по каждому кластеру).

### 1.4. Границы (scope)

**В scope:**

- REST `POST /api/v1/research`;
- admin: `GET /api/v1/admin/storage-config`, `GET /api/v1/admin/storage-config/{cluster}`, `POST /api/v1/admin/storage-config/refresh`, `POST /api/v1/admin/storage-config/{cluster}/refresh`;
- fan-out по всем кластерам параллельно, объединение результатов;
- кэш studies (Caffeine), кэш storage config по каждому кластеру;
- пул потоков, таймауты, HTTP-коды;
- поддержка Oracle 12c+ и PostgreSQL 12+ (в т.ч. смешанные кластеры);
- отключаемый debug и чистый прод-лог;
- документация и тесты.

**Вне scope:**

- аутентификация/авторизация клиентов и admin-эндпоинтов (решается на уровне API Gateway);
- общий кэш между pod'ами (Redis и т.п.) — см. §11.8;
- запись данных, изменение исследований;
- работа с DICOM-файлами и zip-архивами — только метаданные;
- UI / Swagger-UI.

### 1.5. Происхождение SQL

SQL-шаблон — **доработанный исследовательский запрос**, написанный вручную. Продуктивная реализация:

- сохраняет логику JOIN-цепочки (`study → series → instance → rel_instance_location → location`);
- заменяет литерал `WHERE accession_no = '...'` на bind-параметр;
- добавляет ветку по SUID;
- **выносит разбор `dcm4che_config` из SQL в Java**;
- **собирает JSON в Java** вместо `XMLAGG`/`jsonb_agg`;
- **имена таблиц и путей** оставлены как были (`dcm4che_config`, `.../dcm4chee-arc`).

### 1.6. Название

**OldErisIndex** — сервис-индекс «старого» архива ERIS.

---

## 2. Требования

### 2.1. Функциональные (FR)

| ID | Требование | Приоритет |
|---|---|---|
| FR-1 | Принять `POST /api/v1/research` с `an`, `suid` или обоими полями | Must |
| FR-2 | Если задано ни одного поля → `400 Bad Request` | Must |
| FR-3 | При одном поле — поиск по нему во всех кластерах | Must |
| FR-4 | При двух полях — пересечение **по `fileName`** (sop_iuid) между `union_AN` и `union_SUID` | Must |
| FR-5 | Вернуть JSON `{uid, series:[{uid, modality, instances:[{fileName, path}]}]}`, где `fileName` = `sop_iuid`, `path` = путь к zip-архиву | Must |
| FR-6 | Не найдено ни в одном кластере (все живы) → `404` + `{"status":"NOT_FOUND"}` | Must |
| FR-7 | Ошибка БД **всех** кластеров → `502` + `{"status":"ERROR"}` | Must |
| FR-8 | Таймаут **всех** кластеров → `504` | Must |
| FR-9 | Успех → `200` + `{"status":"OK","studies":[...]}` | Must |
| FR-10 | Кэш studies (Caffeine, TTL 10 мин, ключ `an|suid`) | Must |
| FR-11 | Пул потоков для параллельных запросов к БД | Must |
| FR-12 | Study/series без инстансов — удаляются из ответа | Must |
| FR-13 | Отключаемый debug (по умолчанию `INFO`, включается `DEBUG` без пересборки) | Must |
| FR-14 | Debug — в отдельном `olderis-index-debug.log`; при `INFO` не создаётся | Must |
| FR-15 | При выключенном debug — нулевой overhead | Must |
| FR-16 | Прод-лог: `research miss` (1 строка на cache-miss) и `research OK` (1 строка на успех) | Must |
| FR-17 | 502/504 логируются ровно один раз — в `ErrorHandler` или `ResearchService` при all-failed | Must |
| FR-18 | `404`/`400` не пишутся в прод-лог на INFO | Must |
| **FR-19** | **`dcm4che_config` каждого кластера загружается при старте** | **Must** |
| **FR-20** | **Fingerprint-полинг по каждому кластеру** независимо (`fingerprint-interval: 1h`); при изменении — full reload | **Must** |
| **FR-21** | **Ручной full reload** по `POST /api/v1/admin/storage-config/{cluster}/refresh` или `/refresh` (все кластеры) | **Must** |
| **FR-22** | При ошибке refresh — сохраняется предыдущая версия кэша **этого** кластера | **Must** |
| **FR-23** | `GET /api/v1/admin/storage-config` возвращает массив статусов **всех** кластеров | **Must** |
| **FR-24** | Research-запрос **не выполняет** `SELECT content FROM dcm4che_config` | **Must** |
| **FR-25** | Fingerprint-полинг не блокирует research | **Must** |
| **FR-26** | Fingerprint-полинг не делает full reload, если hash не изменился | **Must** |
| **FR-27** | **Все кластеры опрашиваются параллельно** | **Must** |
| **FR-28** | **Дедупликация по `sop_iuid`**: один инстанс в двух кластерах → одна запись; `path` из первого кластера в конфиге | **Must** |
| **FR-29** | **Union study/series**: одинаковые `study_iuid`/`series_iuid` в разных кластерах → один объект в ответе | **Must** |
| **FR-30** | **Один кластер недоступен, другой отдал данные** → `200` + данные живого; **WARN-лог** о возможной неполноте | **Must** |
| **FR-31** | **Один кластер недоступен, живой пуст** → `404` + **WARN-лог** «исследование может существовать в недоступном кластере» | **Must** |
| **FR-32** | **Оба кластера недоступны** → `502` (или `504`, если все ошибки — таймауты) | **Must** |

### 2.2. Нефункциональные (NFR)

| ID | Требование | Значение |
|---|---|---|
| NFR-1 | Поддержка СУБД | Oracle 12c+ и PostgreSQL 12+ (в т.ч. смешанные кластеры) |
| NFR-2 | Пропускная способность | ≥50 RPS при кэш-хите, ≥10 RPS на кластер |
| NFR-3 | Латентность p95 | ≤300 мс (кэш), ≤5 с (БД, оба кластера параллельно) |
| NFR-4 | Таймаут запроса к БД (суммарно на все кластеры) | 20 с (конфигурируется) |
| NFR-5 | Отсутствие SQL-инъекций | все значения — bind-параметры |
| NFR-6 | Пароли БД не в конфиге | env/Vault/Wallet; **у каждого кластера свой** |
| NFR-7 | Логирование ошибок с контекстом | `AN=...`, `SUID=...`, `cluster=...` |
| NFR-8 | Graceful shutdown | дождаться задач ≤30 с; закрыть все Hikari-пулы |
| NFR-9 | Overhead debug при `INFO` | ≤ 0.1% latency |
| NFR-10 | Ротация debug-файла | 200 MB × 7 дней, total 2 GB |
| NFR-11 | Включение/выключение debug | без пересборки |
| NFR-12 | Ротация прод-лога | 100 MB × 30 дней, total 5 GB |
| NFR-13 | Дублей в прод-логе на одно событие | 0 |
| NFR-14 | Нагрузка на `dcm4che_config` (по каждому кластеру) | 1 full SELECT при старте; 1 hash-SELECT раз в `fingerprint-interval`; full SELECT — только при изменении или вручную; 0 при research |
| NFR-15 | Refresh не блокирует research | `AtomicReference` per cluster + атомарный swap |
| NFR-16 | Lookup в кэш storage config | O(1), HashMap, ≤ 1 мкс на кластер |
| NFR-17 | Concurrency refresh | Одновременные refresh одного кластера сериализуются; research не блокируется |
| NFR-18 | Окно расхождения между pod'ами | ≤ `fingerprint-interval` автоматически; 0 после §11.7 |
| **NFR-19** | **Параллельный fan-out** | **Кластеры не влияют друг на друга по латентности** (оба стартуют одновременно) |
| **NFR-20** | **Изоляция сбоя кластера** | **Сбой одного кластера не приводит к `5xx`, если второй отдал данные** |

### 2.3. Технологический стек

- Java 17, Spring Boot 3.3
- Spring JDBC (`NamedParameterJdbcTemplate`, `JdbcTemplate`)
- Spring Cache + Caffeine
- Spring Scheduling (`@EnableScheduling`)
- Spring Boot Actuator
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
│  │  ResearchService  (fan-out orchestrator)        │  │
│  │  @Cacheable studies key=an|suid                 │  │
│  │  callAllClusters()  ── параллельно ──┐          │  │
│  │  merge() / buildStudies()            │          │  │
│  │  dedupe by fileName (sop_iuid)       │          │  │
│  └────────────────┬─────────────────────┼──────────┘  │
│                   │                     │             │
│         ┌─────────┴─────────┐           │             │
│         ▼                   ▼           │             │
│  ┌─────────────┐     ┌─────────────┐    │             │
│  │ Repo(A)     │     │ Repo(B)     │◄───┘             │
│  │ JDBC pool A │     │ JDBC pool B │                  │
│  └──────┬──────┘     └──────┬──────┘                  │
│         │                   │                         │
│  ┌──────▼──────┐     ┌──────▼──────┐                  │
│  │ StorageCfg  │     │ StorageCfg  │                  │
│  │ state A     │     │ state B     │                  │
│  │ fingerprint │     │ fingerprint │                  │
│  └──────┬──────┘     └──────┬──────┘                  │
│         │                   │                         │
│  ┌──────┴───────────────────┴──────┐                  │
│  │  ClusterRegistry                │                  │
│  │  Map<name, ClusterContext>      │                  │
│  │  (dataSource, jdbc, namedJdbc)  │                  │
│  └─────────────────────────────────┘                  │
│                                                       │
│  ┌─────────────────────────────────────────────────┐  │
│  │  ErrorHandler / StorageConfigController         │  │
│  │  logback-spring.xml                             │  │
│  └─────────────────────────────────────────────────┘  │
└──────────┬──────────────────────────┬─────────────────┘
           │ JDBC (bind)              │ JDBC (bind)
           ▼                          ▼
┌──────────────────────┐   ┌──────────────────────┐
│  Agfa A (амбулатор.) │   │  Agfa B (стацион.)   │
│  study/series/…      │   │  study/series/…      │
│  dcm4che_config      │   │  dcm4che_config      │
└──────────────────────┘   └──────────────────────┘
```

### 3.2. Поток research-запроса

1. Controller → Service (`@Valid`, debug-лог входящих `an`/`suid`).
2. Кэш studies: hit → ответ; miss → `INFO "research miss"`.
3. `callAllClusters(an, suid)`:
   - для **каждого** кластера — `CompletableFuture.supplyAsync(..., executor)`;
   - каждый фьючер выполняется в `dbExecutor` (пул 8–16), все стартуют **одновременно**;
   - общий дедлайн — `db-timeout-seconds` (20 с); на каждый фьючер — `get(remaining, ms)`.
4. `ClusterResult` от каждого: `ok + byAn + bySuid` или `failed + cause`.
5. Считаем `okClusters` / `failedClusters`:
   - **0 ok** → `502` (или `504` если все причины — `TimeoutException`), пишем `ERROR`-лог.
   - **есть failed, но ≥1 ok** → `WARN research PARTIAL` **до** дальнейшей обработки.
6. `merge(results, an, suid)`:
   - только AN → `union(byAn по всем кластерам)`, dedupe по `fileName`;
   - только SUID → `union(bySuid по всем кластерам)`, dedupe по `fileName`;
   - оба → `union(AN) ∩ union(SUID)` по `fileName`.
7. `buildStudies(merged)`: union study/series, dedupe instances по `fileName`; `path` — по `StorageConfigService.ssPath(cluster, groupId, storageId)` для строки-«победителя».
8. Пусто → `404` (+ `WARN`, если был `failedClusters`).
9. Иначе — `INFO "research OK"` с `clusters_ok`/`clusters_failed`, `200 OK`.

### 3.3. Поток StorageConfigService (per cluster)

Старт (`@PostConstruct`):

```
for each cluster:
  try:
    json  = SELECT content FROM dcm4che_config WHERE path = ?
    hash  = SELECT <hash>(content) FROM dcm4che_config WHERE path = ?
    map   = parse(json)          // Jackson → Map<StorageKey, ss_path>
    state[cluster] = {map, hash, version=1, ready=true}
    INFO "Storage config loaded: cluster=…, entries=…, version=1, fingerprint=…"
  catch:
    ERROR "Storage config failed on startup: cluster=…"
    if cluster.storageConfig.initialLoadFailFast → throw
apply policy (require-any / require-all / best-effort)
schedule fingerprint for each cluster (общий scheduled pool, base interval = min)
```

Fingerprint-тик (общий, каждые `min(interval_i)`):

```
for each cluster where fingerprintEnabled:
  try:
    newFp = SELECT <hash>(content) FROM dcm4che_config WHERE path = ?
    state[cluster].lastFingerprintCheckTime = now()
    if newFp == state.currentFingerprint → return
    INFO "Storage config: cluster=…, fingerprint changed (…→…), reloading"
    refresh(cluster)
  catch: ERROR "Fingerprint check failed: cluster=…"
```

Full reload (`refresh(cluster)`, synchronized):

```
json = SELECT content …
hash = SELECT hash …
map  = parse(json)
if map.isEmpty → ERROR "Empty, keeping version V"; return
state[cluster] = {map, hash, version+1, ready=true}
INFO "Storage config refreshed: cluster=…"
```

Ручной refresh: `POST /api/v1/admin/storage-config/refresh` (все кластеры) или `POST /api/v1/admin/storage-config/{cluster}/refresh` (один).

### 3.4. Диаграмма последовательности (AN + SUID, оба кластера живы)

```
Client → Controller → Service
Service → Cache: miss
Service → Log(INFO): "research miss"
Service → Executor: submit(A, findByAn+findBySuid)   ─┐
Service → Executor: submit(B, findByAn+findBySuid)   ─┴── параллельно
Executor(A) → DB(A): SELECT …
Executor(B) → DB(B): SELECT …
Executor → Service: ClusterResult A (ok, byAn, bySuid)
Executor → Service: ClusterResult B (ok, byAn, bySuid)
Service: merge → union_AN ∩ union_SUID по fileName
Service: buildStudies → union study/series, dedupe instances
Service → Log(INFO): "research OK: …, clusters_ok=[A,B], clusters_failed=[]"
Service → Controller → Client: 200 OK
```

### 3.5. Диаграмма последовательности (один кластер упал, второй отдал данные)

```
Service → Executor: submit(A)  →  TimeoutException
Service → Executor: submit(B)  →  ok, rows
Service: okClusters=[B], failedClusters=[A]
Service → Log(WARN): "research PARTIAL: …, clusters_ok=[B], clusters_failed=[A]. "
                     "Результат может быть неполным: в недоступном кластере "
                     "могли быть дополнительные серии/инстансы для этого исследования."
Service: merge (только B), buildStudies
Service → Log(INFO): "research OK: …, clusters_ok=[B], clusters_failed=[A]"
Service → Client: 200 OK (данные из B)
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
| задан | — | `union` по всем кластерам, поиск по AN |
| — | задан | `union` по всем кластерам, поиск по SUID |
| задан | задан | `union(AN) ∩ union(SUID)` по `fileName` |
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

### 4.2. Ответы

| HTTP | `status` | Когда | Прод-лог |
|---|---|---|---|
| `200` | `OK` | Найдено хотя бы в одном кластере | `INFO research OK` (+ `WARN research PARTIAL` если были failed) |
| `400` | `ERROR` | Ни одного поля / длина >64 | — (метрика) |
| `404` | `NOT_FOUND` | Не найдено во всех живых кластерах | `INFO` (все живы) / `WARN` (был failed) |
| `502` | `ERROR` | Все кластеры упали (не таймаут) | `ERROR` |
| `504` | `ERROR` | Все кластеры — таймаут | `ERROR` |

**200 OK** — `fileName` = SOP Instance UID, `path` = путь к zip-архиву. **Никаких `partial`/`clusters` в теле** — обычный JSON:

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

### 4.3. Admin-эндпоинты

**`GET /api/v1/admin/storage-config`** — массив статусов всех кластеров:

```json
[
  {
    "name": "A",
    "ready": true,
    "size": 12,
    "version": 42,
    "fingerprint": "a3f5…e8c1",
    "lastLoadTime": "2025-01-01T12:00:00Z",
    "lastFingerprintCheckTime": "2025-01-01T13:00:00Z",
    "path": "/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc",
    "lastError": null
  },
  {
    "name": "B",
    "ready": true,
    "size": 8,
    "version": 15,
    "fingerprint": "b7c1…f0d2",
    "lastLoadTime": "2025-01-01T12:00:00Z",
    "lastFingerprintCheckTime": "2025-01-01T13:00:00Z",
    "path": "/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc",
    "lastError": null
  }
]
```

**`GET /api/v1/admin/storage-config/{cluster}`** — один объект (тот же формат, без массива).

**`POST /api/v1/admin/storage-config/refresh`** — refresh **всех** кластеров; ответ:

```json
{
  "allReady": true,
  "clusters": [ { "name": "A", "ready": true, "version": 43, … }, { … } ]
}
```

HTTP `200`, если `allReady=true`; `500` — если хотя бы один кластер не перезагрузился (в теле остаётся массив с `lastError`).

**`POST /api/v1/admin/storage-config/{cluster}/refresh`** — refresh одного:

```json
{ "cluster": "A", "refreshed": true, "status": { "name": "A", … } }
```

HTTP `200` при `refreshed=true`; `500` при неудаче; `404` если имя кластера не найдено.

### 4.4. Примеры curl

```bash
# research
curl -i -X POST http://localhost:8080/api/v1/research \
     -H "Content-Type: application/json" -d '{"an":"ACC12345"}'

# состояние всех кластеров
curl -s http://localhost:8080/api/v1/admin/storage-config | jq

# состояние кластера A
curl -s http://localhost:8080/api/v1/admin/storage-config/A | jq

# refresh всех
curl -s -X POST http://localhost:8080/api/v1/admin/storage-config/refresh | jq

# refresh только B
curl -s -X POST http://localhost:8080/api/v1/admin/storage-config/B/refresh | jq
```

---

## 5. Модель данных и SQL

### 5.1. Используемые таблицы

| Таблица | Назначение | Кто использует |
|---|---|---|
| `study`, `series`, `instance`, `rel_instance_location`, `location` | research-данные | `StudyRepository` (для каждого кластера) |
| `dcm4che_config` | JSON-конфиг storage systems | **только `StorageConfigService` (по кластеру)** |

**Формат хранения.** DICOM-инстансы упакованы в **zip-архивы**. `location.storage_path` — путь к архиву; `instance.sop_iuid` — идентификатор файла внутри. В ответе `fileName` = `sop_iuid`, `path` = `ss_path + '/' + storage_path.replace('/', '\\')`.

### 5.2. Research SQL — `sql/find-studies.sql` (один на оба диалекта, одинаков для всех кластеров)

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

**`sql/storage-config/fingerprint.postgres.sql`:**

```sql
SELECT md5(content::text) FROM dcm4che_config WHERE path = ?
```

> `?` — позиционный bind-параметр `JdbcTemplate`. Значение `configPath` уходит отдельно от текста SQL. Инъекция невозможна. `path` — это **ключ конфига** (`/dicomConfigurationRoot/…`), не `location.storage_path`.

### 5.4. Различия диалектов

| Аспект | Oracle 12c | PostgreSQL 12+ |
|---|---|---|
| research SQL | идентичен | идентичен |
| load storage config | идентичен | идентичен |
| fingerprint | `RAWTOHEX(STANDARD_HASH(…))` | `md5(::text)` |
| CLOB | `rs.getString(1)` | `TEXT` |
| `''` = NULL | да | нет |

> Диалект задаётся **per cluster**: `app.clusters[].dialect`. Кластеры могут быть смешанными (A — Oracle, B — PostgreSQL).

### 5.5. Логика merge и dedupe (multi-cluster)

1. **Fan-out.** Для каждого кластера `StudyRepository.findByAn` / `findBySuid`. `ClusterResult {cluster, ok, byAn, bySuid, cause}`.
2. **Merge:**
   - только AN → `LinkedHashMap<fileName, StudyRow>` по всем `byAn`, `putIfAbsent` — первый кластер в конфиге «побеждает»;
   - только SUID → аналогично по `bySuid`;
   - оба → `unionAn` (по `fileName`) и `unionSuid` (по `fileName`), итог = `unionAn ∩ unionSuid`, значение из `unionAn`.
3. **buildStudies:**
   - группировка по `study_iuid` → `series_iuid`;
   - внутри series — dedupe по `fileName` (`LinkedHashSet`), добавляем только первый;
   - `path` вычисляется через `StorageConfigService.ssPath(cluster, groupId, storageId)` **для того кластера, чья строка «победила»**;
   - study/series без инстансов удаляются.

**Ключ дедупликации — `fileName` (= `sop_iuid`).** DICOM UID глобально уникален, поэтому один `sop_iuid` в двух кластерах — это **один и тот же** инстанс, просто с разными путями. Возвращаем один; путь — из первого кластера в `app.clusters[]`. Это детерминированно и объяснимо поддержке.

**Union study/series:** если один `study_iuid` есть в A и B — в ответе **один** `study`, а `series`/`instances` — объединение. Это даёт «сумму по кластерам» без дублей.

---

## 6. Компоненты системы

### 6.1. Пакеты

```
com.example.olderisindex
├── OldErisIndexApplication   (@SpringBootApplication @EnableCaching @EnableScheduling
│                              @EnableConfigurationProperties(AppProperties.class))
├── config
│   ├── AppProperties         (app.clusters[], app.research.*, policy)
│   ├── ClusterRegistry       (Map<name, ClusterContext>: dataSource + jdbc + namedJdbc)
│   └── ExecutorConfig        (dbExecutor)
├── api
│   ├── ResearchController / ResearchRequest / ResearchResponse / ResearchService
│   ├── admin/StorageConfigController
│   └── error/
│       ├── ErrorHandler
│       ├── StudyNotFoundException
│       ├── ResearchDatabaseException
│       └── ResearchTimeoutException
├── data
│   ├── StudyRepository   (stateless; принимает ClusterContext)
│   └── StudyRow          (cluster + поля)
└── storage
    ├── StorageConfigService   (Map<cluster, ClusterState>)
    ├── StorageKey
    └── StorageConfigException
```

### 6.2. Ответственности и уровни логирования

| Класс | Ответственность | INFO | WARN | ERROR | DEBUG |
|---|---|---|---|---|---|
| `ResearchController` | HTTP, `@Valid` | — | — | — | POST-запрос |
| `ResearchService` | Fan-out, merge, dedupe, buildStudies | **research miss / OK**, **research NOT_FOUND** | **research PARTIAL**, **research NOT_FOUND (partial)** | **research FAILED (all clusters)** | cacheKey, per-cluster sizes, drop reasons |
| `StudyRepository` | SQL → `List<StudyRow>` per cluster | — | — | — | SQL name, cluster, bind, size, duration |
| `StorageConfigService` | Кэш per cluster, fingerprint, full reload | **loaded (per cluster)**, **refreshed (per cluster)**, **fingerprint changed** | — | **startup failed (per cluster)**, **reload failed (per cluster)**, **fingerprint check failed (per cluster)** | fingerprint unchanged, parse counts |
| `ErrorHandler` | Mapping → HTTP | — | 504 | 502 | 404 / 400 |
| `StorageConfigController` | Admin | — | — | — | — |

**Инвариант:** 502/504 логируются **только** в `ResearchService` (all-failed) или `ErrorHandler`. `ResearchService` для частичных сбоев пишет **WARN** — это единственная строка «алертного» уровня при `200 partial`.

### 6.3. Кэш-ключ studies

```java
key = "(#req.an != null ? #req.an : '') + '|' + (#req.suid != null ? #req.suid : '')"
```

| `an` | `suid` | ключ |
|---|---|---|
| `ACC1` | `null` | `ACC1|` |
| `null` | `1.2.3` | `|1.2.3` |
| `ACC1` | `1.2.3` | `ACC1|1.2.3` |

> Кэш общий для всех кластеров: ключ — запрос, значение — уже объединённый ответ. Если кластер «отвалился» после прогрева кэша, `@Cacheable` вернёт закэшированный ответ без обращения к БД. Это ожидаемое поведение; при необходимости — `DELETE /api/v1/admin/studies/cache`.

### 6.4. Конфигурация (`application.yml`)

См. §10 v2.0 в коде — секция `app.clusters[]` с двумя кластерами, `app.clusters-startup-policy`, `app.research.*`.

**Про `clusters-startup-policy`:**

| Значение | Поведение при старте |
|---|---|
| `require-all` | Приложение **не стартует**, если хоть один кластер не загрузил storage config |
| `require-any` (по умолчанию) | Стартует, если загрузился **хотя бы один**; для упавших — `ERROR` в лог + `ready=false`; research отдаёт `502`, если все кластеры `ready=false` |
| `best-effort` | Стартует всегда; для упавших — `WARN`/`ERROR`; research отдаёт `502` если оба недоступны |

**Про fingerprint на кластер:**

| Значение `fingerprint-interval` | Когда подходит |
|---|---|
| `1h` (по умолчанию) | Типичный кейс |
| `30m` | Быстрее узнавать об изменениях |
| `24h` | Минимальная нагрузка |
| `0` или `fingerprint-enabled: false` | Fingerprint отключён; обновление только `POST /refresh` или перезапуск |

### 6.5. Класс-точка входа

```java
@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class OldErisIndexApplication { … }
```

---

## 7. Безопасность

### 7.1. SQL-инъекции

- research: `:an`/`:suid` — bind-параметры; SQL-файл фиксированный.
- storage config: `?` — bind во всех 3 SQL-файлах.
- Валидация `@Size(max=64)`.

### 7.2. Пароли БД

**У каждого кластера — свой**. Из `app.clusters[].username` / `password`, реальные значения — из env / K8s Secret / Vault / Wallet. Пример:

```yaml
clusters:
  - name: A
    username: ${DB_USER_A}
    password: ${DB_PASSWORD_A}
  - name: B
    username: ${DB_USER_B}
    password: ${DB_PASSWORD_B}
```

**K8s Secret:**

```yaml
env:
  - name: DB_USER_A
    valueFrom: { secretKeyRef: { name: agfa-a-creds, key: username } }
  - name: DB_PASSWORD_A
    valueFrom: { secretKeyRef: { name: agfa-a-creds, key: password } }
  - name: DB_USER_B
    valueFrom: { secretKeyRef: { name: agfa-b-creds, key: username } }
  - name: DB_PASSWORD_B
    valueFrom: { secretKeyRef: { name: agfa-b-creds, key: password } }
```

**Oracle Wallet:** для каждого кластера — свой credential `AGFA_A`, `AGFA_B`.

**Vault:** путь `secret/old-eris-index` с ключами `db_user_a`, `db_password_a`, `db_user_b`, `db_password_b`.

### 7.3. Сетевая безопасность

- Сервис слушает `:8080` во внутренней сети.
- TLS-терминация на ingress/API Gateway.
- **`/api/v1/admin/**`** закрыт от внешнего ingress; вызывается только из ops/monitoring.
- **Сетевой доступ к обоим кластерам** должен быть разрешён из pod'ов сервиса (NetworkPolicy).

### 7.4. PII в логах

- AN/SUID в `research miss`/`research OK`/`PARTIAL` и 502/504 — как есть.
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
| Storage config: startup load (per cluster) | INFO | N (по кластерам) | `StorageConfigService` |
| Storage config: fingerprint changed (per cluster) | INFO | 1 | `StorageConfigService` |
| Storage config: full reload success (per cluster) | INFO | 1 | `StorageConfigService` |
| Storage config: full reload failed (per cluster) | ERROR | 1 + stack | `StorageConfigService` |
| Cache-miss | INFO | 1 (`research miss: ...`) | `ResearchService` |
| `200 OK`, все кластеры живы | INFO | 1 (`research OK: ..., clusters_ok=[A,B], clusters_failed=[]`) | `ResearchService` |
| **`200 OK`, один кластер упал** | **WARN** + INFO | **2**: `research PARTIAL: ...` (WARN) + `research OK: ... clusters_failed=[A]` (INFO) | `ResearchService` |
| `404 NOT_FOUND`, все живы | INFO | 1 (`research NOT_FOUND: ..., clusters_ok=[A,B]`) | `ResearchService` |
| **`404 NOT_FOUND`, один упал** | **WARN** | **1**: `research NOT_FOUND (partial): ... clusters_failed=[A]. Исследование может существовать в недоступном кластере.` | `ResearchService` |
| `502` (все упали, не таймаут) | ERROR | 1 + причина | `ResearchService` |
| `504` (все — таймауты) | WARN | 1 | `ErrorHandler` |

**НЕ пишется:**

- ❌ fingerprint unchanged (только DEBUG);
- ❌ кэш-хит (метрика);
- ❌ `404`/`400` **все кластеры живы** (метрики);
- ❌ ss_path lookup (O(1)).

**Дублей нет:** 502/504 логируются только в одном месте.

**Особое правило:** при `200 partial` **WARN обязателен** — это единственный способ для support/дежурного узнать, что ответ может быть неполным без просмотра metrics/Grafana.

### 8.3. Debug-лог при `DEBUG`

Дополнительно к v1.0:

- `ResearchService`:
  - `callAllClusters(): cluster=A ok, byAn=3, bySuid=3`;
  - `callAllClusters(): cluster=B failed: <cause>`;
  - `merge(): AN-only, union size=N, by cluster=[A:N, B:M]`;
  - `merge(): AN+SUID intersection size=K`;
  - `buildStudies(): rows=N, studies=M, series=K, instances=L, dropped=D`.
- `StorageConfigService`:
  - `fingerprintCheck(): cluster=A, current=ab12…, db=ab12… → unchanged`;
  - `resolve(): cluster=A, (G1, ST1) → /nfs`.

### 8.4. Включение/выключение debug

Без изменений от v1.0: yml / env / CLI / Actuator.

### 8.5. Overhead при `INFO`

Без изменений: все `log.debug` под `isDebugEnabled()`; при `INFO` — `LevelFilter` не даёт создаться debug-файлу. INFO-логов 1–2 на запрос (2 при partial), без stack trace.

### 8.6. Пример прод-лога при `INFO` (все кластеры живы)

```
2025-01-01T12:00:00.100 INFO  [main]       c.e.o.storage.StorageConfigService - Storage config loaded: cluster=A, entries=12, version=1, fingerprint=a3f5…e8c1
2025-01-01T12:00:00.150 INFO  [main]       c.e.o.storage.StorageConfigService - Storage config loaded: cluster=B, entries=8,  version=1, fingerprint=b7c1…f0d2
2025-01-01T12:00:00.400 INFO  [main]       c.e.olderisindex.OldErisIndexApplication - Started OldErisIndexApplication in 2.7 seconds
2025-01-01T13:06:00.123 INFO  [http-nio-…] c.e.o.api.ResearchService - research miss: AN=ACC12345
2025-01-01T13:06:00.145 INFO  [db-2]       c.e.o.api.ResearchService - research OK: AN=ACC12345, studies=1, clusters_ok=[A, B], clusters_failed=[], 22 мс
```

### 8.7. Пример прод-лога при `INFO` (один кластер упал)

```
2025-01-01T13:07:00.123 INFO  [http-nio-…] c.e.o.api.ResearchService - research miss: AN=ACC99999
2025-01-01T13:07:00.145 WARN  [db-1]       c.e.o.api.ResearchService - research PARTIAL: AN=ACC99999, clusters_ok=[B], clusters_failed=[A], causes=[TimeoutException: null]. Результат может быть неполным: в недоступном кластере могли быть дополнительные серии/инстансы для этого исследования.
2025-01-01T13:07:00.146 INFO  [db-1]       c.e.o.api.ResearchService - research OK: AN=ACC99999, studies=1, clusters_ok=[B], clusters_failed=[A], 23 мс
```

### 8.8. Пример debug-трейса

```
2025-01-01T13:06:00.123 DEBUG [http-nio-…] c.e.o.api.ResearchController - POST /api/v1/research: an='ACC12345', suid=null
2025-01-01T13:06:00.124 DEBUG [http-nio-…] c.e.o.api.ResearchService    - process(): AN='ACC12345', SUID='null', cacheKey='ACC12345|'
2025-01-01T13:06:00.125 DEBUG [db-1]       c.e.o.data.StudyRepository  - cluster=A findByAn: bind an='ACC12345'
2025-01-01T13:06:00.126 DEBUG [db-2]       c.e.o.data.StudyRepository  - cluster=B findByAn: bind an='ACC12345'
2025-01-01T13:06:00.130 DEBUG [db-1]       c.e.o.data.StudyRepository  - cluster=A findByAn → 3 rows за 5 мс
2025-01-01T13:06:00.132 DEBUG [db-2]       c.e.o.data.StudyRepository  - cluster=B findByAn → 2 rows за 7 мс
2025-01-01T13:06:00.133 DEBUG [db-1]       c.e.o.api.ResearchService    - callAllClusters(): cluster=A ok, byAn=3
2025-01-01T13:06:00.134 DEBUG [db-1]       c.e.o.api.ResearchService    - callAllClusters(): cluster=B ok, byAn=2
2025-01-01T13:06:00.135 DEBUG [db-1]       c.e.o.api.ResearchService    - merge(): AN-only, union size=4 (dedup 1), by cluster=[A:3, B:2]
2025-01-01T13:06:00.136 DEBUG [db-1]       c.e.o.storage.StorageConfigService - resolve(): cluster=A, (G1, ST1) → /nfs
2025-01-01T13:06:00.137 DEBUG [db-1]       c.e.o.api.ResearchService    - buildStudies(): rows=4, studies=1, series=1, instances=4, dropped=0
2025-01-01T13:06:00.145 INFO  [db-1]       c.e.o.api.ResearchService    - research OK: AN=ACC12345, studies=1, clusters_ok=[A, B], clusters_failed=[], 22 мс
```

---

## 9. Развёртывание

### 9.1. Сборка

```bash
mvn -q clean package -DskipTests
ls -lh target/old-eris-index-1.0.0.jar
```

### 9.2. Dockerfile

Без изменений от v1.0.

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
            - name: DB_USER_A
              valueFrom: { secretKeyRef: { name: agfa-a-creds, key: username } }
            - name: DB_PASSWORD_A
              valueFrom: { secretKeyRef: { name: agfa-a-creds, key: password } }
            - name: DB_USER_B
              valueFrom: { secretKeyRef: { name: agfa-b-creds, key: username } }
            - name: DB_PASSWORD_B
              valueFrom: { secretKeyRef: { name: agfa-b-creds, key: password } }
            - name: LOG_DIR
              value: /var/log/olderis-index
            - name: LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX
              value: INFO
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
            requests: { cpu: 500m, memory: 768Mi }
            limits:   { cpu: 2000m, memory: 1Gi }
      volumes:
        - name: logs
          emptyDir: {}
```

> Ресурсы увеличены vs v1.0: два Hikari-пула по 10 коннектов, больше параллельных запросов.

### 9.4. Проверка после деплоя

```bash
kubectl port-forward svc/old-eris-index 8080:8080

# research
curl -i -X POST http://localhost:8080/api/v1/research \
     -H "Content-Type: application/json" -d '{"an":"ACC12345"}'

# оба кластера видны
curl -s http://localhost:8080/api/v1/admin/storage-config | jq

# refresh обоих кластеров
curl -s -X POST http://localhost:8080/api/v1/admin/storage-config/refresh | jq
```

---

## 10. Тестирование

### 10.1. Пирамида

```
         ╱╲            E2E (MockMvc)
        ╱  ╲
       ╱────╲          Integration (Testcontainers, multi-cluster)
      ╱      ╲
     ╱────────╲        Unit (Service, Repository, StorageConfig, DTO, Logging)
    ╱──────────╲
```

### 10.2. Unit-тесты (ключевые)

#### 10.2.1. `ResearchRequestTest`

Как в v1.0 — `onlyAn`, `onlySuid`, `both`, `neither`, `tooLong*`.

#### 10.2.2. `ResearchServiceTest` — главное: multi-cluster merge/dedupe

```java
@ExtendWith(MockitoExtension.class)
class ResearchServiceTest {

    @Mock ClusterRegistry registry;
    @Mock StudyRepository repo;
    @Mock StorageConfigService storageConfig;
    @Mock ThreadPoolTaskExecutor executor;
    ResearchService service;

    ClusterContext ctxA, ctxB;

    @BeforeEach void setUp() {
        ctxA = mockCtx("A");
        ctxB = mockCtx("B");
        when(registry.all()).thenReturn(List.of(ctxA, ctxB));
        when(registry.names()).thenReturn(List.of("A", "B"));

        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            CompletableFuture<Void> f = new CompletableFuture<>();
            try { ((Runnable) inv.getArgument(0)).run(); f.complete(null); }
            catch (Exception e) { f.completeExceptionally(e); }
            return f;
        });

        service = new ResearchService(registry, repo, storageConfig, executor, 20);
        when(storageConfig.ssPath(any(), any(), any())).thenReturn(Optional.of("/nfs"));
    }

    @Test void bothClusters_unionAn_returnsMerged() {
        when(repo.findByAn(ctxA, "ACC1")).thenReturn(List.of(
                row("A", "ST1", "SER1", "I1", "a1.zip")));
        when(repo.findByAn(ctxB, "ACC1")).thenReturn(List.of(
                row("B", "ST1", "SER1", "I1", "b1.zip"),   // дубль sop_iuid
                row("B", "ST1", "SER1", "I2", "b2.zip"))); // новый

        var req = new ResearchRequest(); req.setAn("ACC1");
        var resp = service.process(req);

        var study = (Map<String, Object>) resp.getStudies().get(0);
        var series = (List<Map<String, Object>>) study.get("series");
        var instances = (List<Map<String, Object>>) series.get(0).get("instances");
        assertEquals(2, instances.size());              // I1 + I2
        assertEquals("I1", instances.get(0).get("fileName"));
        // I1 из A (первый в конфиге) → path "A/nfs/a1.zip"
        assertEquals("/nfs/a1.zip", instances.get(0).get("path"));
    }

    @Test void oneClusterFailed_stillReturnsData_fromLive() {
        when(repo.findByAn(ctxA, "ACC1")).thenThrow(new RuntimeException("A down"));
        when(repo.findByAn(ctxB, "ACC1")).thenReturn(List.of(
                row("B", "ST1", "SER1", "I1", "b1.zip")));

        var req = new ResearchRequest(); req.setAn("ACC1");
        var resp = service.process(req);   // 200 OK
        assertEquals("OK", resp.getStatus());

        // проверить, что в лог записан WARN research PARTIAL (ListAppender)
    }

    @Test void oneClusterFailed_bothEmpty_404() {
        when(repo.findByAn(ctxA, "ACC1")).thenThrow(new RuntimeException("A down"));
        when(repo.findByAn(ctxB, "ACC1")).thenReturn(List.of());

        var req = new ResearchRequest(); req.setAn("ACC1");
        var ex = assertThrows(StudyNotFoundException.class, () -> service.process(req));
        assertTrue(ex.getMessage().contains("ACC1"));
    }

    @Test void bothClustersFailed_502() {
        when(repo.findByAn(ctxA, "ACC1")).thenThrow(new RuntimeException("A down"));
        when(repo.findByAn(ctxB, "ACC1")).thenThrow(new RuntimeException("B down"));

        var req = new ResearchRequest(); req.setAn("ACC1");
        assertThrows(ResearchDatabaseException.class, () -> service.process(req));
    }

    @Test void bothClustersTimeout_504() {
        when(repo.findByAn(ctxA, "ACC1")).thenThrow(new TimeoutException("A"));
        when(repo.findByAn(ctxB, "ACC1")).thenThrow(new TimeoutException("B"));

        var req = new ResearchRequest(); req.setAn("ACC1");
        assertThrows(ResearchTimeoutException.class, () -> service.process(req));
    }

    @Test void anPlusSuid_intersectionByFileName() {
        when(repo.findByAn(ctxA, "ACC1")).thenReturn(List.of(
                row("A", "ST1", "SER1", "I1", "a1.zip"),
                row("A", "ST1", "SER1", "I2", "a2.zip")));
        when(repo.findBySuid(ctxA, "1.2.3")).thenReturn(List.of(
                row("A", "ST1", "SER1", "I1", "a1.zip")));
        when(repo.findByAn(ctxB, "ACC1")).thenReturn(List.of());
        when(repo.findBySuid(ctxB, "1.2.3")).thenReturn(List.of());

        var req = new ResearchRequest(); req.setAn("ACC1"); req.setSuid("1.2.3");
        var resp = service.process(req);

        var study = (Map<String, Object>) resp.getStudies().get(0);
        var series = (List<Map<String, Object>>) study.get("series");
        var instances = (List<Map<String, Object>>) series.get(0).get("instances");
        assertEquals(1, instances.size());
        assertEquals("I1", instances.get(0).get("fileName"));
    }

    private static ClusterContext mockCtx(String name) { … }
    private static StudyRow row(String c, String study, String series, String file, String storagePath) {
        return new StudyRow(c, study, series, "CT", file, "G1", "ST1", storagePath);
    }
}
```

#### 10.2.3. `StorageConfigServiceTest` — per cluster

```java
class StorageConfigServiceTest {
    @Test void init_loadsAllClusters() { /* A и B loaded; state per cluster ready */ }
    @Test void init_requireAny_startsWithOneReady() { /* A ok, B fail, policy=require-any → no exception */ }
    @Test void init_requireAll_throwsIfOneFails() { /* A ok, B fail, policy=require-all → StorageConfigException */ }
    @Test void fingerprintCheck_oneClusterChanged_reloadsOnlyThat() { /* A unchanged, B changed → reload only B */ }
    @Test void refreshOne_failed_keepsOldVersionOfThatCluster() { }
    @Test void refreshAll_allOk_versionsIncrementBoth() { }
}
```

#### 10.2.4. `StudyRepositoryTest`

Как в v1.0, но с проверкой, что `findByAn(ctx, an)` использует `ctx.namedJdbc()` и подставляет `:an`; что SQL не содержит `dcm4che_config`/`JSON_TABLE`; и что `StudyRow.cluster()` = `ctx.name()`.

#### 10.2.5. `ErrorHandlerTest`

Как в v1.0: ровно один WARN/ERROR на 504/502; 404/400 без INFO.

### 10.3. Интеграционные (Testcontainers, multi-cluster)

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test-2pg")
class TwoClustersIntegrationTest {

    @Container static PostgreSQLContainer<?> pgA = new PostgreSQLContainer<>("postgres:15")
            .withInitScript("testdata/pg-a-init.sql");
    @Container static PostgreSQLContainer<?> pgB = new PostgreSQLContainer<>("postgres:15")
            .withInitScript("testdata/pg-b-init.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.clusters[0].name", () -> "A");
        r.add("app.clusters[0].dialect", () -> "postgres");
        r.add("app.clusters[0].jdbc-url", pgA::getJdbcUrl);
        r.add("app.clusters[0].username", pgA::getUsername);
        r.add("app.clusters[0].password", pgA::getPassword);
        r.add("app.clusters[0].driver-class-name", () -> "org.postgresql.Driver");
        r.add("app.clusters[0].max-pool-size", () -> 5);
        r.add("app.clusters[0].storage-config.path", () -> "/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc");
        r.add("app.clusters[0].storage-config.fingerprint-interval", () -> "PT1H");
        r.add("app.clusters[0].storage-config.fingerprint-enabled", () -> true);
        r.add("app.clusters[0].storage-config.initial-load-fail-fast", () -> true);

        r.add("app.clusters[1].name", () -> "B");
        r.add("app.clusters[1].dialect", () -> "postgres");
        r.add("app.clusters[1].jdbc-url", pgB::getJdbcUrl);
        r.add("app.clusters[1].username", pgB::getUsername);
        r.add("app.clusters[1].password", pgB::getPassword);
        r.add("app.clusters[1].driver-class-name", () -> "org.postgresql.Driver");
        r.add("app.clusters[1].max-pool-size", () -> 5);
        r.add("app.clusters[1].storage-config.path", () -> "/dicomConfigurationRoot/dicomDevicesRoot/dcm4chee-arc");
        r.add("app.clusters[1].storage-config.fingerprint-interval", () -> "PT1H");
        r.add("app.clusters[1].storage-config.fingerprint-enabled", () -> true);
        r.add("app.clusters[1].storage-config.initial-load-fail-fast", () -> true);
    }

    @Autowired ResearchService service;
    @Autowired StorageConfigService storageConfig;

    @Test void bothClustersLoadedOnStartup() {
        assertTrue(storageConfig.isReady("A"));
        assertTrue(storageConfig.isReady("B"));
    }

    @Test void findsByAn_unionAcrossClusters() {
        // ACC-UNION: есть в A (I1, I2), есть в B (I3, + dup I1)
        // Ожидание: 3 инстанса (I1, I2, I3), path I1 — из A
        var req = new ResearchRequest(); req.setAn("ACC-UNION");
        var resp = service.process(req);
        // …asserts
    }

    @Test void findsByAn_onlyInB() {
        var req = new ResearchRequest(); req.setAn("ACC-B-ONLY");
        var resp = service.process(req);
        // …asserts: 200, данные из B
    }

    @Test void anPlusSuid_intersectionAcrossClusters() { }

    @Test void clustersStartupPolicy_requireAny() { }
}
```

`pg-a-init.sql` / `pg-b-init.sql` — DDL + данные + `dcm4che_config` (по одной строке с разными путями к zip).

### 10.4. E2E (`MockMvc`)

Как в v1.0 + новые admin-эндпоинты:

```java
@SpringBootTest @AutoConfigureMockMvc
class AdminE2ETest {
    @Test void getAllClusters_returnsArray() throws Exception { /* GET /api/v1/admin/storage-config → массив из 2 */ }
    @Test void getOneCluster_returnsObject() throws Exception { }
    @Test void refreshAll_returns200_whenAllReady() throws Exception { }
    @Test void refreshOne_returns404_ifUnknownCluster() throws Exception { }
}
```

### 10.5. Тестирование логирования

Новое (v2.0) — **WARN при partial обязателен**:

```java
@Test void oneClusterFailed_writesWarnPartial() {
    // ListAppender на ResearchService
    when(repo.findByAn(ctxA, "ACC1")).thenThrow(new RuntimeException("A down"));
    when(repo.findByAn(ctxB, "ACC1")).thenReturn(List.of(row("B", "ST1", "SER1", "I1", "b1.zip")));

    var req = new ResearchRequest(); req.setAn("ACC1");
    service.process(req);

    var warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN).toList();
    assertEquals(1, warns.size());
    String msg = warns.get(0).getFormattedMessage();
    assertTrue(msg.startsWith("research PARTIAL"));
    assertTrue(msg.contains("clusters_failed=[A]"));
    assertTrue(msg.contains("Результат может быть неполным"));
}

@Test void oneClusterFailed_bothEmpty_writesWarnNotFound() {
    // …
    assertTrue(msg.contains("Исследование может существовать в недоступном кластере"));
}

@Test void bothClustersOk_noWarn() {
    // … 0 WARN
}
```

### 10.6. Матрица тестов

| Тип | Что проверяет | CI |
|---|---|---|
| Unit | `StorageConfigService`: per cluster init/fingerprint/reload/require-any/require-all | ✅ PR |
| Unit | `ResearchService`: merge union/dedupe, partial, all-failed, timeout, AN+SUID intersection | ✅ PR |
| Unit | `StudyRepository`: SQL без `dcm4che_config`/`JSON_TABLE`, `cluster` в `StudyRow` | ✅ PR |
| Unit | `ErrorHandler`: ровно один WARN/ERROR на 504/502 | ✅ PR |
| Unit | Логи: `research PARTIAL` (1 WARN), `research OK`, `research NOT_FOUND`, `research FAILED` | ✅ PR |
| Integration | 2 × Testcontainers PG, union/dedupe/partial/intersection | ✅ PR |
| Integration | 1 × Testcontainers Oracle, nightly | ⚠️ nightly |
| E2E | research 200/400/404/502/504; admin per cluster + all | ✅ PR |
| Integration | `olderis-index-debug.log` создаётся только при DEBUG | ✅ PR |
| Load | RPS, p95 при INFO; fan-out не увеличивает latency пропорционально числу кластеров | ⚠️ еженедельно |

### 10.7. Ручной чек-лист (smoke)

- [ ] При старте — две строки `Storage config loaded: cluster=A, …` и `cluster=B, …`.
- [ ] `GET /api/v1/admin/storage-config` → массив из 2 объектов с `ready=true`.
- [ ] `POST /api/v1/admin/storage-config/refresh` → 200, `allReady=true`, версии обоих выросли.
- [ ] `{"an":"<real_in_A>"}` → 200; в логе `research OK: …, clusters_ok=[A, B]`.
- [ ] `{"an":"<real_in_B>"}` → 200; данные из B.
- [ ] `{"an":"<real_in_A_and_B_same_sop>"}` → 200; `path` из A (первый кластер).
- [ ] `{"an":"<real_in_A>","suid":"<real_same>"}` → 200; пересечение.
- [ ] **Симуляция падения B** (например, `iptables -A OUTPUT -d <B> -j DROP` или временная порча `jdbc-url` B): `{"an":"<real_in_A>"}` → **200**, в логе **WARN research PARTIAL**, `clusters_ok=[A]`.
- [ ] **Симуляция падения B**, запрос `{"an":"<real_in_B>"}` → **404**, в логе **WARN research NOT_FOUND (partial): … Исследование может существовать в недоступном кластере**.
- [ ] **Симуляция падения обоих** → **502/504**.
- [ ] Повторный запрос ≤10 мин → hit cache, в прод-логе **ничего**.
- [ ] `logging.level...=INFO` → `olderis-index-debug.log` не создан.
- [ ] `logging.level...=DEBUG` → `olderis-index-debug.log` содержит `callAllClusters()`, `merge()`, `buildStudies()`.
- [ ] Actuator → переключение DEBUG↔INFO без рестарта.
- [ ] §11.7: `refresh-all-pods.sh refresh` — все pod'ы, все кластеры, одинаковые `fingerprint`.
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
| `http_server_requests_total{status="502"}` | ошибки кластеров | rate > X → warn |
| `cache_gets{result="hit"}` | hit rate studies | < 70% → info |
| `hikaricp_connections_active{pool="olderis-A"}` | коннекты A | > 8 из 10 → warn |
| `hikaricp_connections_active{pool="olderis-B"}` | коннекты B | > 8 из 10 → warn |
| `executor_pool_size` | потоки пула | = max 16 > 5 мин → warn |
| `jvm_memory_used_bytes` | память | > 80% heap → warn |
| `logback_events_total{level="debug"}` | debug | > 0 в prod > 15 мин → info |
| `logback_events_total{level="warn"}` | WARN-события (в т.ч. PARTIAL) | рост → info |
| `olderis_storage_config_size{cluster="A"}` / `{cluster="B"}` | записей в кэше | **0 → critical** |
| `olderis_storage_config_version{cluster="A"}` / `{cluster="B"}` | версия кэша | — |
| `olderis_storage_config_ready{cluster="A"}` / `{cluster="B"}` | 0/1 готовность | **0 → critical** |
| `olderis_storage_config_fingerprint_age_seconds{cluster=…}` | возраст проверки | > 2× interval → warn |
| `olderis_storage_config_full_reloads_total{cluster=…}` | reload'ы | — |

### 11.2. Прод-лог при `INFO` — сводка

См. §8.2. **WARN при partial обязателен.** Все 502/504 — ровно один раз.

### 11.3. Admin-эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/api/v1/admin/storage-config` | массив статусов всех кластеров |
| `GET` | `/api/v1/admin/storage-config/{cluster}` | статус одного |
| `POST` | `/api/v1/admin/storage-config/refresh` | refresh **всех** |
| `POST` | `/api/v1/admin/storage-config/{cluster}/refresh` | refresh одного |
| `DELETE` | `/api/v1/admin/studies/cache` | (опционально) очистка кэша studies |

**Ограничение доступа:** `/api/v1/admin/**` закрыт от внешнего ingress; вызывается только из ops/monitoring.

### 11.4. Runbook (общие инциденты)

| Симптом | Причина | Действие |
|---|---|---|
| 502/504 массово | обе БД недоступны | `hikaricp_connections_*{pool="olderis-A/B"}`; Alert DBA; проверить сеть до обоих кластеров |
| **В логе WARN `research PARTIAL`** | **один кластер недоступен** | **проверить доступность этого кластера; если долго — эскалировать DBA кластера; эндпоинт отдаёт данные из живого** |
| **WARN `research NOT_FOUND (partial)`** | **один кластер недоступен, живой пуст** | **проверить доступность упавшего; исследование может быть только там** |
| 404 на пару `an+suid`, которая должна пересекаться | `(fileName)` не совпадает между кластерами | debug на 5 мин; смотреть `merge()`, `buildStudies()` |
| research отдаёт устаревший `path` | fingerprint не сработал / reload падал | §11.6 / §11.7 |
| `Storage config refresh failed` (per cluster) | БД недоступна на момент reload | проверить БД; кэш кластера сохранён; форсировать (§11.7) |
| `olderis_storage_config_fingerprint_age_seconds` > 2× interval | scheduler не работает | форсировать `POST /refresh` (§11.7) |
| Приложение не стартует (`require-all`) | один кластер не загрузил config | проверить `dcm4che_config` этого кластера; временно можно `require-any`/`best-effort` |
| Кэш studies не работает | Bean self-invocation | `@Cacheable` через прокси |
| OOM | большой JSON в кэше studies | снизить `maximumSize` / weighted cache |
| debug-файл разросся | забыли выключить | `POST /actuator/loggers/...` |
| Двойные строки 502/504 | баг | обновить до актуальной версии |

### 11.5. Политика debug в prod

Без изменений от v1.0: `INFO` по умолчанию; `DEBUG` — по инциденту на конкретном pod'е, возврат через 15 минут по алерту.

### 11.6. Штатное изменение `dcm4che_config` (плановое)

**По умолчанию — ничего делать не нужно.** Изменение в кластере A подхватится автоматически на **каждой** pod'е за ≤ `fingerprint-interval`. Кластер B не затронется — у него свой fingerprint.

Лог:

```
INFO Storage config: cluster=A, fingerprint changed (a3f5… → d7b2…), reloading
INFO Storage config refreshed: cluster=A, entries=13, version=2, fingerprint=d7b2…f4a8
```

Проверить, что все pod'ы подхватили (по каждому кластеру):

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s localhost:8080/api/v1/admin/storage-config \
    | jq -c '[.[] | {name, ready, version, fingerprint, size}]'
done
```

**Если ждать час нельзя** — §11.7.

---

### 11.7. Техподдержка: принудительный refresh на всех pod'ах

> **Раздел для техподдержки (multi-cluster).** Использовать при:
> - срочном изменении `dcm4che_config` в **одном или нескольких** кластерах и невозможности ждать `fingerprint-interval`;
> - подозрении, что одна или несколько pod'ов держат устаревший кэш **по любому кластеру** (`fingerprint` отличается между pod'ами или от БД);
> - после ручного изменения `dcm4che_config` в любом кластере.

**Ключевое:** `POST /api/v1/admin/storage-config/refresh` через Service/Ingress попадёт **на одну** pod'у и обновит **все кластеры** на этой pod'е. Чтобы обновить **все** pod'ы — обратиться к **каждой** напрямую.

#### Шаг 1. Посмотреть состояние всех кластеров на всех pod'ах

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s localhost:8080/api/v1/admin/storage-config \
    | jq -c '[.[] | {name, ready, version, fingerprint, size}]'
done
```

Ожидаемый вывод — одинаковые `fingerprint` для **каждого** кластера на всех pod'ах.

#### Шаг 2. Форсировать refresh всех кластеров на всех pod'ах

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo "=== $p ==="
  kubectl exec "$p" -- curl -s -X POST localhost:8080/api/v1/admin/storage-config/refresh \
    | jq -c '{allReady, clusters: [.clusters[] | {name, ready, version, fingerprint, size, lastError}]}'
done
```

Ожидаемо — `allReady=true` для каждой pod'ы; версии обоих кластеров выросли.

**Если нужно обновить только один кластер** (например, только A):

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s -X POST localhost:8080/api/v1/admin/storage-config/A/refresh \
    | jq -c '{cluster, refreshed, status: {version, fingerprint, size, lastError}}'
done
```

#### Шаг 3. Проверить после

```bash
kubectl get pods -l app=old-eris-index -o name | while read p; do
  echo -n "$p: "
  kubectl exec "$p" -- curl -s localhost:8080/api/v1/admin/storage-config \
    | jq -c '[.[] | {name, ready, version, fingerprint, size}]'
done
```

**Все pod'ы должны показать одинаковый `fingerprint` по каждому кластеру.**

#### Готовый скрипт (k8s)

Сохранить как `scripts/refresh-all-pods.sh` (см. §29 кода v2.0):

```bash
./scripts/refresh-all-pods.sh status
NAMESPACE=prod ./scripts/refresh-all-pods.sh refresh
```

Скрипт показывает статусы **всех кластеров** до/после и вызывает `/refresh` (все кластеры) на **каждой** pod'е.

#### Аналоги для других окружений

**Docker:**

```bash
docker ps --filter "name=old-eris-index" --format "{{.Names}}" | while read c; do
  echo -n "$c: "
  docker exec "$c" curl -s -X POST localhost:8080/api/v1/admin/storage-config/refresh \
    | jq -c '{allReady, clusters: [.clusters[] | {name, ready, version, fingerprint}]}'
done
```

**Bare-metal (SSH):**

```bash
for host in oei-1 oei-2 oei-3; do
  echo -n "$host: "
  ssh "$host" 'curl -s -X POST localhost:8080/api/v1/admin/storage-config/refresh' \
    | jq -c '{allReady, clusters: [.clusters[] | {name, ready, version, fingerprint}]}'
done
```

**Ansible:**

```yaml
- hosts: old_eris_index
  become: false
  tasks:
    - name: Force storage config refresh (all clusters)
      uri:
        url: http://localhost:8080/api/v1/admin/storage-config/refresh
        method: POST
        return_content: true
      register: r
    - debug:
        msg: "{{ inventory_hostname }}: {{ r.json | to_json }}"
```

#### Если одна pod не отвечает или один кластер не обновляется

1. Проверить pod: `kubectl get pods`, `kubectl describe pod`.
2. Проверить процесс: `curl -sv localhost:8080/actuator/health`.
3. Проверить состояние per cluster: `curl -s localhost:8080/api/v1/admin/storage-config | jq`.
4. Если для **одного** кластера `ready=false` / `lastError` не пуст — проверить доступ к `dcm4che_config` **именно этого кластера** (Vault/Secret, сеть).
5. Если `POST /refresh` вернул 200, но `fingerprint` не меняется — проверить, что `dcm4che_config` в БД **действительно** изменилась:
   ```sql
   SELECT RAWTOHEX(STANDARD_HASH(content,'SHA256')) FROM dcm4che_config WHERE path = '...';
   ```
6. Перезапустить pod: `kubectl delete pod <pod-name>`.

#### Что НЕ делать

- ❌ Не вызывать `POST /refresh` через Service — попадёт на одну pod'у.
- ❌ Не перезапускать все pod'ы одновременно — downtime.
- ❌ Не редактировать `dcm4che_config` вручную в prod без согласования с DBA.
- ❌ Не снижать `fingerprint-interval` до секунд.

#### Чек-лист техподдержки

- [ ] `./scripts/refresh-all-pods.sh status` — все pod'ы, все кластеры.
- [ ] Если fingerprint различается по **кластеру X** → `refresh` (все кластеры) или `/X/refresh` по pod'ам.
- [ ] Если после refresh различается → проверить доступ к `dcm4che_config` этого кластера; перезапустить отстающую pod'у.
- [ ] Проверить research на известный AN из этого кластера.
- [ ] Зафиксировать в тикете: cluster, version до/после, fingerprint до/после, время.

---

### 11.8. Мультиподинг и согласованность кэшей

**Каждая pod держит свой кэш `dcm4che_config` по каждому кластеру.** Общего кэша нет.

| Свойство | Значение |
|---|---|
| Память | `Map<StorageKey, String>` × (число кластеров) на pod'у — единицы–десятки KB × N |
| Согласованность в норме | ≤ `fingerprint-interval` **по каждому кластеру независимо** |
| Согласованность при форсировании | 0 после §11.7 |
| Сеть между pod'ами | Не требуется |
| Leader election | Не требуется |
| Single point of failure | Нет |
| Что если pod упала | При старте — full load всех кластеров; кэши пересоздаются |

**Когда стоит пересмотреть (в сторону общего кэша):**

- pod'ов ≥5 и 1h-окно неприемлемо;
- жёсткое требование «все pod'ы видят одинаковый конфиг в одну секунду»;
- кэш storage config вырастет до сотен MB.

Тогда — Redis + pub/sub на инвалидацию (вне scope). Сейчас локальный кэш + fingerprint + §11.7 — оптимум.

---

### 11.9. Мультикластерность: семантика и правила

Это ключевой раздел v2.0. Здесь — формальные правила поведения.

**Термины:**

- **AN** — `accession_no` (может совпадать у нескольких studies).
- **SUID** — `study_iuid` (уникален).
- **sop_iuid** — `instance.sop_iuid` (уникален глобально; в ответе — `fileName`).

**Правила:**

| Ситуация | Поведение | Лог |
|---|---|---|
| AN или SUID найден в **одном** кластере | `200`, данные из этого кластера | INFO OK |
| AN или SUID найден в **обоих** кластерах, `sop_iuid` **разные** | `200`, union инстансов, study/series объединены | INFO OK |
| AN или SUID найден в **обоих** кластерах, `sop_iuid` **совпадает** | `200`, **одна** запись на `sop_iuid`; `path` — из **первого** кластера в `app.clusters[]` | INFO OK |
| AN + SUID | `union(AN по кластерам) ∩ union(SUID по кластерам)` по **`sop_iuid`**; `path` из AN-результата | INFO OK |
| Ни в одном, все живы | `404 NOT_FOUND` | INFO NOT_FOUND |
| Ни в одном, один упал | `404 NOT_FOUND` | **WARN NOT_FOUND (partial)** |
| Один упал, в живом есть | `200` с данными живого | **WARN PARTIAL** + INFO OK |
| Оба упали (не таймаут) | `502` | ERROR |
| Оба упали (все таймауты) | `504` | WARN |

**Ключевые принципы:**

1. **Один `sop_iuid` = один инстанс.** Пути могут различаться (миграция), но физически это один и тот же DICOM-объект. Дублировать его в ответе нельзя.
2. **`study_iuid` / `series_iuid` объединяются.** Если в одном кластере добавилась серия (например, при дообследовании), а в другом её нет — union покажет обе.
3. **Приоритет кластера при дубле `sop_iuid`** — порядок в `app.clusters[]`. Это детерминированно и объяснимо.
4. **`200 partial` — легитимный ответ**, но **всегда** с `WARN` в логе. Клиент получает данные, а дежурный видит сигнал «результат может быть неполным». В тело ответа **не добавляем** `partial`/`clusters` — контракт остаётся простым.
5. **`404` при упавшем кластере — `WARN`.** Нельзя утверждать «не найдено», пока второй кластер недоступен; в логе — явное предупреждение.
6. **Кластеры не «равны» по важности для данных**, но **равны для merge**: нет приоритета «A важнее B» для факта наличия; приоритет только для **выбора одного пути** при дубле.

---

## 12. Миграция Oracle → PostgreSQL

### 12.1. Что нужно сделать

| Шаг | Действие |
|---|---|
| 1 | Перенести данные (все таблицы, включая `dcm4che_config`) — **по каждому кластеру отдельно** |
| 2 | Для каждого кластера: `app.clusters[i].dialect: postgres` |
| 3 | Для каждого кластера: `jdbc-url`, `driver-class-name` |
| 4 | Integration-тесты PG (per cluster) |
| 5 | Smoke на stage |
| 6 | Prod (blue/green, по одному кластеру за раз) |

**SQL менять не нужно:** `find-studies.sql` и `load.sql` идентичны для обоих диалектов; меняется только fingerprint (`STANDARD_HASH` ↔ `md5`) — сервис выбирает файл по `cluster.dialect`.

**Кластеры могут быть смешанными:** A — Oracle, B — PostgreSQL (или наоборот). Сервис это поддерживает.

### 12.2. Что НЕ меняется

Java-код, `ResearchService`, `StorageConfigService`, кэш, executor, API-контракт, логирование, метрики, безопасность, имена таблиц.

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
| **2.0 (текущая)** | Мультикластерность: fan-out, merge, dedupe по `sop_iuid`, union study/series, partial при падении одного кластера, admin per cluster | ✅ |
| 2.1 | Prometheus + Grafana dashboard (per-cluster метрики) | Should |
| 2.2 | Rate limiting (Bucket4j) | Should |
| 2.3 | Аутентификация (JWT через API Gateway) | Must (prod) |
| 2.4 | Общий кэш `dcm4che_config` (Redis) при pod'ов ≥5 | Nice |
| 2.5 | `GET /api/v1/research?an=...&suid=...` | Nice |
| 2.6 | Стриминговый ответ для больших studies | Nice |
| 2.7 | OpenAPI/Swagger | Should |

### 13.2. Известные ограничения

1. Кэш studies не инвалидируется автоматически — TTL 10 мин; при падении кластера после прогрева кэш отдаёт старые объединённые данные (см. §6.3).
2. Ответ держится целиком в памяти — при study >100k instances возможен OOM.
3. Нет аутентификации клиентов и admin-эндпоинтов — внутренняя сеть / Gateway.
4. Каждая pod держит свой кэш `dcm4che_config` по каждому кластеру (§11.8).
5. `fingerprint-enabled: false` / `fingerprint-interval: 0` отключает автоматическое обнаружение для соответствующего кластера.
6. `STANDARD_HASH` / `md5()` — для других СУБД нужен свой fingerprint-файл.
7. Парсинг в Java — при очень крупном JSON (сотни MB) старт может быть медленным.
8. PII в логах (AN/SUID в `research miss`/`OK`/`PARTIAL`/`NOT_FOUND`) — при требовании хэшировать.
9. Точное совпадение по `fileName` — без нормализации. `sop_iuid` — глобально уникальный UID DICOM, нормализация не требуется.
10. `version` — локальный счётчик per cluster per pod; для сравнения между pod'ами использовать `fingerprint`.
11. **WARN `research PARTIAL`** — единственный сигнал «может быть неполно» без раздувания тела ответа. Алерт на рост WARN rate — на усмотрение эксплуатации.
12. **`404` при `clusters_failed != []`** — это **не** «достоверно не найдено», а «в живых нет; возможно, есть в упавшем». Формально HTTP-код тот же; для строгой уверенности клиент должен проверить лог/метрики (или дождаться восстановления кластера).
13. **Приоритет кластера при дубле `sop_iuid`** — порядок в `app.clusters[]`. Меняется только правкой yml и перезапуском.

---

## 14. Приложения

### A. Структура проекта

```
old-eris-index/
├── pom.xml
├── Dockerfile
├── README.md
├── scripts/refresh-all-pods.sh
├── src/main/java/com/example/olderisindex/
│   ├── OldErisIndexApplication.java
│   ├── api/
│   │   ├── ResearchController / ResearchRequest / ResearchResponse / ResearchService
│   │   ├── admin/StorageConfigController
│   │   └── error/…
│   ├── config/
│   │   ├── AppProperties.java
│   │   ├── ClusterRegistry.java
│   │   └── ExecutorConfig.java
│   ├── data/
│   │   ├── StudyRepository.java
│   │   └── StudyRow.java
│   └── storage/
│       ├── StorageConfigService.java
│       ├── StorageKey.java
│       └── StorageConfigException.java
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
├── src/test/resources/testdata/{pg-a-init.sql, pg-b-init.sql}
└── k6/load.js
```

### B. Переменные окружения

| Переменная | Обязательна | Описание |
|---|---|---|
| `DB_USER_A` / `DB_PASSWORD_A` | да | Креды кластера A |
| `DB_USER_B` / `DB_PASSWORD_B` | да | Креды кластера B |
| `LOG_DIR` | нет | `/var/log/olderis-index` |
| `LOGGING_LEVEL_COM_EXAMPLE_OLDERISINDEX` | нет | `INFO` / `DEBUG` |
| `JAVA_OPTS` | нет | `-XX:MaxRAMPercentage=75` |

### C. Контрольный список приёмки (DoD)

- [ ] Unit: `StorageConfigService` per cluster (init/fingerprint/reload/require-any/require-all).
- [ ] Unit: `ResearchService` merge/dedupe/partial/all-failed/timeout/intersection.
- [ ] Unit: `StudyRepository` — SQL без `dcm4che_config`/`JSON_TABLE`; `cluster` в `StudyRow`.
- [ ] Unit: `ErrorHandler` — ровно один WARN/ERROR на 504/502.
- [ ] Unit: **WARN `research PARTIAL` при одном упавшем кластере**.
- [ ] Unit: **WARN `research NOT_FOUND (partial)` при одном упавшем и пустом живом**.
- [ ] Unit: debug при INFO не пишется; `olderis-index-debug.log` не создаётся.
- [ ] Integration: 2 × Testcontainers PG, union/dedupe/partial/intersection.
- [ ] Integration Oracle — nightly.
- [ ] E2E: research 200/400/404/502/504; admin per cluster + all.
- [ ] **Smoke: fingerprint unchanged не делает full SELECT (AWR / pg_stat_statements) — по каждому кластеру.**
- [ ] **Smoke: §11.7 `refresh-all-pods.sh refresh` — все pod'ы, все кластеры, одинаковые fingerprint.**
- [ ] k6: p95 при `INFO` в норме; fan-out не даёт линейного роста latency.
- [ ] Пароли не в git (`git grep DB_PASSWORD`).
- [ ] Runbook обновлён (§11.4, §11.6, §11.7, §11.8, **§11.9**).
- [ ] **§11.7 протестирован на stage минимум на 2 pod'ах × 2 кластера.**
- [ ] Дашборд Grafana с метриками §11.1, включая per-cluster.
- [ ] Alertmanager: `olderis_storage_config_ready{cluster=...} == 0` → critical; `olderis_storage_config_fingerprint_age_seconds > 7200` → warn; 5xx > 1%; debug > 15 мин; рост `logback_events_total{level="warn"}` → info.
- [ ] Admin-эндпоинты закрыты от внешнего ingress.
- [ ] Техподдержка ознакомлена с §11.7 (multi-cluster) и §11.9.
- [ ] Политика debug согласована с on-call.

### D. Глоссарий

| Термин | Расшифровка |
|---|---|
| **OldErisIndex** | Название сервиса |
| **AN** | Accession Number |
| **SUID** | Study Instance UID |
| **sop_iuid / fileName** | Уникальный идентификатор DICOM-инстанса; в ответе — `fileName` |
| **Кластер A / B** | Амбулаторный / стационарный кластер Agfa; каждый со своей БД и `dcm4che_config` |
| **Storage config** | Кэш `Map<(group_id, storage_id) → ss_path>` **по кластеру** |
| **`ss_path`** | Базовый путь storage system кластера |
| **`full_path`** | `ss_path + '/' + storage_path.replace('/', '\\')` |
| **Fingerprint** | SHA-256 (Oracle) / MD5 (PG) от `content` **по кластеру**; дешёвый детект изменений |
| **`fingerprint-interval`** | Период fingerprint-полинга (per cluster) |
| **Full reload** | Полный `SELECT content` + парсинг + атомарный swap для одного кластера |
| **Merge** | Объединение результатов кластеров с dedupe по `fileName` |
| **Union study/series** | Одинаковые `study_iuid`/`series_iuid` из разных кластеров → один объект в ответе |
| **`research PARTIAL`** | WARN-строка при `200`, если был упавший кластер |
| **`research NOT_FOUND (partial)`** | WARN-строка при `404`, если был упавший кластер |
| **§11.7 / §11.8 / §11.9** | Разделы для техподдержки / мультиподинга / семантики кластеров |
| **RPS / TTL** | Requests Per Second / Time To Live |

---

**Конец документа.** При изменении контракта, добавлении endpoint, изменении уровней логирования, политики debug, политики refresh storage config, **правил merge/dedupe (§11.9)** или **процедуры §11.7** — обновляйте разделы 2, 4, 8, 10, 11.2, 11.4, 11.6, 11.7, 11.8, **11.9** и 13 одновременно.