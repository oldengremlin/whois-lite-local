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

import ch.qos.logback.classic.Logger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *
 * @author olden
 */
public class initializeDatabase {

    private final Logger logger;

    initializeDatabase() {
        this.logger = Config.getLogger();
    }

    public initializeDatabase createTables() throws SQLException {
        try (Connection connSQLite = DriverManager.getConnection(Config.getDBUrl())) {
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
                        geo TEXT,
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
                    CREATE TABLE IF NOT EXISTS rpsl (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        key TEXT NOT NULL,
                        value TEXT NOT NULL,
                        block TEXT NOT NULL,
                        UNIQUE(key, value)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS file_metadata (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        url TEXT NOT NULL UNIQUE,
                        last_modified TEXT NOT NULL,
                        file_size INTEGER NOT NULL
                    )""");
                try (PreparedStatement checkStmt = connSQLite.prepareStatement(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name=?")) {
                    // Index idx_asn_asn
                    checkStmt.setString(1, "idx_asn_asn");
                    ResultSet rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_asn_asn' ON 'asn' ('asn')");
                        logger.info("Created index idx_asn_asn on asn table");
                    } else {
                        logger.info("Index idx_asn_asn already exists, skipping creation");
                    }
                    // Index idx_ipv4_coordinator_identifier
                    checkStmt.setString(1, "idx_ipv4_coordinator_identifier");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv4_coordinator_identifier' ON 'ipv4' ('coordinator', 'identifier')");
                        logger.info("Created index idx_ipv4_coordinator_identifier on ipv4 table");
                    } else {
                        logger.info("Index idx_ipv4_coordinator_identifier already exists, skipping creation");
                    }
                    // Index idx_ipv4_firstip
                    checkStmt.setString(1, "idx_ipv4_firstip");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv4_firstip' ON 'ipv4' ('firstip')");
                        logger.info("Created index idx_ipv4_firstip on ipv4 table");
                    } else {
                        logger.info("Index idx_ipv4_firstip already exists, skipping creation");
                    }
                    // Index idx_ipv4_lastip
                    checkStmt.setString(1, "idx_ipv4_lastip");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv4_lastip' ON 'ipv4' ('lastip')");
                        logger.info("Created index idx_ipv4_lastip on ipv4 table");
                    } else {
                        logger.info("Index idx_ipv4_lastip already exists, skipping creation");
                    }
                    // Index idx_ipv6_coordinator_identifier
                    checkStmt.setString(1, "idx_ipv6_coordinator_identifier");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv6_coordinator_identifier' ON 'ipv6' ('coordinator', 'identifier')");
                        logger.info("Created index idx_ipv6_coordinator_identifier on ipv6 table");
                    } else {
                        logger.info("Index idx_ipv6_coordinator_identifier already exists, skipping creation");
                    }
                    // Index idx_ipv6_firstip
                    checkStmt.setString(1, "idx_ipv6_firstip");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv6_firstip' ON 'ipv6' ('firstip')");
                        logger.info("Created index idx_ipv6_firstip on ipv4 table");
                    } else {
                        logger.info("Index idx_ipv6_firstip already exists, skipping creation");
                    }
                    // Index idx_ipv6_lastip
                    checkStmt.setString(1, "idx_ipv6_lastip");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_ipv6_lastip' ON 'ipv6' ('lastip')");
                        logger.info("Created index idx_ipv6_lastip on ipv6 table");
                    } else {
                        logger.info("Index idx_ipv6_lastip already exists, skipping creation");
                    }
                    // Index idx_rpsl_kv
                    checkStmt.setString(1, "idx_rpsl_kv");
                    rs = checkStmt.executeQuery();
                    if (!rs.next()) {
                        stmt.execute("CREATE INDEX 'idx_rpsl_kv' ON 'rpsl' ('key','value')");
                        logger.info("Created index idx_rpsl_kv on rpsl table");
                    } else {
                        logger.info("Index idx_rpsl_kv already exists, skipping creation");
                    }
                }
                connSQLite.commit();
                logger.info("Database initialized");
            } catch (SQLException e) {
                connSQLite.rollback();
                logger.error("Failed to initialize database", e);
                throw e;
            }
        }
        return this;
    }
}
