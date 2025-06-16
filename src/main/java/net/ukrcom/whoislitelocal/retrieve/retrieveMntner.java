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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.ukrcom.whoislitelocal.Config;
import org.sqlite.Function;

/**
 *
 * @author olden
 */
public class retrieveMntner {

    protected String mntner;
    protected String mntnerRoleKey;
    protected String mntnerBlock;
    private final Logger logger;

    public retrieveMntner(String mntBy) {
        this.mntner = mntBy;
        this.logger = Config.getLogger();
    }

    public retrieveMntner printMntner() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key = \"mntner\" AND value = ?")) {
                selectStmt.setString(1, this.mntner);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntnerBlock = rs.getString("block");
                    System.out.println(this.mntnerBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve AutNum", ex);
        }
        return this;
    }

    public retrieveMntner printMntnerRole() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT key FROM rpsl_mntby WHERE mntby=? AND value=?")) {
                selectStmt.setString(1, "role");
                selectStmt.setString(2, this.mntner);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntnerRoleKey = rs.getString("key");
                    this.mntnerBlock = getMntnerRoleBlock();
                    System.out.println(this.mntnerBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to print MntnerRole", ex);
        }
        return this;
    }

    private String getMntnerRoleBlock() {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key=? AND value=?")) {
                selectStmt.setString(1, "role");
                selectStmt.setString(2, this.mntnerRoleKey);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    retVal.append(rs.getString("block"));
                    retVal.append("\n");
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve MntnerRole", ex);
        }
        return retVal.toString();
    }

}
