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
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.IpUtils;

/**
 *
 * @author olden
 */
@Slf4j
public class ParseExtended extends ParseAbstract implements ParseInterface {

    private static final int BATCH_SIZE = 1000;

    private final Set<String> coordinators = new HashSet<>();
    private boolean needInitializeTempTables = true;

    // Prepared once per file rather than once per row: preparing and closing four
    // statements for every line dominated the cost of parsing extended files.
    private PreparedStatement tempIpv4Stmt, mainIpv4Stmt;
    private PreparedStatement tempIpv6Stmt, mainIpv6Stmt;
    private int ipv4BatchCount = 0;
    private int ipv6BatchCount = 0;

    @Override
    public void parse(ProcessFiles pf) {
        try {
            synchronized (pf.connection) {
                try (var stmt = pf.connection.createStatement()) {
                    if (needInitializeTempTables) {
                        stmt.execute("""
                            CREATE TEMPORARY TABLE IF NOT EXISTS temp_ipv4 (
                                coordinator TEXT NOT NULL,
                                identifier TEXT NOT NULL,
                                network TEXT NOT NULL,
                                UNIQUE(coordinator, identifier, network)
                            )""");
                        stmt.execute("""
                            CREATE TEMPORARY TABLE IF NOT EXISTS temp_ipv6 (
                                coordinator TEXT NOT NULL,
                                identifier TEXT NOT NULL,
                                network TEXT NOT NULL,
                                UNIQUE(coordinator, identifier, network)
                            )""");
                        needInitializeTempTables = false;
                    } else {
                        stmt.execute("DELETE FROM temp_ipv4");
                        stmt.execute("DELETE FROM temp_ipv6");
                    }
                }
            }
            coordinators.clear();

            synchronized (pf.connection) {
                tempIpv4Stmt = pf.connection.prepareStatement(
                        "INSERT OR IGNORE INTO temp_ipv4 (coordinator, identifier, network) VALUES (?, ?, ?)");
                mainIpv4Stmt = pf.connection.prepareStatement(
                        "INSERT OR IGNORE INTO ipv4 (coordinator, country, network, date, identifier, firstip, lastip)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)");
                tempIpv6Stmt = pf.connection.prepareStatement(
                        "INSERT OR IGNORE INTO temp_ipv6 (coordinator, identifier, network) VALUES (?, ?, ?)");
                mainIpv6Stmt = pf.connection.prepareStatement(
                        "INSERT OR IGNORE INTO ipv6 (coordinator, country, network, date, identifier, firstip, lastip)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)");
            }
            try {
                super.parse(pf);
                synchronized (pf.connection) {
                    flushBatches();
                    cleanupOutdatedNetworks(pf);
                    runIncrementalVacuumSmart(pf);
                }
            } finally {
                synchronized (pf.connection) {
                    closeQuietly(tempIpv4Stmt, mainIpv4Stmt, tempIpv6Stmt, mainIpv6Stmt);
                    tempIpv4Stmt = mainIpv4Stmt = tempIpv6Stmt = mainIpv6Stmt = null;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to process file or cleanup networks", e);
        }
    }

    private void flushBatches() throws SQLException {
        if (ipv4BatchCount > 0) {
            tempIpv4Stmt.executeBatch();
            mainIpv4Stmt.executeBatch();
            ipv4BatchCount = 0;
        }
        if (ipv6BatchCount > 0) {
            tempIpv6Stmt.executeBatch();
            mainIpv6Stmt.executeBatch();
            ipv6BatchCount = 0;
        }
    }

    private static void closeQuietly(PreparedStatement... statements) {
        for (PreparedStatement stmt : statements) {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignore) {
                    // nothing useful to do while unwinding
                }
            }
        }
    }

    @Override
    public void store(ProcessFiles pf) {
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
            // CPU work (IP parsing, validation) happens before acquiring the lock
            switch (type) {
                case "asn" -> {
                    synchronized (pf.connection) {
                        processAsn(pf, coordinator, country, value, date, identifier);
                    }
                }
                case "ipv4" -> {
                    synchronized (pf.connection) {
                        processIpv4(pf, coordinator, country, value, countOrPrefix, date, identifier);
                    }
                }
                case "ipv6" -> {
                    synchronized (pf.connection) {
                        processIpv6(pf, coordinator, country, value, countOrPrefix, date, identifier);
                    }
                }
                default ->
                    log.warn("Unknown type: {}", type);
            }
        } catch (NumberFormatException e) {
            log.error("Failed to process line, NumberFormatException: {}", line, e);
        } catch (SQLException e) {
            log.error("Failed to process line, SQLException: {}", line, e);
        } catch (UnknownHostException e) {
            log.error("Failed to process line, UnknownHostException: {}", line, e);
        }
    }

    private void processAsn(ProcessFiles pf, String coordinator, String country, String value, String date, String identifier) throws
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
                    log.warn("ASN {} coordinator or identifier changed: old=[{}, {}], new=[{}, {}]",
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

    private void processIpv4(ProcessFiles pf, String coordinator, String country, String value, String countOrPrefix, String date, String identifier) throws
            UnknownHostException, SQLException {
        // An extended-file ipv4 count is an address count, not necessarily a power
        // of two, so a delegation can span several CIDR blocks. Deriving a single
        // prefix from log2(count) silently misrepresented those ranges.
        for (IPAddress block : IpUtils.ipv4RangeToCidrBlocks(value, Integer.parseInt(countOrPrefix))) {
            queueNetwork(block, tempIpv4Stmt, mainIpv4Stmt, coordinator, country, date, identifier);
            if (++ipv4BatchCount >= BATCH_SIZE) {
                tempIpv4Stmt.executeBatch();
                mainIpv4Stmt.executeBatch();
                ipv4BatchCount = 0;
            }
        }
    }

    private void processIpv6(ProcessFiles pf, String coordinator, String country, String value, String countOrPrefix, String date, String identifier) throws
            UnknownHostException, SQLException {
        // For ipv6 the field really is a prefix length, so there is exactly one block.
        String ipv6Network = IpUtils.ipv6ToCidr(value, Integer.parseInt(countOrPrefix));
        IPAddress block;
        try {
            block = new IPAddressString(ipv6Network).toAddress();
        } catch (AddressStringException | IncompatibleAddressException e) {
            log.error("Invalid network {} : {}", ipv6Network, e);
            recordStoreError();
            return;
        }
        queueNetwork(block, tempIpv6Stmt, mainIpv6Stmt, coordinator, country, date, identifier);
        if (++ipv6BatchCount >= BATCH_SIZE) {
            tempIpv6Stmt.executeBatch();
            mainIpv6Stmt.executeBatch();
            ipv6BatchCount = 0;
        }
    }

    private void queueNetwork(IPAddress block, PreparedStatement tempStmt, PreparedStatement mainStmt,
            String coordinator, String country, String date, String identifier) throws
            UnknownHostException, SQLException {
        String network = block.toString();
        String firstip = IPBigIntegerWithZero(IP2BigInteger(block.getLower().toString()).toString());
        String lastip = IPBigIntegerWithZero(IP2BigInteger(block.getUpper().toString()).toString());

        tempStmt.setString(1, coordinator);
        tempStmt.setString(2, identifier);
        tempStmt.setString(3, network);
        tempStmt.addBatch();

        mainStmt.setString(1, coordinator);
        mainStmt.setString(2, country);
        mainStmt.setString(3, network);
        mainStmt.setString(4, date);
        mainStmt.setString(5, identifier);
        mainStmt.setString(6, firstip);
        mainStmt.setString(7, lastip);
        mainStmt.addBatch();
    }

    private void cleanupNetworks(ProcessFiles pf, String coordinator, String identifier) throws
            SQLException {
        try (PreparedStatement deleteIpv4Stmt = pf.connection.prepareStatement(
                "DELETE FROM ipv4 WHERE coordinator = ? AND identifier = ?")) {
            deleteIpv4Stmt.setString(1, coordinator);
            deleteIpv4Stmt.setString(2, identifier);
            int deleted = deleteIpv4Stmt.executeUpdate();
            if (deleted > 0) {
                log.info("Deleted {} ipv4 networks for coordinator={}, identifier={}", deleted, coordinator, identifier);
            }
        }
        try (PreparedStatement deleteIpv6Stmt = pf.connection.prepareStatement(
                "DELETE FROM ipv6 WHERE coordinator = ? AND identifier = ?")) {
            deleteIpv6Stmt.setString(1, coordinator);
            deleteIpv6Stmt.setString(2, identifier);
            int deleted = deleteIpv6Stmt.executeUpdate();
            if (deleted > 0) {
                log.info("Deleted {} ipv6 networks for coordinator={}, identifier={}", deleted, coordinator, identifier);
            }
        }
    }

    private void cleanupOutdatedNetworks(ProcessFiles pf) throws SQLException {
        if (coordinators.isEmpty()) {
            log.info("No coordinators processed, skipping outdated networks cleanup");
            return;
        }
        for (String coordinator : coordinators) {
            log.info("Checking outdated networks for coordinator {}", coordinator);
            try (PreparedStatement deleteIpv4Stmt = pf.connection.prepareStatement(
                    "DELETE FROM ipv4 WHERE coordinator = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM temp_ipv4 t WHERE t.coordinator = ipv4.coordinator AND t.identifier = ipv4.identifier AND t.network = ipv4.network)")) {
                deleteIpv4Stmt.setString(1, coordinator);
                int deleted = deleteIpv4Stmt.executeUpdate();
                if (deleted > 0) {
                    log.info("Deleted {} outdated ipv4 networks for coordinator {}", deleted, coordinator);
                }
            }
            try (PreparedStatement deleteIpv6Stmt = pf.connection.prepareStatement(
                    "DELETE FROM ipv6 WHERE coordinator = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM temp_ipv6 t WHERE t.coordinator = ipv6.coordinator AND t.identifier = ipv6.identifier AND t.network = ipv6.network)")) {
                deleteIpv6Stmt.setString(1, coordinator);
                int deleted = deleteIpv6Stmt.executeUpdate();
                if (deleted > 0) {
                    log.info("Deleted {} outdated ipv6 networks for coordinator {}", deleted, coordinator);
                }
            }
        }
        // Clear temporary tables (but don't drop them)
        try (var stmt = pf.connection.createStatement()) {
            stmt.execute("DELETE FROM temp_ipv4");
            stmt.execute("DELETE FROM temp_ipv6");
        }
    }

    public static BigInteger IP2BigInteger(String ipAddress) throws
            UnknownHostException {
        IPAddressString ipStr = new IPAddressString(ipAddress);
        IPAddress ip = ipStr.getAddress();
        if (ip == null) {
            return null;
        }
        return ip.getValue();
    }

    /**
     * Left-pads the decimal form of an address to a fixed width so that the
     * TEXT columns firstip/lastip compare correctly with &lt;= and &gt;=.
     */
    public static String IPBigIntegerWithZero(String strIPBigInt) {
        int pad = 40 - strIPBigInt.length();
        return pad > 0 ? "0".repeat(pad) + strIPBigInt : strIPBigInt;
    }
}
