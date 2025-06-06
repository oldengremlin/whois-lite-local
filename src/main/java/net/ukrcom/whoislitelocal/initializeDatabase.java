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
                        UNIQUE(coordinator, asn, identifier)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ipv4 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        coordinator TEXT NOT NULL,
                        country TEXT NOT NULL,
                        network TEXT NOT NULL,
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
                        date TEXT NOT NULL,
                        identifier TEXT NOT NULL,
                        UNIQUE(coordinator, network, identifier)
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS file_metadata (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        url TEXT NOT NULL UNIQUE,
                        last_modified TEXT NOT NULL,
                        file_size INTEGER NOT NULL
                    )""");
                stmt.execute("""
                             CREATE INDEX 'idx_asn_asn' ON 'asn' (
                             	'asn'
                             )
                             """);
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
