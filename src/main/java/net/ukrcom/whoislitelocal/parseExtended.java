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

import inet.ipaddr.AddressStringException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.IncompatibleAddressException;
import java.math.BigInteger;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author olden
 */
public class parseExtended extends parseAbstract implements parseInterface, AutoCloseable {

    private final Set<String> coordinators = new HashSet<>();
    private boolean needInitializeTempTables = true;

    @Override
    public void parse(processFiles pf) {
        try {
            if (needInitializeTempTables) {
                // Initialize temporary tables once per process
                pf.connection.createStatement().execute("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_ipv4 (
                        coordinator TEXT NOT NULL,
                        identifier TEXT NOT NULL,
                        network TEXT NOT NULL,
                        UNIQUE(coordinator, identifier, network)
                    )""");
                pf.connection.createStatement().execute("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_ipv6 (
                        coordinator TEXT NOT NULL,
                        identifier TEXT NOT NULL,
                        network TEXT NOT NULL,
                        UNIQUE(coordinator, identifier, network)
                    )""");
                needInitializeTempTables = false;
            } else {
                // Clear temporary tables for this file
                pf.connection.createStatement().execute("DELETE FROM temp_ipv4");
                pf.connection.createStatement().execute("DELETE FROM temp_ipv6");
            }
            coordinators.clear();
            // Parse file
            super.parse(pf);
            // Cleanup outdated networks
            cleanupOutdatedNetworks(pf);
        } catch (SQLException e) {
            pf.logger.error("Failed to process file or cleanup networks", e);
        }
    }

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
        coordinators.add(coordinator);
        try {
            switch (type) {
                case "asn" ->
                    processAsn(pf, coordinator, country, value, date, identifier);
                case "ipv4" ->
                    processIpv4(pf, coordinator, country, value, countOrPrefix, date, identifier);
                case "ipv6" ->
                    processIpv6(pf, coordinator, country, value, countOrPrefix, date, identifier);
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

    private void processAsn(processFiles pf, String coordinator, String country, String value, String date, String identifier) throws
            SQLException {
        int asn = IpUtils.validateAsn(value);
        try (PreparedStatement selectStmt = pf.connection.prepareStatement(
                "SELECT coordinator, identifier FROM asn WHERE asn = ?")) {
            selectStmt.setInt(1, asn);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                String existingCoordinator = rs.getString("coordinator");
                String existingIdentifier = rs.getString("identifier");
                if (!coordinator.equals(existingCoordinator) || !identifier.equals(existingIdentifier)) {
                    pf.logger.warn("ASN {} coordinator or identifier changed: old=[{}, {}], new=[{}, {}]",
                            asn, existingCoordinator, existingIdentifier, coordinator, identifier);
                    cleanupNetworks(pf, existingCoordinator, existingIdentifier);
                    try (PreparedStatement updateStmt = pf.connection.prepareStatement(
                            "INSERT OR REPLACE INTO asn (coordinator, country, asn, date, identifier, name) VALUES (?, ?, ?, ?, ?, ?)")) {
                        updateStmt.setString(1, coordinator);
                        updateStmt.setString(2, country);
                        updateStmt.setInt(3, asn);
                        updateStmt.setString(4, date);
                        updateStmt.setString(5, identifier);
                        updateStmt.setNull(6, java.sql.Types.VARCHAR);
                        updateStmt.executeUpdate();
                    }
                }
            } else {
                try (PreparedStatement insertStmt = pf.connection.prepareStatement(
                        "INSERT INTO asn (coordinator, country, asn, date, identifier, name) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insertStmt.setString(1, coordinator);
                    insertStmt.setString(2, country);
                    insertStmt.setInt(3, asn);
                    insertStmt.setString(4, date);
                    insertStmt.setString(5, identifier);
                    insertStmt.setNull(6, java.sql.Types.VARCHAR);
                    insertStmt.executeUpdate();
                }
            }
        }
    }

    private void processIpv4(processFiles pf, String coordinator, String country, String value, String countOrPrefix, String date, String identifier) throws
            UnknownHostException, SQLException {
        String ipv4Network = IpUtils.ipv4ToCidr(value, Integer.parseInt(countOrPrefix));

        String firstip = null;
        String lastip = null;
        try {
            IPAddress ipv4Address = new IPAddressString(ipv4Network).toAddress();
            firstip = IPBigIntegerWithZero(IP2BigInteger(ipv4Address.getLower().toString()).toString());
            lastip = IPBigIntegerWithZero(IP2BigInteger(ipv4Address.getUpper().toString()).toString());
        } catch (AddressStringException | IncompatibleAddressException e) {
            pf.logger.error("Invalid network {} : {}", ipv4Network, e);
        }

        try (PreparedStatement tempStmt = pf.connection.prepareStatement(
                "INSERT OR IGNORE INTO temp_ipv4 (coordinator, identifier, network) VALUES (?, ?, ?)")) {
            tempStmt.setString(1, coordinator);
            tempStmt.setString(2, identifier);
            tempStmt.setString(3, ipv4Network);
            tempStmt.addBatch();
            tempStmt.executeBatch();
        }
        try (PreparedStatement mainStmt = pf.connection.prepareStatement(
                "INSERT OR IGNORE INTO ipv4 (coordinator, country, network, date, identifier, firstip, lastip) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            mainStmt.setString(1, coordinator);
            mainStmt.setString(2, country);
            mainStmt.setString(3, ipv4Network);
            mainStmt.setString(4, date);
            mainStmt.setString(5, identifier);
            mainStmt.setString(6, firstip);
            mainStmt.setString(7, lastip);
            mainStmt.addBatch();
            mainStmt.executeBatch();
        }
    }

    private void processIpv6(processFiles pf, String coordinator, String country, String value, String countOrPrefix, String date, String identifier) throws
            UnknownHostException, SQLException {
        String ipv6Network = IpUtils.ipv6ToCidr(value, Integer.parseInt(countOrPrefix));

        String firstip = null;
        String lastip = null;
        try {
            IPAddress ipv6Address = new IPAddressString(ipv6Network).toAddress();
            firstip = IPBigIntegerWithZero(IP2BigInteger(ipv6Address.getLower().toString()).toString());
            lastip = IPBigIntegerWithZero(IP2BigInteger(ipv6Address.getUpper().toString()).toString());
        } catch (AddressStringException | IncompatibleAddressException e) {
            pf.logger.error("Invalid network {} : {}", ipv6Network, e);
        }

        try (PreparedStatement tempStmt = pf.connection.prepareStatement(
                "INSERT OR IGNORE INTO temp_ipv6 (coordinator, identifier, network) VALUES (?, ?, ?)")) {
            tempStmt.setString(1, coordinator);
            tempStmt.setString(2, identifier);
            tempStmt.setString(3, ipv6Network);
            tempStmt.addBatch();
            tempStmt.executeBatch();
        }
        try (PreparedStatement mainStmt = pf.connection.prepareStatement(
                "INSERT OR IGNORE INTO ipv6 (coordinator, country, network, date, identifier, firstip, lastip) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            mainStmt.setString(1, coordinator);
            mainStmt.setString(2, country);
            mainStmt.setString(3, ipv6Network);
            mainStmt.setString(4, date);
            mainStmt.setString(5, identifier);
            mainStmt.setString(6, firstip);
            mainStmt.setString(7, lastip);
            mainStmt.addBatch();
            mainStmt.executeBatch();
        }
    }

    private void cleanupNetworks(processFiles pf, String coordinator, String identifier) throws
            SQLException {
        try (PreparedStatement deleteIpv4Stmt = pf.connection.prepareStatement(
                "DELETE FROM ipv4 WHERE coordinator = ? AND identifier = ?")) {
            deleteIpv4Stmt.setString(1, coordinator);
            deleteIpv4Stmt.setString(2, identifier);
            int deleted = deleteIpv4Stmt.executeUpdate();
            if (deleted > 0) {
                pf.logger.info("Deleted {} ipv4 networks for coordinator={}, identifier={}", deleted, coordinator, identifier);
            }
        }
        try (PreparedStatement deleteIpv6Stmt = pf.connection.prepareStatement(
                "DELETE FROM ipv6 WHERE coordinator = ? AND identifier = ?")) {
            deleteIpv6Stmt.setString(1, coordinator);
            deleteIpv6Stmt.setString(2, identifier);
            int deleted = deleteIpv6Stmt.executeUpdate();
            if (deleted > 0) {
                pf.logger.info("Deleted {} ipv6 networks for coordinator={}, identifier={}", deleted, coordinator, identifier);
            }
        }
    }

    private void cleanupOutdatedNetworks(processFiles pf) throws SQLException {
        if (coordinators.isEmpty()) {
            pf.logger.info("No coordinators processed, skipping outdated networks cleanup");
            return;
        }
        for (String coordinator : coordinators) {
            pf.logger.info("Checking outdated networks for coordinator {}", coordinator);
            try (PreparedStatement deleteIpv4Stmt = pf.connection.prepareStatement(
                    "DELETE FROM ipv4 WHERE coordinator = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM temp_ipv4 t WHERE t.coordinator = ipv4.coordinator AND t.identifier = ipv4.identifier AND t.network = ipv4.network)")) {
                deleteIpv4Stmt.setString(1, coordinator);
                int deleted = deleteIpv4Stmt.executeUpdate();
                if (deleted > 0) {
                    pf.logger.info("Deleted {} outdated ipv4 networks for coordinator {}", deleted, coordinator);
                }
            }
            try (PreparedStatement deleteIpv6Stmt = pf.connection.prepareStatement(
                    "DELETE FROM ipv6 WHERE coordinator = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM temp_ipv6 t WHERE t.coordinator = ipv6.coordinator AND t.identifier = ipv6.identifier AND t.network = ipv6.network)")) {
                deleteIpv6Stmt.setString(1, coordinator);
                int deleted = deleteIpv6Stmt.executeUpdate();
                if (deleted > 0) {
                    pf.logger.info("Deleted {} outdated ipv6 networks for coordinator {}", deleted, coordinator);
                }
            }
        }
        // Clear temporary tables (but don't drop them)
        pf.connection.createStatement().execute("DELETE FROM temp_ipv4");
        pf.connection.createStatement().execute("DELETE FROM temp_ipv6");
    }

    public static BigInteger IP2BigInteger(String ipAddress) throws
            UnknownHostException {
//        ipAddress = ipAddress.replaceFirst("/\\d+$", "");
//        InetAddress ipInetAddress = InetAddress.getByName(ipAddress);
//        byte[] ipBytes = ipInetAddress.getAddress();
//        return new BigInteger(1, ipBytes); // 1 означає додатне число
        IPAddressString ipStr = new IPAddressString(ipAddress);
        IPAddress ip = ipStr.getAddress();
        if (ip == null) {
            return null;
        }
        return ip.getValue();
    }

    public static String IPBigIntegerWithZero(String strIPBigInt) {
        StringBuilder resultStr = new StringBuilder();
        for (int i = 0; i < 40 - strIPBigInt.length(); i++) {
            resultStr.append("0");
        }
        resultStr.append(strIPBigInt);
        return resultStr.toString();
    }

    @Override
    public void close() throws Exception {
        needInitializeTempTables = false;
    }
}
