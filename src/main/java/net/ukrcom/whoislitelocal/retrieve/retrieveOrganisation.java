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
import net.ukrcom.whoislitelocal.Config;

/**
 *
 * @author olden
 */
public class retrieveOrganisation extends retrieveAutNum {

    public retrieveOrganisation(String autNum) {
        super(autNum);
    }

    public retrieveAutNum Load() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
             PreparedStatement selectStmt = conn.prepareStatement(
                     "SELECT block FROM rpsl WHERE key=? AND value=?");) {

            selectStmt.setString(1, "aut-num");
            selectStmt.setString(2, this.autNum);
            ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                this.autNumBlock = rs.getString("block");
                System.out.println(getAsn(this.autNum));
            }

        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve AutNum", ex);
        }
        return this;
    }

}
