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
package net.ukrcom.whoislitelocal;

import ch.qos.logback.classic.Logger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.sqlite.Function;

/**
 *
 * @author olden
 */
public class retrieveMntBy {

    protected String mntBy;
    protected String mntByBlock;
    private final Logger logger;

    public retrieveMntBy(String mntBy) {
        this.mntBy = mntBy;
        this.logger = Config.getLogger();
    }

    public retrieveMntBy printMntBy() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            registerRegexpFunction(conn);
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT block FROM rpsl WHERE key IN (\"aut-num\", \"as-set\") AND block REGEXP ?")) {
                String escapedMntBy = this.mntBy.replaceAll("[^a-zA-Z0-9-]", "");
                selectStmt.setString(1, "(?m)^mnt-by: *" + escapedMntBy);
                ResultSet rs = selectStmt.executeQuery();
                while (rs.next()) {
                    this.mntByBlock = rs.getString("block");
                    System.out.println(this.mntByBlock);
                    System.out.println();
                }
            }
        } catch (SQLException ex) {
            this.logger.error("Failed to retrieve AutNum", ex);
        }
        return this;
    }

    public static void registerRegexpFunction(Connection conn) throws
            SQLException {
        Function.create(conn, "regexp", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                if (args() != 2) {
                    throw new SQLException("regexp(pattern, string) requires 2 arguments");
                }
                String pattern = value_text(0);
                String text = value_text(1);
                try {
                    result(Pattern.compile(pattern).matcher(text).find() ? 1 : 0);
                } catch (PatternSyntaxException e) {
                    throw new SQLException("Invalid regular expression: " + pattern);
                }
            }
        });
    }

}
