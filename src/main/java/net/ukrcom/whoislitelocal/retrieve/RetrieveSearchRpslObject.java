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
public class RetrieveSearchRpslObject {

    // Wall-clock bound for one whole search, so a pattern that is merely slow
    // across millions of rows ends with an explanation instead of an apparent hang.
    private static final long SEARCH_TIME_BUDGET_NANOS = 120L * 1_000_000_000L;

    private final String pattern;
    private final Pattern javaPattern;
    private final boolean useRegexp;
    private long deadline = Long.MAX_VALUE;

    public RetrieveSearchRpslObject(String pattern) {
        this.pattern = pattern;
        boolean regexp = true;
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex '{}', falling back to literal LIKE search: {}", pattern, e.getMessage());
            compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
            regexp = false;
        }
        this.javaPattern = compiled;
        this.useRegexp = regexp;
    }

    public RetrieveSearchRpslObject search() {
        this.deadline = System.nanoTime() + SEARCH_TIME_BUDGET_NANOS;
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            if (useRegexp) {
                registerRegexpFunction(conn);
            }
            searchAsn(conn);
            searchRpsl(conn);
        } catch (SQLException ex) {
            log.error("Search failed for pattern '{}'", pattern, ex);
        }
        return this;
    }

    private void searchAsn(Connection conn) throws SQLException {
        String sql = useRegexp
                ? "SELECT asn, name FROM asn WHERE name REGEXP ?"
                : "SELECT asn, name FROM asn WHERE name LIKE ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, useRegexp ? pattern : "%" + pattern + "%");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                System.out.println("aut-num:        AS" + rs.getInt("asn"));
                System.out.println("as-name:        " + rs.getString("name"));
                System.out.println();
            }
        }
    }

    private void searchRpsl(Connection conn) throws SQLException {
        String sql = useRegexp
                ? "SELECT block FROM rpsl WHERE block REGEXP ?"
                : "SELECT block FROM rpsl WHERE block LIKE ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, useRegexp ? pattern : "%" + pattern + "%");
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
                result(matchesWithinBudget(text) ? 1 : 0);
            }
        });
    }

    /**
     * Matches one block, first checking that the search as a whole is still
     * within its time budget.
     *
     * <p>The check is per row rather than per character. A per-character budget
     * would also bound a single pathological match, but it costs about 44% on
     * every search, and the classic catastrophic patterns
     * ({@code (a+)+$}, {@code (x+x+)+y}) do not actually blow up on the current
     * JDK regex engine — they complete in single-digit milliseconds. Paying that
     * much on the common path to guard a case that could not be reproduced is
     * the wrong trade; a wall-clock bound costs roughly nothing and still turns
     * a search that grinds across millions of rows into a clear message.
     */
    private boolean matchesWithinBudget(String text) throws SQLException {
        if (System.nanoTime() > deadline) {
            throw new SQLException("Search for '" + pattern + "' exceeded "
                    + (SEARCH_TIME_BUDGET_NANOS / 1_000_000_000L) + " seconds and was stopped. "
                    + "Try a more specific pattern — anchoring it or removing nested quantifiers "
                    + "usually helps.");
        }
        return javaPattern.matcher(text).find();
    }
}
