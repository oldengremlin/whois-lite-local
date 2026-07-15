# Участь у розробці whois-lite-local

## Вимоги

Java 21+, Maven 3.6+

## Збірка

```bash
mvn clean package
```

На системах із непрацюючим IPv6:
```bash
MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" mvn clean package
```

## Конфігурація

Перед збіркою створіть файл `src/main/resources/whoislitelocal.properties` (виключений з git).
Файл вбудовується в JAR через Maven Shade і завантажується з classpath — JAR можна запускати з будь-якої директорії.

Обов'язкові ключі: `urls_extended`, `asnames`, `geolocations`, `ripedb` (URL або список URL через кому)

## Гілкова стратегія

- `main` — стабільна гілка
- feature/fix-гілки → PR у `main`

## Стиль коду

- Java 21, нові залежності — тільки після обговорення
- Наявні конвенції іменування (camelCase для класів)
- Коментарі — лише якщо НЕ ОЧЕВИДНО, *чому* щось зроблено саме так
- Батчеві JDBC-операції — не замінювати на порядковий INSERT/UPDATE

## Версіонування

Семантичне версіонування в `pom.xml`. При кожному релізі оновлювати `CHANGELOG.md`.

- **MAJOR** — несумісні зміни схеми БД або CLI
- **MINOR** — нові можливості
- **PATCH** — виправлення помилок
