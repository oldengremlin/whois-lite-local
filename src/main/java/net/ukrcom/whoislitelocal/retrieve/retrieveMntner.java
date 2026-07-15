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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;
import org.sqlite.Function;

/**
 *
 * @author olden
 */
@Slf4j
public class retrieveMntner {

    protected String mntner;
    protected String mntnerRoleValue;
    protected String mntnerBlock;

    public retrieveMntner(String mntBy) {
        this.mntner = mntBy;
    }

    public retrieveMntner printMntner() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key = \"mntner\" AND value = ?")) {
                selectStmt.setString(1, this.mntner);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntnerBlock = rs.getString("block");
                    Config.printBlock(this.mntnerBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to retrieve AutNum", ex);
        }
        return this;
    }

    public retrieveMntner printMntnerRole() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT value FROM rpsl_mntby WHERE key=? AND mntby=?")) {
                selectStmt.setString(1, "role");
                selectStmt.setString(2, this.mntner);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntnerRoleValue = rs.getString("value");
                    this.mntnerBlock = getMntnerRoleBlock();
                    Config.printBlock(this.mntnerBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to print MntnerRole", ex);
        }
        return this;
    }

    private String getMntnerRoleBlock() {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key=? AND value=?")) {
                selectStmt.setString(1, "role");
                selectStmt.setString(2, this.mntnerRoleValue);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    retVal.append(rs.getString("block"));
                    retVal.append("\n");
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to retrieve MntnerRole", ex);
        }
        return retVal.toString();
    }

}
