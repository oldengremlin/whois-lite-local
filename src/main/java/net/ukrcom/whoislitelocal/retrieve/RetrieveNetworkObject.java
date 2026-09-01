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

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;
import net.ukrcom.whoislitelocal.IpUtils;

/**
 * Looks up the address-bearing RPSL objects — route, route6, inetnum, inet6num —
 * that cover a given address or prefix.
 *
 * <p>The query argument does not have to name an object exactly. Asking for
 * {@code 2a04:42c1:3:c::3} finds every route6 and inet6num containing it,
 * including the enclosing {@code 2a04:42c0::/29}.
 *
 * @author olden
 */
@Slf4j
public class RetrieveNetworkObject {

    /**
     * One RPSL object found by the lookup, kept with its mask length so results
     * can be printed from the largest enclosing block down to the most specific.
     */
    private record Match(String key, String value, int masklen) {

    }

    private final String objectType;
    private final String query;

    public RetrieveNetworkObject(String objectType, String query) {
        this.objectType = objectType;
        this.query = query;
    }

    public RetrieveNetworkObject print() {
        IPAddress address = new IPAddressString(this.query.trim()).getAddress();
        if (address == null) {
            log.error("Not a valid address or prefix: {}", this.query);
            return this;
        }
        int version = address.isIPv4() ? 4 : 6;
        if (!versionMatches(version)) {
            log.error("{} is an IPv{} address, but --retrieve-{} expects IPv{}",
                    this.query, version, this.objectType, version == 4 ? 6 : 4);
            return this;
        }

        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            List<Match> matches = findCovering(conn, address, version);
            if (matches.isEmpty()) {
                log.info("No {} object covers {}", this.objectType, this.query);
                return this;
            }
            // Least specific first: the enclosing allocation gives context before
            // the more specific objects inside it.
            matches.sort((a, b) -> Integer.compare(a.masklen(), b.masklen()));

            for (Match match : matches) {
                String block = printObject(conn, match);
                printOrg(conn, block);
                if (isInetnum()) {
                    printContainedRoutes(conn, match, version);
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to retrieve {} for {}", this.objectType, this.query, ex);
        }
        return this;
    }

    private boolean isInetnum() {
        return "inetnum".equals(this.objectType) || "inet6num".equals(this.objectType);
    }

    private boolean versionMatches(int version) {
        boolean wantsV6 = "route6".equals(this.objectType) || "inet6num".equals(this.objectType);
        return wantsV6 == (version == 6);
    }

    /**
     * Finds every object of the requested type whose range contains the address.
     *
     * <p>These objects overlap by design — a /29 and a /48 inside it both exist —
     * so a {@code firstip <= ? AND lastip >= ?} predicate would have to scan a
     * large part of the table. Instead the address is masked to each possible
     * prefix length and looked up exactly, which is one index seek per length.
     */
    private List<Match> findCovering(Connection conn, IPAddress address, int version) throws SQLException {
        BigInteger value = address.getLower().getValue();
        int bits = IpUtils.addressBits(address);
        List<Match> matches = new ArrayList<>();

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT value FROM rpsl_net WHERE version = ? AND masklen = ? AND firstip = ? AND key = ?")) {
            for (int masklen = 0; masklen <= bits; masklen++) {
                String network = IpUtils.padIpDecimal(IpUtils.networkAddress(value, bits, masklen));
                stmt.setInt(1, version);
                stmt.setInt(2, masklen);
                stmt.setString(3, network);
                stmt.setString(4, this.objectType);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        matches.add(new Match(this.objectType, rs.getString("value"), masklen));
                    }
                }
            }
        }
        return matches;
    }

    private String printObject(Connection conn, Match match) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key = ? AND value = ?")) {
            stmt.setString(1, match.key());
            stmt.setString(2, match.value());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String block = rs.getString("block");
                    Config.printBlock(block);
                    System.out.println();
                    return block;
                }
            }
        }
        return null;
    }

    /**
     * Prints the organisation objects referenced by an org: attribute of the block.
     */
    private void printOrg(Connection conn, String block) throws SQLException {
        if (block == null) {
            return;
        }
        Set<String> orgs = new LinkedHashSet<>();
        for (String line : block.split("\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && "org:".equals(parts[0])) {
                orgs.add(parts[1].trim());
            }
        }
        if (orgs.isEmpty()) {
            return;
        }
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE key = 'organisation' AND value = ?")) {
            for (String org : orgs) {
                stmt.setString(1, org);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Config.printBlock(rs.getString("block"));
                        System.out.println();
                    }
                }
            }
        }
    }

    /**
     * Prints the route/route6 objects lying inside an inetnum or inet6num.
     *
     * <p>Bounded on both ends, so this one is a genuine range seek on
     * (version, firstip, lastip) rather than a scan.
     */
    private void printContainedRoutes(Connection conn, Match match, int version) throws
            SQLException {
        String routeKey = version == 4 ? "route" : "route6";
        String firstip = null;
        String lastip = null;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT firstip, lastip FROM rpsl_net WHERE key = ? AND value = ? ORDER BY firstip")) {
            stmt.setString(1, match.key());
            stmt.setString(2, match.value());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // An inetnum range can span several blocks; take the outer bounds
                    String f = rs.getString("firstip");
                    String l = rs.getString("lastip");
                    if (firstip == null || f.compareTo(firstip) < 0) {
                        firstip = f;
                    }
                    if (lastip == null || l.compareTo(lastip) > 0) {
                        lastip = l;
                    }
                }
            }
        }
        if (firstip == null) {
            return;
        }
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT r.block FROM rpsl_net n "
                + "JOIN rpsl r ON r.key = n.key AND r.value = n.value "
                + "WHERE n.version = ? AND n.key = ? AND n.firstip >= ? AND n.lastip <= ? "
                + "ORDER BY n.firstip, n.masklen")) {
            stmt.setInt(1, version);
            stmt.setString(2, routeKey);
            stmt.setString(3, firstip);
            stmt.setString(4, lastip);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Config.printBlock(rs.getString("block"));
                    System.out.println();
                }
            }
        }
    }

}
