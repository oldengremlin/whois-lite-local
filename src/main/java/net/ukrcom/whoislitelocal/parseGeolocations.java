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

import java.math.BigInteger;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import static net.ukrcom.whoislitelocal.parseExtended.IP2BigInteger;
import static net.ukrcom.whoislitelocal.parseExtended.IPBigIntegerWithZero;

public class parseGeolocations extends parseAbstract implements parseInterface {

    private int batchCount = 0;
    private static final int BATCH_SIZE = 1000;

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

        String ipAddress = fields[0].trim();
        String city = fields[2].trim();        // Kyiv
        String region = fields[3].trim();      // Kiev City
        String countryName = fields[4].trim(); // Ukraine
        String countryCode = fields[5].trim(); // UA
        String geo = String.join(",", city, region, countryName, countryCode);

        if (geo.isEmpty() || countryCode.length() != 2) {
            pf.logger.warn("Invalid geo data in line: {}", this.line);
            return;
        }

        try {
            BigInteger ipBigInt = IP2BigInteger(ipAddress);
            if (ipBigInt == null) {
                pf.logger.warn("Invalid IP address: {}", ipAddress);
                return;
            }
            String ipBigIntStr = IPBigIntegerWithZero(ipBigInt.toString());

            // Check if GEO exists in the table
            try (PreparedStatement selectStmt = pf.connection.prepareStatement(
                    "SELECT geo FROM geo WHERE ipaddress = ?")) {
                selectStmt.setString(1, ipBigIntStr);
                ResultSet rs = selectStmt.executeQuery();
                if (rs.next()) {
                    // GEO exists
                    String existingGeo = rs.getString("geo");

                    if (existingGeo != null && existingGeo.contains(geo)) {
                        return;
                    }
                    String geoUpdate = existingGeo == null ? geo : existingGeo + "|" + geo;

                    try (PreparedStatement updateStmt = pf.connection.prepareStatement(
                            "UPDATE geo SET geo = ? WHERE ipaddress = ?")) {
                        updateStmt.setString(1, geoUpdate);
                        updateStmt.setString(2, ipBigIntStr);
//                        updateStmt.executeUpdate();
                        updateStmt.addBatch();
                        pf.logger.info("Update GEO for {} [{}]: {} ", ipAddress, ipBigIntStr, geo);
                        if (++batchCount % BATCH_SIZE == 0) {
                            updateStmt.executeBatch();
                        }
                    } catch (SQLException ex) {
                        pf.logger.warn("Can't update GEO for {}: {} : ", ipBigIntStr, geo, ex);
                    }
                } else {
                    try (PreparedStatement insertStmt = pf.connection.prepareStatement(
                            "INSERT INTO geo (ipaddress, geo) VALUES (?, ?)")) {
                        insertStmt.setString(1, ipBigIntStr);
                        insertStmt.setString(2, geo);
//                        insertStmt.executeUpdate();
                        insertStmt.addBatch();
                        pf.logger.debug("Insert GEO for {} [{}]: {} ", ipAddress, ipBigIntStr, geo);
                        if (++batchCount % BATCH_SIZE == 0) {
                            insertStmt.executeBatch();
                        }
                    } catch (SQLException ex) {
                        pf.logger.warn("Can't insert GEO for {}: {} : ", ipBigIntStr, geo, ex);
                    }

                }
            } catch (SQLException ex) {
                pf.logger.error("SQLException for line {}: {}", this.line, ex.getMessage(), ex);
            }

        } catch (UnknownHostException ex) {
            pf.logger.warn("Error in parse data: {}", this.line, ex);
        }

    }
}
