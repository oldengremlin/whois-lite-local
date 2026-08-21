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
package net.ukrcom.whoislitelocal.retrieve;

import inet.ipaddr.AddressStringException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.IncompatibleAddressException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;
import static net.ukrcom.whoislitelocal.parse.ParseExtended.IP2BigInteger;
import static net.ukrcom.whoislitelocal.parse.ParseExtended.IPBigIntegerWithZero;

/**
 *
 * @author olden
 */
@Slf4j
public class RetrieveNetworkOrigin {

    protected String network;

    public RetrieveNetworkOrigin(String network) {
        this.network = network;
    }

    public RetrieveNetworkOrigin printNetworkOrigin() {
        String stringAddress;
        try {
            IPAddress address = new IPAddressString(this.network).toAddress();
            stringAddress = IPBigIntegerWithZero(IP2BigInteger(address.getLower().toString()).toString());
        } catch (AddressStringException | IncompatibleAddressException | UnknownHostException ex) {
            log.error("Can't parse IP-address {}", this.network);
            return this;
        }

        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            String found = findNetwork(conn, "ipv4", stringAddress);
            if (found == null) {
                found = findNetwork(conn, "ipv6", stringAddress);
            }
            if (found == null) {
                return this;
            }
            this.network = found;
            printRouteNetworkBlock(conn, found);
        } catch (SQLException ex) {
            log.error("Failed to search network for {}", this.network, ex);
        }
        return this;
    }

    /**
     * Finds the allocation containing an address.
     *
     * <p>Allocations never overlap, so the containing block — if there is one —
     * is the last allocation starting at or before the address. Seeking to that
     * one row and checking its upper bound in Java lets the covering index stop
     * after a single entry. Filtering on {@code firstip <= ? AND lastip >= ?}
     * instead made SQLite walk every row satisfying one of the two bounds.
     */
    private String findNetwork(Connection conn, String table, String address) throws SQLException {
        // table is a compile-time constant chosen by the caller, never user input
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT network, lastip FROM " + table
                + " WHERE firstip <= ? ORDER BY firstip DESC LIMIT 1")) {
            stmt.setString(1, address);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String lastip = rs.getString("lastip");
                    if (lastip != null && address.compareTo(lastip) <= 0) {
                        return rs.getString("network");
                    }
                }
            }
        }
        return null;
    }

    private void printRouteNetworkBlock(Connection conn, String network) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key IN ('route', 'route6') AND value = ?")) {
            stmt.setString(1, network);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Config.printBlock(rs.getString("block"));
                }
            }
        }
    }

}
