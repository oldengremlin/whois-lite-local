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
public class retrieveAutNum {

    protected String autNum;
    protected String autNumBlock;
    protected final Logger logger;

    public retrieveAutNum(String autNum) {
        this.autNum = autNum;
        this.logger = Config.getLogger();
    }

    public retrieveAutNum printAutNum() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
             PreparedStatement selectStmt = conn.prepareStatement(
                     "SELECT block FROM rpsl WHERE key=? AND value=?");) {

            selectStmt.setString(1, "aut-num");
            selectStmt.setString(2, this.autNum);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                this.autNumBlock = rs.getString("block");
                System.out.println(this.autNumBlock);
                System.out.println(getAsn(this.autNum));
            }

        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve AutNum", ex);
        }
        return this;
    }

    public retrieveAutNum printOrg() {
        if (autNumBlock != null) {
            autNumBlock.lines().forEach(line -> {
                String[] parts = line.split("\\s+", 2);
                if (!(parts.length < 2)) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    if (key.equals("org:")) {
                        System.out.println(getOrg(value));
                    }
                }
            });
        }
        return this;
    }

    private String getOrg(String org) {
        StringBuilder retVal = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
             PreparedStatement selectStmt = conn.prepareStatement(
                     "SELECT block FROM rpsl WHERE key=? AND value=?");) {

            selectStmt.setString(1, "organisation");
            selectStmt.setString(2, org);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                retVal.append(rs.getString("block"));
                retVal.append("\n");
            }

        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve Org", ex);
        }
        return retVal.toString();
    }

    protected String getAsn(String as) {
        StringBuilder retVal = new StringBuilder();
        String asNum = as.replaceFirst("^[Aa][Ss]", "");
        Integer asn = Integer.valueOf(asNum);

        try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
             PreparedStatement selectStmt = conn.prepareStatement(
                     "SELECT country, name FROM asn WHERE asn=?");) {
            selectStmt.setInt(1, asn);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                retVal.append("as-num:         ");
                retVal.append(as.toUpperCase());
                retVal.append("\ncountry:        ");
                retVal.append(rs.getString("country"));
                retVal.append("\nas-name:        ");
                retVal.append(rs.getString("name"));
                retVal.append("\n");
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve Asn", ex);
        }
        return retVal.toString();
    }
}
