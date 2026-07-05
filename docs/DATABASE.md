# Структура бази даних `whoislitelocal.db`

База даних — SQLite. Файл `whoislitelocal.db` створюється в поточній робочій директорії при першому запуску.

> **Авторитетне джерело схеми:** `initializeDatabase.java`. Файл `src/main/resources/schema.sql` є застарілим допоміжним артефактом і не відображає актуальну структуру (відсутні колонки `lastip` в таблицях `ipv4`/`ipv6`).

---

## Огляд таблиць

| Таблиця | Джерело даних | Призначення |
|---|---|---|
| `asn` | Extended-файли RIR | Реєстрові записи автономних систем |
| `ipv4` | Extended-файли RIR | Делеговані блоки IPv4-адрес |
| `ipv6` | Extended-файли RIR | Делеговані блоки IPv6-адрес |
| `rpsl` | `ripe.db` та аналоги | RPSL-об'єкти (aut-num, route, org тощо) |
| `rpsl_origin` | `ripe.db` та аналоги | Зв'язок маршрутів з AS-джерелом |
| `rpsl_mntby` | `ripe.db` та аналоги | Зв'язок об'єктів з мейнтейнерами |
| `geo` | Geolocation-файл | Геолокація IP-адрес |
| `file_metadata` | Внутрішня | Метадані завантажених файлів |

---

## Таблиця `asn`

Реєстрові записи автономних систем від усіх RIR (RIPE NCC, ARIN, APNIC, LACNIC, AFRINIC).

```sql
CREATE TABLE asn (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    coordinator TEXT    NOT NULL,  -- RIR: ripe, arin, apnic, lacnic, afrinic
    country     TEXT    NOT NULL,  -- Код країни ISO 3166-1 alpha-2
    asn         INTEGER NOT NULL,  -- Номер AS без префіксу "AS"
    date        TEXT    NOT NULL,  -- Дата делегування, формат YYYYMMDD
    identifier  TEXT    NOT NULL,  -- Ідентифікатор запису від RIR
    name        TEXT,              -- Назва AS (з asnames-файлу, може бути NULL)
    UNIQUE(coordinator, asn, identifier)
);
CREATE INDEX idx_asn_asn ON asn (asn);
```

**Примітка:** Колонка `name` заповнюється окремим парсером (`parseAsnames`) після завантаження extended-файлів. До цього моменту вона `NULL`.

---

## Таблиця `ipv4`

Делеговані блоки IPv4-адрес зі статусом `allocated`.

```sql
CREATE TABLE ipv4 (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    coordinator TEXT    NOT NULL,  -- RIR-реєстратор
    country     TEXT    NOT NULL,  -- Код країни ISO 3166-1 alpha-2
    network     TEXT    NOT NULL,  -- CIDR-нотація, напр. 212.90.160.0/19
    firstip     TEXT,              -- Перша IP блоку у вигляді 40-символьного BigInteger (для порівняння діапазонів)
    lastip      TEXT,              -- Остання IP блоку (аналогічно)
    date        TEXT    NOT NULL,  -- Дата делегування, формат YYYYMMDD
    identifier  TEXT    NOT NULL,  -- Ідентифікатор запису від RIR
    UNIQUE(coordinator, network, identifier)
);
CREATE INDEX idx_ipv4_coordinator_identifier ON ipv4 (coordinator, identifier);
CREATE INDEX idx_ipv4_firstip ON ipv4 (firstip);
CREATE INDEX idx_ipv4_lastip  ON ipv4 (lastip);
```

**Про `firstip`/`lastip`:** Зберігаються як рядки завдовжки рівно 40 символів (з ведучими нулями), що є десятковим представленням BigInteger IP-адреси. Така форма дозволяє виконувати лексикографічне порівняння рядків замість числового, зберігаючи коректний порядок.

```
Приклад: 212.90.160.0 → "00000000000000000000000000000000003561011200"
                                                              (40 символів)
```

---

## Таблиця `ipv6`

Ідентична структурі `ipv4`, але для IPv6-блоків.

```sql
CREATE TABLE ipv6 (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    coordinator TEXT    NOT NULL,
    country     TEXT    NOT NULL,
    network     TEXT    NOT NULL,  -- напр. 2a04:42c0::/29
    firstip     TEXT,              -- 40-символьний BigInteger (IPv6 128-бітний)
    lastip      TEXT,
    date        TEXT    NOT NULL,
    identifier  TEXT    NOT NULL,
    UNIQUE(coordinator, network, identifier)
);
CREATE INDEX idx_ipv6_coordinator_identifier ON ipv6 (coordinator, identifier);
CREATE INDEX idx_ipv6_firstip ON ipv6 (firstip);
CREATE INDEX idx_ipv6_lastip  ON ipv6 (lastip);
```

---

## Таблиця `rpsl`

Основне сховище RPSL-об'єктів. Кожен запис — один об'єкт з RPSL-бази у вигляді повного текстового блоку.

```sql
CREATE TABLE rpsl (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    key   TEXT    NOT NULL,              -- Тип об'єкту (один із 7 допустимих)
    value TEXT    NOT NULL COLLATE NOCASE, -- Ідентифікатор об'єкту (регістронезалежно)
    block TEXT    NOT NULL,              -- Повний текст RPSL-об'єкту
    UNIQUE(key, value)
);
CREATE INDEX idx_rpsl_kv ON rpsl (key, value);
```

**Допустимі значення `key`:**

| `key` | Опис |
|---|---|
| `aut-num` | Автономна система (AS) |
| `as-set` | Іменований набір AS |
| `organisation` | Організація-власник ресурсів |
| `mntner` | Мейнтейнер (обліковий запис управління) |
| `role` | Контактна роль (технічна/адміністративна) |
| `route` | IPv4-маршрут з атрибутом `origin` |
| `route6` | IPv6-маршрут з атрибутом `origin` |

**Приклад вмісту поля `block` для `aut-num`:**

```
aut-num:        AS12345
as-name:        EXAMPLE-AS
descr:          Example ISP network
country:        UA
org:            ORG-EXMPL-RIPE
import:         from AS174 accept ANY
export:         to AS174 announce AS12345
mp-import:      afi ipv6.unicast from AS174 accept ANY
mp-export:      afi ipv6.unicast to AS174 announce AS12345
mnt-by:         EXAMPLE-MNT
source:         RIPE
```

**Важливо:** Атрибути `import`, `export`, `mp-import`, `mp-export` не винесені в окремі колонки — вони є частиною текстового `block`. Їх витяг виконується на рівні застосунку шляхом покрядкового розбору.

---

## Таблиця `rpsl_origin`

Денормалізована таблиця зв'язку маршрутів з AS-джерелом. Будується автоматично при парсингу `route`/`route6` об'єктів з атрибута `origin:`.

```sql
CREATE TABLE rpsl_origin (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    origin TEXT    NOT NULL COLLATE NOCASE, -- AS-джерело, напр. AS12345
    route  TEXT    NOT NULL,                -- IP-префікс (IPv4 або IPv6 CIDR)
    UNIQUE(origin, route)
);
```

**Приклад:** Для RPSL-об'єкту `route: 203.0.113.0/24` з атрибутом `origin: AS12345` буде збережено запис `(origin='AS12345', route='203.0.113.0/24')`.

IPv4 і IPv6 маршрути зберігаються в одній таблиці. Для розрізнення: IPv6 містить символ `:` у полі `route`.

---

## Таблиця `rpsl_mntby`

Денормалізована таблиця зв'язку об'єктів з мейнтейнерами. Будується з атрибуту `mnt-by:` для об'єктів типу `aut-num`, `as-set`, `role`.

```sql
CREATE TABLE rpsl_mntby (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    key   TEXT    NOT NULL,                  -- Тип об'єкту-джерела: aut-num, as-set, role
    value TEXT    NOT NULL COLLATE NOCASE,   -- Ідентифікатор об'єкту (напр. AS12345, AS-EXAMPLE)
    mntby TEXT    NOT NULL COLLATE NOCASE,   -- Ідентифікатор мейнтейнера (з атрибуту mnt-by:)
    UNIQUE(mntby, key, value)
);
```

**Приклад:** для `aut-num: AS12345` з атрибутом `mnt-by: EXAMPLE-MNT` буде збережено запис
`(key='aut-num', value='AS12345', mntby='EXAMPLE-MNT')`.

> Ця таблиця й код навколо неї до `2026-07-05` мали неузгодженість: колонки
> в `INSERT`-запиті (`parseRpsl.java`) заповнювались у порядку, що не
> відповідав їхнім назвам (тип об'єкту потрапляв у `mntby`, а мейнтейнер —
> у `value`). Увесь код читання був написаний під ту помилкову розкладку і
> працював коректно, але сама назва колонок вводила в оману при читанні
> коду й документації. Виправлено одночасно у `parseRpsl.java` (запис),
> `retrieveMntBy.java` і `retrieveMntner.java` (читання, whois-lite-local)
> та в `asblockwar` — тепер усі відповідають опису вище. **База даних, що
> була згенерована до цього виправлення, несумісна з новим кодом** —
> потрібне повне перестворення `whoislitelocal.db` (видалити файл і
> заново прогнати `--get-data`).

---

## Таблиця `geo`

Геолокаційні дані для IP-адрес.

```sql
CREATE TABLE geo (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ipaddress TEXT UNIQUE, -- IP-адреса (ключ пошуку)
    geo       TEXT         -- Рядок: "city,region,countryName,countryCode"
);
```

**Приклад:** `ipaddress='8.8.8.8'`, `geo='Mountain View,California,United States,US'`

---

## Таблиця `file_metadata`

Службова таблиця для відстеження стану завантажених файлів. Використовується для умовного завантаження (HTTP `If-Modified-Since`).

```sql
CREATE TABLE file_metadata (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    url           TEXT    NOT NULL UNIQUE, -- URL джерела
    last_modified TEXT    NOT NULL,        -- ISO 8601, напр. 2025-06-04T12:34:56Z
    file_size     INTEGER NOT NULL         -- Розмір файлу в байтах
);
```

---

## Зв'язки між таблицями

```
asn ◄─────────────────────────── rpsl (key='aut-num')
    asn.asn = CAST(REPLACE(UPPER(rpsl.value),'AS','') AS INTEGER)

rpsl (key='aut-num'/'as-set'/'role') ◄─── rpsl_mntby
    rpsl.key = rpsl_mntby.key AND rpsl.value = rpsl_mntby.value

rpsl (key='route'/'route6') ──► rpsl_origin
    (через атрибут origin: всередині block при парсингу)

rpsl (key='aut-num') ──► rpsl (key='organisation')
    (через атрибут org: всередині block, текстовий зв'язок)

rpsl_origin.origin ◄──────────── rpsl (key='aut-num')
    rpsl_origin.origin = rpsl.value  (COLLATE NOCASE)
```

---

## Приклади SQL-запитів

### Отримати aut-num об'єкт з метаданими AS

```sql
SELECT
    r.value   AS as_number,
    a.name    AS as_name,
    a.country AS country,
    a.coordinator AS rir,
    a.date    AS delegation_date,
    r.block   AS rpsl_block
FROM rpsl r
LEFT JOIN asn a
    ON a.asn = CAST(REPLACE(UPPER(r.value), 'AS', '') AS INTEGER)
WHERE r.key = 'aut-num'
  AND UPPER(r.value) = UPPER('AS12345');
```

### Отримати всі маршрути, анонсовані AS (з `rpsl_origin`)

```sql
SELECT
    ro.route AS prefix,
    CASE WHEN INSTR(ro.route, ':') > 0 THEN 'ipv6' ELSE 'ipv4' END AS ip_version
FROM rpsl_origin ro
WHERE UPPER(ro.origin) = UPPER('AS12345')
ORDER BY ip_version, ro.route;
```

### Знайти AS за IP-адресою (пошук у діапазоні ipv4/ipv6)

```sql
-- IPv4: підставити BigInteger-представлення IP у :ip_bigint (40 символів з нулями)
SELECT network, country, coordinator, identifier
FROM ipv4
WHERE firstip <= :ip_bigint
  AND lastip  >= :ip_bigint
ORDER BY LENGTH(network) DESC  -- найвужча мережа першою
LIMIT 1;
```

### Отримати всі об'єкти під управлінням мейнтейнера

```sql
SELECT rm.key, rm.value, r.block
FROM rpsl_mntby rm
JOIN rpsl r ON r.key = rm.key AND r.value = rm.value
WHERE UPPER(rm.mntby) = UPPER('EXAMPLE-MNT')
ORDER BY rm.key, rm.value;
```

### Отримати aut-num разом з організацією (два кроки)

```sql
-- Крок 1: отримати block aut-num
SELECT r.block
FROM rpsl r
WHERE r.key = 'aut-num' AND UPPER(r.value) = UPPER('AS12345');

-- Крок 2: витягнути org-ідентифікатор з block у застосунку,
-- потім запитати:
SELECT r.block
FROM rpsl r
WHERE r.key = 'organisation' AND UPPER(r.value) = UPPER(:org_id);
```

> **Чому два кроки?** SQLite не має вбудованої функції для розбивки рядків за `\n`, тому розбір атрибутів `org:`, `import:`, `export:`, `mp-import:`, `mp-export:` зі змісту `block` виконується в коді застосунку (Java метод `block.lines().filter(...)`).

---

## Індекси

| Індекс | Таблиця | Колонки | Статус |
|---|---|---|---|
| `idx_asn_asn` | `asn` | `asn` | Активний |
| `idx_ipv4_coordinator_identifier` | `ipv4` | `coordinator, identifier` | Активний |
| `idx_ipv4_firstip` | `ipv4` | `firstip` | Активний |
| `idx_ipv4_lastip` | `ipv4` | `lastip` | Активний |
| `idx_ipv6_coordinator_identifier` | `ipv6` | `coordinator, identifier` | Активний |
| `idx_ipv6_firstip` | `ipv6` | `firstip` | Активний |
| `idx_ipv6_lastip` | `ipv6` | `lastip` | Активний |
| `idx_rpsl_kv` | `rpsl` | `key, value` | Активний |
| `idx_rpsl_origin` | `rpsl_origin` | `origin` | Закоментований |
| `idx_rpsl_mntby` | `rpsl_mntby` | `mntby` | Закоментований |
