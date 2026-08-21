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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;

/**
 *
 * @author olden
 */
@Slf4j
@RequiredArgsConstructor
public class RetrieveMntBy {

    protected final String mntBy;

    /**
     * Prints the aut-num and as-set objects held by this maintainer. One join
     * replaces the former loop that reopened a database connection for every
     * value it had just listed.
     */
    public RetrieveMntBy printMntBy() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl());
             PreparedStatement selectStmt = conn.prepareStatement(
                     "SELECT r.block FROM rpsl_mntby m "
                     + "JOIN rpsl r ON r.key = m.key AND r.value = m.value "
                     + "WHERE m.mntby = ? AND m.key IN ('aut-num', 'as-set') "
                     + "ORDER BY m.key, m.value")) {
            selectStmt.setString(1, this.mntBy);
            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    Config.printBlock(rs.getString("block"));
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to print MntBy", ex);
        }
        return this;
    }

}
