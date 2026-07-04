# whois-lite-local

Утиліта для роботи з extended-файлами RIR (RIPE NCC, ARIN, APNIC, LACNIC, AFRINIC), а також `ripe.db`, asnames та geolocations. Завантажує дані, парсить їх та зберігає у локальній SQLite-базі з підтримкою запитів за ASN, AS-set, мейнтейнером, організацією та IP-мережами.

## Документація

* [Структура бази даних](docs/DATABASE.md) — опис усіх таблиць, індексів, зв'язків між таблицями та приклади SQL-запитів.

## Збірка

**Вимоги:** Java 21+, Maven 3.6+

```bash
mvn clean install
```

> **Увага для систем з непрацюючим IPv6:** якщо Maven не може завантажити залежності з Maven Central, додайте прапор `-Djava.net.preferIPv4Stack=true`:
> ```bash
> MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" mvn clean install
> ```
> Щоб не вводити щоразу: `echo 'MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"' >> ~/.mavenrc`

## Конфігурація

Створіть файл `whoislitelocal.properties` з переліком URL для завантаження:

```properties
url.1=https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-extended-latest
url.2=https://ftp.ripe.net/ripe/dbase/ripe.db.gz
# ...
```

## Usage

```bash
java -jar WhoisLiteLocal-1.0.0.jar [options]
```

| Опція | Коротка | Аргумент | Опис |
|---|---|---|---|
| `--get-data` | `-gd` | — | Завантажити та обробити дані з налаштованих URL |
| `--retrieve-aut-num` | `-ran` | `<as-num>` | Отримати інформацію про aut-num об'єкт та пов'язані об'єкти |
| `--retrieve-as-set` | `-ras` | `<as-set>` | Отримати інформацію про as-set об'єкт |
| `--retrieve-mntner` | `-rm` | `<mntnr>` | Отримати інформацію про mntner та пов'язані об'єкти |
| `--retrieve-mnt-by` | `-rmb` | `<mntnr>` | Отримати об'єкти під управлінням вказаного мейнтейнера |
| `--retrieve-organisation` | `-ro` | `<as-num>` | Отримати інформацію про організацію для вказаного aut-num |
| `--retrieve-route-origin` | `-rro` | `<AS-num>` | Отримати route/route6 об'єкти із вказаним origin |
| `--retrieve-network-origin` | `-rno` | `<net-num>` | Отримати route/route6 об'єкти для вказаної мережі |
| `--help` | `-h` | — | Показати довідку |

## Примітки

> `src/main/resources/schema.sql` є довідковим артефактом і **не використовується** під час роботи програми. Актуальна схема БД формується кодом (`initializeDatabase.java`) при першому запуску. Детальний опис схеми — у [docs/DATABASE.md](docs/DATABASE.md).
