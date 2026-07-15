# Журнал змін

Усі помітні зміни в цьому проекті документуються тут.

Формат базується на [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
проект дотримується [Семантичного версіонування](https://semver.org/spec/v2.0.0.html).

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
