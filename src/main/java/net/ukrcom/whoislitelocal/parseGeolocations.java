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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

public class parseGeolocations extends parseAbstract implements parseInterface {

    private final Map<String, String> geoCache = new HashMap<>(); // Кеш для зменшення запитів до БД

    @Override
    public void parse(processFiles pf) {
        try (InputStream fileIn = Files.newInputStream(pf.tempFile); BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(fileIn); BufferedReader reader = new BufferedReader(new InputStreamReader(bzIn, StandardCharsets.UTF_8))) {
            // Зберігаємо з'єднання для батчів
            Connection conn = pf.connection;
            try (
                    PreparedStatement selectStmt = conn.prepareStatement(
                            "SELECT coordinator, identifier FROM ipv4 WHERE firstip = ? UNION ALL "
                            + "SELECT coordinator, identifier FROM ipv6 WHERE firstip = ?"); PreparedStatement selectAsnStmt = conn.prepareStatement(
                            "SELECT geo FROM asn WHERE coordinator = ? AND identifier = ?"); PreparedStatement updateStmt = conn.prepareStatement(
                            "UPDATE asn SET geo = ? WHERE coordinator = ? AND identifier = ?");) {

                int batchSize = 0;
                while ((this.line = reader.readLine()) != null) {
                    if (store(pf, selectStmt, selectAsnStmt, updateStmt)) {
                        batchSize++;
                        if (batchSize >= 1000) { // Виконуємо батч кожні 1000 записів
                            updateStmt.executeBatch();
                            batchSize = 0;
                        }
                    }
                }
                if (batchSize > 0) {
                    updateStmt.executeBatch(); // Виконуємо залишкові батчі
                }
            } catch (SQLException e) {
                pf.logger.error("Failed to process geolocations batch", e);
            }
            // Update file metadata
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                stmt.setString(1, pf.processUrl);
                stmt.setString(2, pf.lastModified);
                stmt.setLong(3, pf.fileSize);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                pf.logger.error("Error storing metadata for URL {}, SQLException {}", pf.processUrl, ex);
            }
        } catch (IOException ex) {
            pf.logger.error("Can't parse temporary file {}", pf.tempFile, ex);
        } finally {
            try {
                Files.delete(pf.tempFile);
                pf.logger.info("Deleted temporary file {}", pf.tempFile);
            } catch (IOException e) {
                pf.logger.warn("Failed to delete temporary file {}: {}", pf.tempFile, e.getMessage());
            }
        }
    }

    private boolean store(processFiles pf, PreparedStatement selectStmt, PreparedStatement selectAsnStmt, PreparedStatement updateStmt) {
        if (this.line.trim().isEmpty()) {
            return false; // Skip empty lines
        }

        String[] fields = this.line.split(",");
        if (fields.length < 6) {
            pf.logger.warn("Invalid geolocations line format: {}", this.line);
            return false;
        }

        String ipAddress = fields[0];
        String city = fields[2];        // Kyiv
        String region = fields[3];      // Kiev City
        String countryName = fields[4]; // Ukraine
        String countryCode = fields[5]; // UA

        // Формуємо поле geo
        String geo = String.join(",", city, region, countryName, countryCode);
        if (geo.isEmpty() || countryCode.length() != 2) {
            pf.logger.warn("Invalid geo data in line: {}", this.line);
            return false;
        }

        try {
            // Шукаємо мережу за firstip (перевіряємо і ipv4, і ipv6 через UNION ALL)
            selectStmt.setString(1, ipAddress);
            selectStmt.setString(2, ipAddress);
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (rs.next()) {
                    String coordinator = rs.getString("coordinator");
                    String identifier = rs.getString("identifier");
                    String cacheKey = coordinator + "|" + identifier;

                    // Перевіряємо кеш
                    if (geoCache.containsKey(cacheKey)) {
                        if (geoCache.get(cacheKey).equals(geo)) {
                            return false; // Geo не змінилося, пропускаємо
                        }
                    }

                    // Шукаємо ASN за coordinator і identifier
                    selectAsnStmt.setString(1, coordinator);
                    selectAsnStmt.setString(2, identifier);
                    try (ResultSet asnRs = selectAsnStmt.executeQuery()) {
                        if (asnRs.next()) {
                            String existingGeo = asnRs.getString("geo");
                            if (existingGeo == null || !existingGeo.equals(geo)) {
                                // Додаємо до батча для оновлення
                                updateStmt.setString(1, geo);
                                updateStmt.setString(2, coordinator);
                                updateStmt.setString(3, identifier);
                                updateStmt.addBatch();
                                geoCache.put(cacheKey, geo); // Оновлюємо кеш

                                pf.logger.info("Updated geo for coordinator={}, identifier={}: {}", coordinator, identifier, geo);

                                return true;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            pf.logger.error("Failed to process geolocations line: {}", this.line, e);
        }
        // pf.logger.warn("No network found for IP {} in ipv4 or ipv6", ipAddress);
        return false;
    }
}
