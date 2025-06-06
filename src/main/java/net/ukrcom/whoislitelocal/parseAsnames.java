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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 *
 * @author olden
 */
public class parseAsnames extends parseAbstract implements parseInterface {

    @Override
    public void store(processFiles pf) {
        if (this.line.trim().isEmpty()) {
            return; // Skip empty lines
        }

        // Split by first space to get ASN and the rest
        String[] parts = this.line.split("\\s+", 2);
        if (parts.length < 2) {
            pf.logger.warn("Invalid asnames line format: {}", this.line);
            return;
        }

        int asn;
        try {
            if (Integer.parseInt(parts[0]) == 0) {
                return;
            }
            asn = IpUtils.validateAsn(parts[0]);
        } catch (IllegalArgumentException e) {
            pf.logger.warn("Invalid ASN in asnames line: {}", this.line, e);
            return;
        }

        // Split the rest by the last comma to separate name and country
        String nameAndCountry = parts[1].trim();
        int lastCommaIndex = nameAndCountry.lastIndexOf(",");
        if (lastCommaIndex == -1 || lastCommaIndex == nameAndCountry.length() - 1) {
            pf.logger.warn("Invalid asnames line format (missing country): {}", this.line);
            return;
        }

        String name = nameAndCountry.substring(0, lastCommaIndex).trim();
        String country = nameAndCountry.substring(lastCommaIndex + 1).trim();
        if (country.length() != 2) {
            pf.logger.warn("Invalid country code in asnames line: {}", this.line);
            return;
        }
        if ("ZZ".equalsIgnoreCase(country)) {
            return;
        }

        // Check if ASN exists in the table
        try (PreparedStatement selectStmt = pf.connection.prepareStatement(
                "SELECT name, country FROM asn WHERE asn = ?")) {
            selectStmt.setInt(1, asn);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                // ASN exists, update name and check country
                String existingCountry = rs.getString("country");
                String existingName = rs.getString("name");
                boolean needUpdate = false;
                if (existingName == null || !existingName.equalsIgnoreCase(name)) {
                    pf.logger.warn("Name mismatch for ASN {}: database has {}, asnames has {}", asn, existingName, name);
                    needUpdate = true;
                }
                if (!needUpdate && (existingCountry == null || !existingCountry.equalsIgnoreCase(country))) {
                    pf.logger.warn("Country mismatch for ASN {}: database has {}, asnames has {}", asn, existingCountry, country);
                    needUpdate = true;
                }
                if (needUpdate) {
                    try (PreparedStatement updateStmt = pf.connection.prepareStatement(
                            "UPDATE asn SET name = ?, country = ? WHERE asn = ?")) {
                        updateStmt.setString(1, name);
                        updateStmt.setString(2, country);
                        updateStmt.setInt(3, asn);
                        updateStmt.executeUpdate();
                    } catch (SQLException ex) {
                        pf.logger.warn("Can't update ASN {}, SQLException {}", asn, ex);
                    }
                }
            } else {
                // ASN does not exist, insert new record
                String identifier = UUID.randomUUID().toString();
                String date = LocalDate.now().format(Config.getDateFormatter());
                pf.logger.warn("Adding new ASN {} from asnames, not found in database: country={}, name={}, identifier={}",
                        asn, country, name, identifier);
                try (PreparedStatement insertStmt = pf.connection.prepareStatement(
                        "INSERT INTO asn (coordinator, country, asn, date, identifier, name) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insertStmt.setString(1, "wll");
                    insertStmt.setString(2, country);
                    insertStmt.setInt(3, asn);
                    insertStmt.setString(4, date);
                    insertStmt.setString(5, identifier);
                    insertStmt.setString(6, name);
                    insertStmt.executeUpdate();
                } catch (SQLException ex) {
                    pf.logger.warn("Can't insert ASN {} [wll], SQLException {}", asn, ex);
                }
            }
        } catch (SQLException ex) {
            pf.logger.error("SQLException {}", ex);
        }
    }

}
