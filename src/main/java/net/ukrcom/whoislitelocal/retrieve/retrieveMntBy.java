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
public class retrieveMntBy {

    protected String mntBy;
    protected String mntByValue;
    protected String mntByBlock;

    public retrieveMntBy(String mntBy) {
        this.mntBy = mntBy;
    }

    public retrieveMntBy printMntBy() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT value FROM rpsl_mntby WHERE key IN (\"aut-num\", \"as-set\") AND mntby = ?")) {
                selectStmt.setString(1, this.mntBy);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntByValue = rs.getString("value");
                    this.mntByBlock = getMntByBlock();
                    Config.printBlock(this.mntByBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to print MntBy", ex);
        }
        return this;
    }

    private String getMntByBlock() {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key IN (\"aut-num\", \"as-set\") AND value=?")) {
                selectStmt.setString(1, this.mntByValue);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    retVal.append(rs.getString("block"));
                    retVal.append("\n");
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to retrieve MntBy", ex);
        }
        return retVal.toString();
    }

}
