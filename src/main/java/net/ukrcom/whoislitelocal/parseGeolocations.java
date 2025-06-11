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
import java.math.BigInteger;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.compress.compressors.CompressorException;
import static net.ukrcom.whoislitelocal.parseExtended.IP2BigInteger;
import static net.ukrcom.whoislitelocal.parseExtended.IPBigIntegerWithZero;

public class parseGeolocations extends parseAbstract implements parseInterface {

    private final Map<String, String> geoCache = new HashMap<>(); // Кеш для зменшення запитів до БД
    private final Map<String, String> geoIPCache = new HashMap<>(); // Кеш для зменшення запитів до БД
    private processFiles pf;

    @Override
    public void parse(processFiles pf) {
        this.pf = pf;
        try (
                InputStream fileIn = Files.newInputStream(pf.tempFile);
                BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
                InputStream decompressedIn = tryDecompress(bufferedIn);
                InputStreamReader decoder = new InputStreamReader(decompressedIn, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(decoder)) {
            // Зберігаємо з'єднання для батчів
            Connection conn = pf.connection;

            // Очистити поле geo в таблиці asn
            try (PreparedStatement clearStmt = conn.prepareStatement("UPDATE asn SET geo = ''")) {
                int updated = clearStmt.executeUpdate();
                pf.logger.info("Cleared geo for {} ASN records", updated);
            } catch (SQLException ex) {
                pf.logger.error("Failed to cleared geo", ex);
            }

            try (
                    PreparedStatement selectStmt = conn.prepareStatement(
                            "SELECT coordinator, identifier, network, firstip, lastip FROM ipv4 WHERE firstip <= ? AND lastip >= ?"
                            + "UNION ALL "
                            + "SELECT coordinator, identifier, network, firstip, lastip FROM ipv6 WHERE firstip <= ? AND lastip >= ?");
                    PreparedStatement selectAsnStmt = conn.prepareStatement(
                            "SELECT geo FROM asn WHERE coordinator = ? AND identifier = ?");
                    PreparedStatement updateStmt = conn.prepareStatement(
                            "UPDATE asn SET geo = ? WHERE coordinator = ? AND identifier = ?");) {
                int batchSize = 0;
                while ((this.line = reader.readLine()) != null) {
                    if (store(selectStmt, selectAsnStmt, updateStmt)) {
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
        } catch (CompressorException ex) {
            pf.logger.error("Compression error while parsing {}", pf.tempFile, ex);
        } finally {
            try {
                Files.delete(pf.tempFile);
                pf.logger.info("Deleted temporary file {}", pf.tempFile);
            } catch (IOException e) {
                pf.logger.warn("Failed to delete temporary file {}: {}", pf.tempFile, e.getMessage());
            }
        }
    }

    private boolean store(PreparedStatement selectStmt, PreparedStatement selectAsnStmt, PreparedStatement updateStmt) throws
            UnknownHostException {
        if (this.line.trim().isEmpty()) {
            return false;
        }

        String[] fields = this.line.split(",");
        if (fields.length < 6) {
            this.pf.logger.warn("Invalid geolocations line format: {}", this.line);
            return false;
        }

        String ipAddress = fields[0];
        String city = fields[2];        // Kyiv
        String region = fields[3];      // Kiev City
        String countryName = fields[4]; // Ukraine
        String countryCode = fields[5]; // UA
        String geo = String.join(",", city, region, countryName, countryCode);

        if (geo.isEmpty() || countryCode.length() != 2) {
            this.pf.logger.warn("Invalid geo data in line: {}", this.line);
            return false;
        }

        try {

            ipAddress = ipAddress.replaceFirst("\\d+/\\d+$", "0/24");
            if (geoIPCache.containsKey(ipAddress) && geoIPCache.get(ipAddress).equals(geo + "|")) {
                return false;
            }

            BigInteger ipBigInt = IP2BigInteger(ipAddress);
            String ipBigIntStr = IPBigIntegerWithZero(ipBigInt.toString());

            selectStmt.setString(1, ipBigIntStr);
            selectStmt.setString(2, ipBigIntStr);
            selectStmt.setString(3, ipBigIntStr);
            selectStmt.setString(4, ipBigIntStr);

            String bestCoordinator = null;
            String bestIdentifier = null;
            String bestNetwork = null;
            BigInteger minRange = null;

            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {

                    //this.pf.logger.warn("{} <-- {}", selectStmt, ipBigIntStr);
                    String coordinator = rs.getString("coordinator");
                    String identifier = rs.getString("identifier");
                    String network = rs.getString("network");

                    BigInteger firstIp = new BigInteger(rs.getString("firstip"));
                    BigInteger lastIp = new BigInteger(rs.getString("lastip"));
                    BigInteger range = lastIp.subtract(firstIp);

                    if (minRange == null || range.compareTo(minRange) < 0) {
                        minRange = range;
                        bestCoordinator = coordinator;
                        bestIdentifier = identifier;
                        bestNetwork = network;
                    }

                }
            }

            if (bestCoordinator != null) {

                String cacheKey = bestCoordinator + "|" + bestIdentifier;
                if (geoCache.containsKey(cacheKey) && geoCache.get(cacheKey).equals(geo)) {
                    return false;
                }

                this.pf.logger.debug("GEO: ipAddress={} ({}) — {}", ipAddress, ipBigIntStr, geo);

                selectAsnStmt.setString(1, bestCoordinator);
                selectAsnStmt.setString(2, bestIdentifier);

                try (ResultSet asnRs = selectAsnStmt.executeQuery()) {
                    if (asnRs.next()) {

                        String existingGeo = asnRs.getString("geo");

                        if (existingGeo == null || !existingGeo.equals(geo)) {

                            String newGeo = geo + "|";
                            if (existingGeo != null && geo != null && existingGeo.indexOf(newGeo) > 0) {
                                return false;
                            }
                            newGeo += existingGeo;

                            this.pf.logger.info("new GEO: ipAddress={} ({}) — {}", ipAddress, ipBigIntStr, newGeo);

                            updateStmt.setString(1, newGeo);
                            updateStmt.setString(2, bestCoordinator);
                            updateStmt.setString(3, bestIdentifier);
                            updateStmt.addBatch();

                            geoCache.put(cacheKey, newGeo);
                            geoIPCache.put(ipAddress, newGeo);

                            this.pf.logger.info("Updated geo for coordinator={}, identifier={}: {}",
                                    bestCoordinator, bestIdentifier, newGeo);

                            return true;
                        }

                    }
                }
            }
            // this.pf.logger.warn("No network found for IP {} in ipv4 or ipv6", ipAddress);
        } catch (SQLException e) {
            this.pf.logger.error("Failed to process geolocations line: {}", this.line, e);
        }
        return false;
    }
}
