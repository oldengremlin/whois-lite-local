# Журнал змін

Усі помітні зміни в цьому проекті документуються тут.

Формат базується на [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
проект дотримується [Семантичного версіонування](https://semver.org/spec/v2.0.0.html).

## [1.1.4] — 2026-07-15

### Додано
- Опція `--vacuum` / `-vc`: запускає повний `VACUUM` SQLite після завершення оновлення даних (`--get-data --vacuum`) або окремо для компактування наявної БД (`--vacuum`). Без цієї опції виконується лише incremental vacuum з порогом 25% фрагментації, як і раніше

## [1.1.3] — 2026-07-15

### Виправлено
- `cleanupOutdatedRpsl(processFiles pf)` мав невикористовуваний параметр `pf`: метод використовував `this.pf` безпосередньо, тому параметр був зайвим і викликав IDE-попередження; параметр видалено, сигнатуру змінено на `cleanupOutdatedRpsl()`
- `UnsupportedClassVersionError` при запуску: JAR компілювався під Java 25 (class file version 69.0), але виконувався на Java 24 (розпізнає до 68.0); цільову версію компілятора знижено до Java 21 (LTS)

## [1.1.2] — 2026-07-15

### Виправлено
- `cleanupRpslOriginAndMntBy()` ніколи не викликався після `cleanupOutdatedRpsl()`: стала інформація про `origin` та `mnt-by` нескінченно накопичувалася в таблицях `rpsl_origin` і `rpsl_mntby` — метод існував, але виклик у `parse()` був відсутній
- `HelpFormatter` deprecated з Commons CLI 1.8.0: замінено `new HelpFormatter()` на `HelpFormatter.builder().get()`; заодно прибрано хардкод версії з рядка довідки

## [1.1.1] — 2026-07-15

### Виправлено
- `SQLITE_BUSY_SNAPSHOT` при паралельному записі трьома парсерами: замінено три конкуруючих з'єднання на одне спільне `Connection` у `executeGetData`; DB-операції серіалізовано через `synchronized(pf.connection)`, CPU-робота (розбір рядків, IP-конвертація, `addBatch`) лишається паралельною
- Lombok 1.18.36 не компілювався з JDK 24+ (`TypeTag::UNKNOWN`): оновлено до 1.18.42
- Попередження `sun.misc.Unsafe::objectFieldOffset` від Lombok при компіляції: у `maven-compiler-plugin` додано `<fork>true</fork>` та `-J--sun-misc-unsafe-memory-access=allow`

### Видалено
- `src/main/resources/schema.sql` — довідковий артефакт, що ніколи не використовувався під час роботи; актуальна схема формується кодом (`initializeDatabase.java`) при першому запуску

## [1.1.0] — 2026-07-15

### Додано
- Паралельне завантаження HTTP у межах кожної групи URL за допомогою virtual threads: усі файли, що потребують оновлення, завантажуються одночасно
- Паралельне виконання незалежних парсерів (`parseExtended`, `parseAsnames`, `parseGeolocations`) в `executeGetData`; вони записують у різні таблиці. `parseRpsl` виконується після них послідовно
- SQLite WAL-режим (`PRAGMA journal_mode = WAL`) для кращої паралельної роботи з читанням/записом
- `PRAGMA busy_timeout = 30000` на кожному з'єднанні для коректного очікування при конкуренції за запис
- Lombok `@Slf4j` замість ручних полів `Logger` у всіх класах; кожен клас тепер логує під власним ім'ям
- `CONTRIBUTING.md` із описом збірки, конфігурації та гілкової стратегії

### Виправлено
- `cleanupOutdatedRpsl` видаляв усі незмінені RPSL-записи при кожному інкрементальному оновленні: `saveBlock()` повертався раніше, не вставляючи незмінені блоки в `temp_rpsl`, тому предикат `NOT EXISTS` вважав кожен незмінений об'єкт застарілим
- `processFiles` завантажував `whoislitelocal.properties` з файлової системи за відносним шляхом, що робило JAR залежним від поточної директорії; перейшло на завантаження з classpath через `getResourceAsStream()`

### Змінено
- Поріг VACUUM змінено з абсолютного (100 сторінок) на відносний (25% фрагментації)
- Дедублікація виводу: `Config.printBlock()` дедублює на рівні блоків (SHA-512 Highlander) та на рівні атрибутів всередині блоку з урахуванням continuation-рядків (RFC 2622 §2)
- Continuation-рядки RPSL тепер зберігаються через `stripTrailing()` замість `trim()`

## [1.0.0] — 2025-01-01

### Додано
- Перший реліз: завантаження та парсинг extended-файлів делегувань RIR, RIPE asnames, geolocations та дампу `ripe.db` у локальну SQLite-базу
- CLI: `--get-data`, `--retrieve-aut-num`, `--retrieve-as-set`, `--retrieve-mntner`, `--retrieve-mnt-by`, `--retrieve-organisation`, `--retrieve-route-origin`, `--retrieve-network-origin`
- `docs/DATABASE.md` — документація схеми бази даних
