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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 *
 * @author olden
 */
public class parseGeolocations extends parseAbstract implements parseInterface {

    @Override
    public void parse(processFiles pf) {
        // Parse temporary file
        try (InputStream fileIn = Files.newInputStream(pf.tempFile); BufferedInputStream bufferedIn = new BufferedInputStream(fileIn); BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bufferedIn); InputStreamReader decoder = new InputStreamReader(bzIn, StandardCharsets.UTF_8); BufferedReader reader = new BufferedReader(decoder)) {
            while ((this.line = reader.readLine()) != null) {
                store(pf);
            }
            // Update file metadata
            try (PreparedStatement stmt = pf.connection.prepareStatement(
                    "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                stmt.setString(1, pf.processUrl);
                stmt.setString(2, pf.lastModified);
                stmt.setLong(3, pf.fileSize);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                pf.logger.error("Error store metadata for URL {}, SQLException {}", pf.processUrl, ex);
            }
        } catch (IOException ex) {
            pf.logger.error("Can't parsing temporary file {}", pf.tempFile);
        } finally {
            // Delete temporary file
            try {
                Files.delete(pf.tempFile);
                pf.logger.info("Deleted temporary file {}", pf.tempFile);
            } catch (IOException e) {
                pf.logger.warn("Failed to delete temporary file {}: {}", pf.tempFile, e.getMessage());
            }
        }
    }

    @Override
    public void store(processFiles pf) {
        if (this.line.trim().isEmpty()) {
            return; // Skip empty lines
        }

        String[] fields = this.line.split(",");
        if (fields.length < 6) {
            pf.logger.warn("Invalid geolocations line format: {}", this.line);
            return;
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
            return;
        }

        try {
            // Визначаємо, чи це IPv4 чи IPv6
            String table = ipAddress.contains(":") ? "ipv6" : "ipv4";
            // Шукаємо мережу за firstip
            try (PreparedStatement selectStmt = pf.connection.prepareStatement(
                    "SELECT coordinator, identifier FROM " + table + " WHERE firstip = ?")) {
                selectStmt.setString(1, ipAddress);
                ResultSet rs = selectStmt.executeQuery();
                if (rs.next()) {
                    String coordinator = rs.getString("coordinator");
                    String identifier = rs.getString("identifier");
                    // Шукаємо ASN за coordinator і identifier
                    try (PreparedStatement selectAsnStmt = pf.connection.prepareStatement(
                            "SELECT geo FROM asn WHERE coordinator = ? AND identifier = ?")) {
                        selectAsnStmt.setString(1, coordinator);
                        selectAsnStmt.setString(2, identifier);
                        ResultSet asnRs = selectAsnStmt.executeQuery();
                        while (asnRs.next()) {
                            String existingGeo = asnRs.getString("geo");
                            if (existingGeo == null || !existingGeo.equals(geo)) {
                                // Оновлюємо geo, якщо воно відрізняється
                                try (PreparedStatement updateStmt = pf.connection.prepareStatement(
                                        "UPDATE asn SET geo = ? WHERE coordinator = ? AND identifier = ?")) {
                                    updateStmt.setString(1, geo);
                                    updateStmt.setString(2, coordinator);
                                    updateStmt.setString(3, identifier);
                                    int updated = updateStmt.executeUpdate();
                                    if (updated > 0) {
                                        pf.logger.info("Updated geo for coordinator={}, identifier={}: {}", coordinator, identifier, geo);
                                    }
                                }
                            }
                        }
                    }
                }
//                } else {
//                    pf.logger.warn("No network found for IP {} in {}", ipAddress, table);
//                }
            }
        } catch (SQLException e) {
            pf.logger.error("Failed to process geolocations line: {}", this.line, e);
        }

    }
}
