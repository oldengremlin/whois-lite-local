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
public class retrieveMntBy {

    protected String mntBy;
    protected String mntByKey;
    protected String mntByBlock;
    private final Logger logger;

    public retrieveMntBy(String mntBy) {
        this.mntBy = mntBy;
        this.logger = Config.getLogger();
    }

    public retrieveMntBy printMntBy() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT key FROM rpsl_mntby WHERE mntby IN (\"aut-num\", \"as-set\") AND value = ?")) {
                selectStmt.setString(1, this.mntBy);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntByKey = rs.getString("key");
                    this.mntByBlock = getMntByBlock();
                    Config.printBlock(this.mntByBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to print MntBy", ex);
        }
        return this;
    }

    private String getMntByBlock() {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key IN (\"aut-num\", \"as-set\") AND value=?")) {
                selectStmt.setString(1, this.mntByKey);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    retVal.append(rs.getString("block"));
                    retVal.append("\n");
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve MntBy", ex);
        }
        return retVal.toString();
    }

}
