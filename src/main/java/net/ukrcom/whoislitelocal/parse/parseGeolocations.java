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
package net.ukrcom.whoislitelocal.parse;

import java.math.BigInteger;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import static net.ukrcom.whoislitelocal.parse.parseExtended.IP2BigInteger;
import static net.ukrcom.whoislitelocal.parse.parseExtended.IPBigIntegerWithZero;

@Slf4j
public class parseGeolocations extends parseAbstract implements parseInterface {

    private int batchCount = 0;
    private PreparedStatement storeUpdateStmt;
    private PreparedStatement storeInsertStmt;
    private static final int BATCH_SIZE = 1000;

    @Override
    public void parse(processFiles pf) {
        try {
            synchronized (pf.connection) {
                storeUpdateStmt = pf.connection.prepareStatement("UPDATE geo SET geo = ? WHERE ipaddress = ?");
                storeInsertStmt = pf.connection.prepareStatement("INSERT INTO geo (ipaddress, geo) VALUES (?, ?)");
            }

            super.parse(pf); // per-row store() calls + vacuum + file_metadata (each synchronized internally)

            synchronized (pf.connection) {
                if (batchCount > 0) {
                    storeUpdateStmt.executeBatch();
                    storeInsertStmt.executeBatch();
                }
                runIncrementalVacuumSmart(pf);
            }
        } catch (SQLException ex) {
            log.warn("SQLException: {}", ex);
        } finally {
            synchronized (pf.connection) {
                if (storeUpdateStmt != null) {
                    try { storeUpdateStmt.close(); } catch (SQLException ignore) {}
                }
                if (storeInsertStmt != null) {
                    try { storeInsertStmt.close(); } catch (SQLException ignore) {}
                }
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
            log.warn("Invalid geolocations line format: {}", this.line);
            return;
        }

        String ipAddress = fields[0].trim();
        String city = fields[2].trim();        // Kyiv
        String region = fields[3].trim();      // Kiev City
        String countryName = fields[4].trim(); // Ukraine
        String countryCode = fields[5].trim(); // UA
        String geo = String.join(",", city, region, countryName, countryCode);

        if (geo.isEmpty() || countryCode.length() != 2) {
            log.warn("Invalid geo data in line: {}", this.line);
            return;
        }

        try {
            // CPU work — no lock needed
            BigInteger ipBigInt = IP2BigInteger(ipAddress);
            if (ipBigInt == null) {
                log.warn("Invalid IP address: {}", ipAddress);
                return;
            }
            String ipBigIntStr = IPBigIntegerWithZero(ipBigInt.toString());

            // SELECT needs the lock; determine what to do with the result
            boolean doUpdate = false;
            boolean doInsert = false;
            String geoUpdate = null;
            synchronized (pf.connection) {
                try (PreparedStatement selectStmt = pf.connection.prepareStatement(
                        "SELECT geo FROM geo WHERE ipaddress = ?")) {
                    selectStmt.setString(1, ipBigIntStr);
                    ResultSet rs = selectStmt.executeQuery();
                    if (rs.next()) {
                        String existingGeo = rs.getString("geo");
                        if (existingGeo == null || !existingGeo.contains(geo)) {
                            geoUpdate = existingGeo == null ? geo : existingGeo + "|" + geo;
                            doUpdate = true;
                        }
                    } else {
                        doInsert = true;
                    }
                } catch (SQLException ex) {
                    log.error("SQLException for line {}: {}", this.line, ex.getMessage(), ex);
                }
            }

            // addBatch is local to this thread's statement — no lock needed
            if (doUpdate) {
                storeUpdateStmt.setString(1, geoUpdate);
                storeUpdateStmt.setString(2, ipBigIntStr);
                storeUpdateStmt.addBatch();
                log.info("Update GEO for {} [{}]: {} ", ipAddress, ipBigIntStr, geo);
                if (++batchCount == BATCH_SIZE) {
                    synchronized (pf.connection) { storeUpdateStmt.executeBatch(); }
                    batchCount = 0;
                }
            } else if (doInsert) {
                storeInsertStmt.setString(1, ipBigIntStr);
                storeInsertStmt.setString(2, geo);
                storeInsertStmt.addBatch();
                log.debug("Insert GEO for {} [{}]: {} ", ipAddress, ipBigIntStr, geo);
                if (++batchCount == BATCH_SIZE) {
                    synchronized (pf.connection) { storeInsertStmt.executeBatch(); }
                    batchCount = 0;
                }
            }
        } catch (UnknownHostException ex) {
            log.warn("Error in parse data: {}", this.line, ex);
        } catch (SQLException ex) {
            log.warn("Can't batch GEO for line {}: {}", this.line, ex.getMessage());
        }

    }
}
