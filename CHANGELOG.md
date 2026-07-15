# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-07-15
### Added
- Parallel HTTP downloads within each URL group using virtual threads: all files that need updating are downloaded concurrently
- Parallel execution of independent parsers (parseExtended, parseAsnames, parseGeolocations) in executeGetData; these write to non-overlapping tables. parseRpsl runs sequentially after them
- SQLite WAL mode (PRAGMA journal_mode = WAL) for better concurrent read/write behaviour
- PRAGMA busy_timeout = 30000 on every connection to handle write contention under parallel load
- Lombok @Slf4j replacing manual Logger fields across all classes; each class now logs under its own name
- CONTRIBUTING.md with build, configuration and branch strategy

### Fixed
- cleanupOutdatedRpsl deleted all unchanged RPSL records on every incremental run: saveBlock() returned early without inserting unchanged blocks into temp_rpsl, so the NOT EXISTS predicate treated every unchanged object as stale
- processFiles loaded whoislitelocal.properties from filesystem path, making JAR location-dependent; switched to classpath loading via getResourceAsStream()

### Changed
- VACUUM threshold changed from absolute 100 pages to relative 25% fragmentation ratio
- Output deduplication: Config.printBlock() deduplicates at SHA-512 block level (Highlander) and RFC 2622 §2 continuation-aware intra-block attribute level
- RPSL continuation lines preserved with stripTrailing() instead of trim()

## [1.0.0] — 2025-01-01
### Added
- Initial release: download and parse RIR extended delegation files, RIPE asnames, geolocations, and ripe.db RPSL dump into a local SQLite database
- CLI: --get-data, --retrieve-aut-num, --retrieve-as-set, --retrieve-mntner, --retrieve-mnt-by, --retrieve-organisation, --retrieve-route-origin, --retrieve-network-origin
- docs/DATABASE.md schema documentation
