# Contributing to whois-lite-local

## Requirements
Java 21+, Maven 3.6+

## Build
mvn clean package
On systems with non-functional IPv6: MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" mvn clean package

## Configuration
Create src/main/resources/whoislitelocal.properties (git-ignored) before building.
The file is bundled into the JAR by Maven Shade and loaded from classpath — JAR runs from any directory.

Required keys: urls_extended, asnames, geolocations, ripedb (URL or comma-separated URLs)

## Branch strategy
- main: stable
- feature/fix branches: PR into main

## Code style
- Java 21, no new dependencies without discussion
- Existing naming conventions (camelCase classes)
- No comments unless the WHY is non-obvious
- Batch JDBC operations — don't replace with row-by-row

## Versioning
Semantic versioning in pom.xml. Update CHANGELOG.md with every release.
MAJOR: incompatible DB schema or CLI changes. MINOR: new features. PATCH: bug fixes.
