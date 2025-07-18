-- Таблиця для ASN-записів
CREATE TABLE IF NOT EXISTS asn (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    coordinator TEXT NOT NULL,
    country TEXT NOT NULL,
    asn INTEGER NOT NULL,
    date TEXT NOT NULL, -- Формат YYYYMMDD
    identifier TEXT NOT NULL,
    name TEXT, -- Назва ASN
    geo TEXT, -- Геолокація: city,region,countryName,countryCode
    UNIQUE(coordinator, asn, identifier)
);
CREATE INDEX 'idx_asn_asn' ON 'asn' ('asn');

-- Таблиця для IPv4-записів
CREATE TABLE IF NOT EXISTS ipv4 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    coordinator TEXT NOT NULL,
    country TEXT NOT NULL,
    network TEXT NOT NULL, -- Наприклад, 212.90.160.0/19
    firstip TEXT, -- Перша IP-адреса мережі (наприклад, 212.90.160.1/32)
    date TEXT NOT NULL,
    identifier TEXT NOT NULL,
    UNIQUE(coordinator, network, identifier)
);
CREATE INDEX 'idx_ipv4_coordinator_identifier' ON 'ipv4' ('coordinator', 'identifier');
CREATE INDEX 'idx_ipv4_firstip' ON 'ipv4' ('firstip');

-- Таблиця для IPv6-записів
CREATE TABLE IF NOT EXISTS ipv6 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    coordinator TEXT NOT NULL,
    country TEXT NOT NULL,
    network TEXT NOT NULL, -- Наприклад, 2a04:42c0::/29
    firstip TEXT, -- Перша IP-адреса мережі (наприклад, 2a04:42c0::1/128)
    date TEXT NOT NULL,
    identifier TEXT NOT NULL,
    UNIQUE(coordinator, network, identifier)
);
CREATE INDEX 'idx_ipv6_coordinator_identifier' ON 'ipv6' ('coordinator', 'identifier');
CREATE INDEX 'idx_ipv6_firstip' ON 'ipv6' ('firstip');

-- Таблиця для метаданих файлів
CREATE TABLE IF NOT EXISTS file_metadata (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    url TEXT NOT NULL UNIQUE,
    last_modified TEXT NOT NULL, -- Формат ISO 8601, наприклад, 2025-06-04T12:34:56Z
    file_size INTEGER NOT NULL
);
