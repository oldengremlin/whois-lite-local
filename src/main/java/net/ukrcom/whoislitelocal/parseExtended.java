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

import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 *
 * @author olden
 */
public class parseExtended extends parseAbstract implements parseInterface {

    @Override
    public void store(processFiles pf) {
        String[] fields = this.line.split("\\|");
        if (fields.length < 8 || !fields[6].equals("allocated") || fields[1].equals("*")) {
            return; // Skip non-allocated or wildcard country
        }

        String coordinator = fields[0];
        String country = fields[1];
        String type = fields[2];
        String value = fields[3];
        String countOrPrefix = fields[4];
        String date = fields[5];
        String identifier = fields[7];

        try {
            switch (type) {
                case "asn" -> {
                    int asn = IpUtils.validateAsn(value);
                    try (PreparedStatement stmt = pf.connection.prepareStatement(
                            "INSERT OR IGNORE INTO asn (coordinator, country, asn, date, identifier, name) VALUES (?, ?, ?, ?, ?, ?)")) {
                        stmt.setString(1, coordinator);
                        stmt.setString(2, country);
                        stmt.setInt(3, asn);
                        stmt.setString(4, date);
                        stmt.setString(5, identifier);
                        stmt.setNull(6, java.sql.Types.VARCHAR); // name is null for extended files
                        stmt.executeUpdate();
                    }
                }
                case "ipv4" -> {
                    String ipv4Network = IpUtils.ipv4ToCidr(value, Integer.parseInt(countOrPrefix));
                    try (PreparedStatement stmt = pf.connection.prepareStatement(
                            "INSERT OR IGNORE INTO ipv4 (coordinator, country, network, date, identifier) VALUES (?, ?, ?, ?, ?)")) {
                        stmt.setString(1, coordinator);
                        stmt.setString(2, country);
                        stmt.setString(3, ipv4Network);
                        stmt.setString(4, date);
                        stmt.setString(5, identifier);
                        stmt.executeUpdate();
                    }
                }
                case "ipv6" -> {
                    String ipv6Network = IpUtils.ipv6ToCidr(value, Integer.parseInt(countOrPrefix));
                    try (PreparedStatement stmt = pf.connection.prepareStatement(
                            "INSERT OR IGNORE INTO ipv6 (coordinator, country, network, date, identifier) VALUES (?, ?, ?, ?, ?)")) {
                        stmt.setString(1, coordinator);
                        stmt.setString(2, country);
                        stmt.setString(3, ipv6Network);
                        stmt.setString(4, date);
                        stmt.setString(5, identifier);
                        stmt.executeUpdate();
                    }
                }
                default ->
                    pf.logger.warn("Unknown type: {}", type);
            }
        } catch (NumberFormatException e) {
            pf.logger.error("Failed to process line, NumberFormatException: {}", line, e);
        } catch (SQLException e) {
            pf.logger.error("Failed to process line, SQLException: {}", line, e);
        } catch (UnknownHostException e) {
            pf.logger.error("Failed to process line, UnknownHostException: {}", line, e);
        }
    }

}
