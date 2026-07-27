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
import java.util.List;
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
public class retrieveSearchRpslObject {

    private final String pattern;
    private final Pattern javaPattern;

    public retrieveSearchRpslObject(String pattern) {
        this.pattern = pattern;
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex '{}', using literal match: {}", pattern, e.getMessage());
            compiled = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        }
        this.javaPattern = compiled;
    }

    public retrieveSearchRpslObject search() {
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            registerRegexpFunction(conn);
            searchAsn(conn);
            searchRpsl(conn);
        } catch (SQLException ex) {
            log.error("Search failed for pattern '{}'", pattern, ex);
        }
        return this;
    }

    private void searchAsn(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT asn, name FROM asn WHERE name REGEXP ?")) {
            stmt.setString(1, pattern);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                System.out.println("aut-num:        AS" + rs.getInt("asn"));
                System.out.println("as-name:        " + rs.getString("name"));
                System.out.println();
            }
        }
    }

    private void searchRpsl(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT block FROM rpsl WHERE block REGEXP ?")) {
            stmt.setString(1, pattern);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                printResult(rs.getString("block"));
            }
        }
    }

    private void printResult(String block) {
        List<String> lines = block.lines().toList();
        if (lines.isEmpty()) {
            return;
        }
        // Always print the object identifier (first line of the RPSL block)
        System.out.println(lines.get(0));
        // Print only matching lines from the remainder of the block
        lines.stream()
                .skip(1)
                .filter(line -> !line.isBlank())
                .filter(line -> javaPattern.matcher(line).find())
                .forEach(System.out::println);
        System.out.println();
    }

    private void registerRegexpFunction(Connection conn) throws SQLException {
        // SQLite: "x REGEXP y" → regexp(y, x), so arg0=pattern, arg1=value.
        // We pre-compile the pattern into javaPattern and use only arg1 (the text).
        Function.create(conn, "REGEXP", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                String text = value_text(1);
                if (text == null) {
                    result(0);
                    return;
                }
                result(javaPattern.matcher(text).find() ? 1 : 0);
            }
        });
    }
}
