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

import ch.qos.logback.classic.Logger;
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
import net.ukrcom.whoislitelocal.Config;
import static net.ukrcom.whoislitelocal.parse.parseExtended.IP2BigInteger;
import static net.ukrcom.whoislitelocal.parse.parseExtended.IPBigIntegerWithZero;

/**
 *
 * @author olden
 */
public class retrieveNetworkOrigin {

    protected String network;
    protected String origin;
    protected String originRoute;
    protected String originBlock;
    private final Logger logger;

    public retrieveNetworkOrigin(String network) {
        this.network = network;
        this.logger = Config.getLogger();
    }

    public retrieveNetworkOrigin printNetworkOrigin() {

        this.originBlock = getRouteNetworkBlock();
        System.out.println(this.originBlock);

        boolean networkFound = false;
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT origin FROM rpsl_origin WHERE route=? ORDER BY origin")) {
                selectStmt.setString(1, this.network);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    networkFound = true;
                    this.origin = rs.getString("origin");

                    System.out.println(this.network.contains(":")
                            ? "route6:"
                            : "route: "
                            + "         "
                            + this.network);
                    System.out.println("origin:         " + this.origin);
                    System.out.println();
                    new retrieveAutNum(this.origin).printAutNum();
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to print RouteOrigin", ex);
        }
        if (networkFound) {
            return this;
        }

        try {
            IPAddress ipv4Address = new IPAddressString(this.network).toAddress();
            String stringAddress = IPBigIntegerWithZero(IP2BigInteger(ipv4Address.getLower().toString()).toString());

            try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
                 PreparedStatement selectStmt = conn.prepareStatement(
                         "SELECT network FROM ipv4 WHERE firstip<=? AND lastip>=? "
                         + "UNION ALL "
                         + "SELECT network FROM ipv6 WHERE firstip<=? AND lastip>=?")) {
                selectStmt.setString(1, stringAddress);
                selectStmt.setString(2, stringAddress);
                selectStmt.setString(3, stringAddress);
                selectStmt.setString(4, stringAddress);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.network = rs.getString("network");
                    this.originBlock = getRouteNetworkBlock();
                    System.out.println(this.originBlock);
                }
            } catch (SQLException ex) {
                this.logger.error("Failed to search network for RouteOrigin: {}", ipv4Address.toString(), ex);
            }

        } catch (AddressStringException | IncompatibleAddressException | UnknownHostException ex) {
            this.logger.error("Can't parse IP-address {}", this.network);
        }

        return this;
    }

    private String getRouteNetworkBlock() {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key IN (\"route\", \"route6\") AND value=?")) {
                selectStmt.setString(1, this.network);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    retVal.append(rs.getString("block"));
                    retVal.append("\n");
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve NetworkOrigin", ex);
        }
        return retVal.toString();
    }

}
