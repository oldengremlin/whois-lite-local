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

    /** One RPSL object found by the lookup, with the block bounds it matched on. */
    private record Match(String key, String value, String firstip, String lastip) {

    }

    /**
     * Cap on how many route objects are listed inside one inetnum. Without it, an
     * address that only the IANA 0.0.0.0/0 placeholder covers would print every
     * route object in the database.
     */
    private static final int MAX_CONTAINED_ROUTES = 500;

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
            List<Match> matches = findMostSpecific(conn, address, version);
            if (matches.isEmpty()) {
                log.info("No {} object covers {}", this.objectType, this.query);
                return this;
            }
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

    private boolean coversWholeAddressSpace(Match match, int version) {
        int bits = version == 4 ? 32 : 128;
        BigInteger max = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        return match.firstip().equals(IpUtils.padIpDecimal(BigInteger.ZERO))
                && match.lastip().equals(IpUtils.padIpDecimal(max));
    }

    private boolean isInetnum() {
        return "inetnum".equals(this.objectType) || "inet6num".equals(this.objectType);
    }

    private boolean versionMatches(int version) {
        boolean wantsV6 = "route6".equals(this.objectType) || "inet6num".equals(this.objectType);
        return wantsV6 == (version == 6);
    }

    /**
     * Finds the narrowest object of the requested type that covers the address.
     *
     * <p>Returning every covering object is not useful: the RIPE database holds a
     * placeholder inetnum for 0.0.0.0/0, so every IPv4 query would drag in "the
     * whole IPv4 address space" along with whatever lies inside it. Whois answers
     * with the most specific match, and so does this.
     *
     * <p>Finding it by {@code firstip <= ? AND lastip >= ? ORDER BY firstip DESC
     * LIMIT 1} is correct but scans the index backwards, all the way to the
     * 0.0.0.0/0 row when nothing narrower covers the address — about 200x slower
     * in that case. Every stored row is a CIDR block, so both of its bounds follow
     * from an address and a prefix length: walking prefix lengths from the most
     * specific down and probing (firstip, lastip) exactly turns the search into at
     * most 33 (or 129) index seeks, and it stops at the first hit.
     */
    private List<Match> findMostSpecific(Connection conn, IPAddress address, int version) throws SQLException {
        BigInteger value = address.getLower().getValue();
        int bits = IpUtils.addressBits(address);
        List<Match> matches = new ArrayList<>();

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT value FROM rpsl_net WHERE version = ? AND firstip = ? AND lastip = ? AND key = ?")) {
            for (int masklen = bits; masklen >= 0; masklen--) {
                BigInteger network = IpUtils.networkAddress(value, bits, masklen);
                BigInteger last = network.add(BigInteger.ONE.shiftLeft(bits - masklen)).subtract(BigInteger.ONE);
                stmt.setInt(1, version);
                stmt.setString(2, IpUtils.padIpDecimal(network));
                stmt.setString(3, IpUtils.padIpDecimal(last));
                stmt.setString(4, this.objectType);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        matches.add(new Match(this.objectType, rs.getString("value"),
                                IpUtils.padIpDecimal(network), IpUtils.padIpDecimal(last)));
                    }
                }
                if (!matches.isEmpty()) {
                    return matches;
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
        // The RIPE database carries a placeholder inetnum for the entire address
        // space, which is what covers an address that is not assigned to anyone.
        // "Everything announced on the internet" is not an answer to any question,
        // so stop rather than list it.
        if (coversWholeAddressSpace(match, version)) {
            log.info("{} is covered only by the whole-address-space placeholder {} — "
                    + "the address is not assigned to any organisation",
                    this.query, match.value());
            return;
        }
        // The bounds of the block the address matched on, not of the whole object:
        // an inetnum spanning several blocks is answered for the one that applies.
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT r.block FROM rpsl_net n "
                + "JOIN rpsl r ON r.key = n.key AND r.value = n.value "
                + "WHERE n.version = ? AND n.key = ? AND n.firstip >= ? AND n.lastip <= ? "
                + "ORDER BY n.firstip LIMIT ?")) {
            stmt.setInt(1, version);
            stmt.setString(2, routeKey);
            stmt.setString(3, match.firstip());
            stmt.setString(4, match.lastip());
            stmt.setInt(5, MAX_CONTAINED_ROUTES + 1);
            int printed = 0;
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (printed == MAX_CONTAINED_ROUTES) {
                        log.warn("More than {} {} objects lie inside {} — listing stopped. "
                                + "Query a narrower prefix to see the rest.",
                                MAX_CONTAINED_ROUTES, routeKey, match.value());
                        break;
                    }
                    Config.printBlock(rs.getString("block"));
                    System.out.println();
                    printed++;
                }
            }
        }
    }

}
