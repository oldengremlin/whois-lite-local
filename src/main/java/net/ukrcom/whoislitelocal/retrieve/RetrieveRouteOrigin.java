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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;

/**
 *
 * @author olden
 */
@Slf4j
public class RetrieveRouteOrigin {

    protected String origin;

    public RetrieveRouteOrigin(String origin) {
        this.origin = origin;
    }

    /**
     * Prints every route/route6 object announced by this origin. A single join
     * replaces the former loop that reopened a database connection for each
     * route it had just listed.
     */
    public RetrieveRouteOrigin printRouteOrigin() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
             PreparedStatement selectStmt = conn.prepareStatement(
                     "SELECT r.block FROM rpsl_origin o "
                     + "JOIN rpsl r ON r.key IN ('route', 'route6') AND r.value = o.route "
                     + "WHERE o.origin = ? ORDER BY o.route")) {
            selectStmt.setString(1, this.origin);
            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    Config.printBlock(rs.getString("block"));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to print RouteOrigin", ex);
        }
        return this;
    }

}
