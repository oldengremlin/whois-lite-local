/*
 * Copyright 2025 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrcom.whoislitelocal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.sqlite.Function;

/**
 *
 * @author olden
 */
@Slf4j
public class InitializeDatabase {

    public InitializeDatabase createTables() throws SQLException {
        try (Connection connSQLite = DriverManager.getConnection(Config.getDBUrl())) {
            configurePragmas(connSQLite);
            connSQLite.setAutoCommit(false);
            try (var stmt = connSQLite.createStatement()) {
                // Create tables
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS asn (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        coordinator TEXT NOT NULL,
                        country TEXT NOT NULL,
                        asn INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        identifier TEXT NOT NULL,
                        name TEXT,
                        UNIQUE(coordinator, asn, identifier)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ipv4 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        coordinator TEXT NOT NULL,
                        country TEXT NOT NULL,
                        network TEXT NOT NULL,
                        firstip TEXT,
                        lastip TEXT,
                        date TEXT NOT NULL,
                        identifier TEXT NOT NULL,
                        UNIQUE(coordinator, network, identifier)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ipv6 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        coordinator TEXT NOT NULL,
                        country TEXT NOT NULL,
                        network TEXT NOT NULL,
                        firstip TEXT,
                        lastip TEXT,
                        date TEXT NOT NULL,
                        identifier TEXT NOT NULL,
                        UNIQUE(coordinator, network, identifier)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS geo (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ipaddress TEXT,
                        geo TEXT,
                        UNIQUE(ipaddress)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS rpsl (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        key TEXT NOT NULL,
                        value TEXT NOT NULL COLLATE NOCASE,
                        block TEXT NOT NULL,
                        block_sha512 TEXT,
                        UNIQUE(key, value)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS file_metadata (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        url TEXT NOT NULL UNIQUE,
                        last_modified TEXT NOT NULL,
                        file_size INTEGER NOT NULL
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS "rpsl_origin" (
	                id INTEGER PRIMARY KEY AUTOINCREMENT,
                	origin TEXT NOT NULL COLLATE NOCASE,
                        route TEXT NOT NULL,
                        UNIQUE(origin, route)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS "rpsl_mntby" (
	                id INTEGER PRIMARY KEY AUTOINCREMENT,
                	key TEXT NOT NULL,
                        value TEXT NOT NULL COLLATE NOCASE,
                	mntby TEXT NOT NULL COLLATE NOCASE,
                	UNIQUE(mntby, key, value)
                    )""");

                try (PreparedStatement checkStmt = connSQLite.prepareStatement(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name=?")) {
                    // Index idx_asn_asn
                    checkStmt.setString(1, "idx_asn_asn");
                    ResultSet rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_asn_asn' ON 'asn' ('asn')");
                        log.info("Created index idx_asn_asn on asn table");
                    } else {
                        log.info("Index idx_asn_asn already exists, skipping creation");
                    }
                    // Index idx_ipv4_coordinator_identifier
                    checkStmt.setString(1, "idx_ipv4_coordinator_identifier");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv4_coordinator_identifier' ON 'ipv4' ('coordinator', 'identifier')");
                        log.info("Created index idx_ipv4_coordinator_identifier on ipv4 table");
                    } else {
                        log.info("Index idx_ipv4_coordinator_identifier already exists, skipping creation");
                    }
                    // Covering index for the containing-allocation seek in
                    // RetrieveNetworkOrigin: ORDER BY firstip DESC LIMIT 1 reads
                    // lastip and network straight from the index.
                    checkStmt.setString(1, "idx_ipv4_range");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv4_range' ON 'ipv4' ('firstip', 'lastip', 'network')");
                        log.info("Created index idx_ipv4_range on ipv4 table");
                    } else {
                        log.info("Index idx_ipv4_range already exists, skipping creation");
                    }
                    // Index idx_ipv6_coordinator_identifier
                    checkStmt.setString(1, "idx_ipv6_coordinator_identifier");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv6_coordinator_identifier' ON 'ipv6' ('coordinator', 'identifier')");
                        log.info("Created index idx_ipv6_coordinator_identifier on ipv6 table");
                    } else {
                        log.info("Index idx_ipv6_coordinator_identifier already exists, skipping creation");
                    }
                    checkStmt.setString(1, "idx_ipv6_range");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv6_range' ON 'ipv6' ('firstip', 'lastip', 'network')");
                        log.info("Created index idx_ipv6_range on ipv6 table");
                    } else {
                        log.info("Index idx_ipv6_range already exists, skipping creation");
                    }
                    // No index on rpsl(key, value): UNIQUE(key, value) already creates
                    // sqlite_autoindex_rpsl_1 on exactly those columns, and the query
                    // planner uses it. A separate idx_rpsl_kv was an exact duplicate —
                    // it is dropped below.
                    //
                    // No index on rpsl_origin(origin) or rpsl_mntby(mntby) either:
                    // UNIQUE(origin, route) and UNIQUE(mntby, key, value) already serve
                    // those lookups as covering indexes on their leading columns.
                }

                dropRedundantIndexes(stmt);
                dropSupersededIndexes(stmt);
                migrateRpslBlockHash(stmt);

                connSQLite.commit();
                log.info("Database initialized");
            } catch (SQLException e) {
                connSQLite.rollback();
                log.error("Failed to initialize database", e);
                throw e;
            }

            // Runs in its own transaction, after the schema is committed
            backfillRpslBlockHash(connSQLite);
        }
        return this;
    }

    /**
     * Applies connection-level pragmas. Must run while the connection is still
     * in autocommit mode: SQLite refuses to change auto_vacuum inside a
     * transaction, and on a database that was created without it the setting
     * only takes effect after a full VACUUM.
     */
    private void configurePragmas(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA busy_timeout = 30000");
            stmt.execute("PRAGMA auto_vacuum = INCREMENTAL");
            try (ResultSet rs = stmt.executeQuery("PRAGMA auto_vacuum")) {
                int mode = rs.next() ? rs.getInt(1) : -1;
                if (mode == 2) {
                    log.info("auto_vacuum = INCREMENTAL");
                } else {
                    log.warn("auto_vacuum = {} (expected 2 = INCREMENTAL). This database was created "
                            + "without incremental auto-vacuum, so PRAGMA incremental_vacuum does nothing. "
                            + "Run once with --vacuum to rebuild the file and enable it.", mode);
                }
            }
        }
    }

    /**
     * Drops indexes that duplicate an implicit UNIQUE index. Space is returned
     * to the file only after a VACUUM.
     */
    private void dropRedundantIndexes(java.sql.Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_rpsl_kv'")) {
            if (!rs.next()) {
                return;
            }
        }
        stmt.execute("DROP INDEX IF EXISTS idx_rpsl_kv");
        log.info("Dropped redundant index idx_rpsl_kv (duplicated UNIQUE(key, value)); "
                + "run --vacuum to reclaim the space");
    }

    /**
     * Removes the single-column firstip/lastip indexes. A range test needs both
     * bounds, so SQLite could only ever use one of them and then filter row by
     * row; idx_ipv4_range / idx_ipv6_range cover the lookup instead.
     */
    private void dropSupersededIndexes(java.sql.Statement stmt) throws SQLException {
        for (String name : new String[]{"idx_ipv4_firstip", "idx_ipv4_lastip",
            "idx_ipv6_firstip", "idx_ipv6_lastip"}) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='" + name + "'")) {
                if (!rs.next()) {
                    continue;
                }
            }
            stmt.execute("DROP INDEX IF EXISTS " + name);
            log.info("Dropped index {} (superseded by the covering range index)", name);
        }
    }

    /**
     * Adds rpsl.block_sha512 to databases created before the column existed.
     * The column caches the block hash so that change detection no longer has
     * to read and hash the whole block on every run.
     */
    private void migrateRpslBlockHash(java.sql.Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(rpsl)")) {
            while (rs.next()) {
                if ("block_sha512".equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        stmt.execute("ALTER TABLE rpsl ADD COLUMN block_sha512 TEXT");
        log.info("Added column rpsl.block_sha512");
    }

    /**
     * Fills block_sha512 for rows that predate the column. One-time cost on an
     * existing database; a no-op on every run after that.
     */
    private void backfillRpslBlockHash(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        registerSha512Function(conn);
        try (var stmt = conn.createStatement()) {
            int pending;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rpsl WHERE block_sha512 IS NULL")) {
                pending = rs.next() ? rs.getInt(1) : 0;
            }
            if (pending == 0) {
                return;
            }
            log.info("Backfilling block_sha512 for {} rpsl records (one-time migration, this may take a while)...",
                    pending);
            long startTime = System.currentTimeMillis();
            int updated = stmt.executeUpdate(
                    "UPDATE rpsl SET block_sha512 = sha512(block) WHERE block_sha512 IS NULL");
            conn.commit();
            log.info("Backfilled {} rpsl records in {} ms", updated, System.currentTimeMillis() - startTime);
        } catch (SQLException e) {
            conn.rollback();
            log.error("Failed to backfill rpsl.block_sha512", e);
            throw e;
        }
    }

    public static void registerSha512Function(Connection conn) throws
            SQLException {
        Function.create(conn, "sha512", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                if (args() != 1) {
                    throw new SQLException("sha512(text) requires one argument");
                }
                try {
                    String input = value_text(0);
                    MessageDigest md = MessageDigest.getInstance("SHA-512");
                    byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
                    result(HexFormat.of().formatHex(hash));
                } catch (NoSuchAlgorithmException | SQLException e) {
                    throw new SQLException("SQL SHA-512 error", e);
                }
            }
        });
    }

    public static String sha512(String input) throws Exception {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new Exception("SHA-512 error", ex);
        }

    }
}
