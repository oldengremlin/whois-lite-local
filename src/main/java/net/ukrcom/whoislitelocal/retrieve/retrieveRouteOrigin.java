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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import net.ukrcom.whoislitelocal.Config;

/**
 *
 * @author olden
 */
public class retrieveRouteOrigin {

    protected String origin;
    protected String originRoute;
    protected String originBlock;
    private final Logger logger;

    public retrieveRouteOrigin(String origin) {
        this.origin = origin;
        this.logger = Config.getLogger();
    }

    public retrieveRouteOrigin printRouteOrigin() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT route FROM rpsl_origin WHERE origin=? ORDER BY route")) {
                selectStmt.setString(1, this.origin);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.originRoute = rs.getString("route");
                    this.originBlock = getRouteOriginBlock();
                    System.out.println(this.originBlock);
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to print RouteOrigin", ex);
        }
        return this;
    }

    private String getRouteOriginBlock() {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key IN (\"route\", \"route6\") AND value=?")) {
                selectStmt.setString(1, this.originRoute);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    retVal.append(rs.getString("block"));
                    retVal.append("\n");
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve RouteOrigin", ex);
        }
        return retVal.toString();
    }

}
