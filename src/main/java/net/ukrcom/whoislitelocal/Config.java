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

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author olden
 */
@Slf4j
public class Config {

    private static final String DB_URL = "jdbc:sqlite:whoislitelocal.db";
//    private static final String DB_URL = "jdbc:log4jdbc:sqlite:whoislitelocal.db";
    private static final String PROPERTIES_FILE = "whoislitelocal.properties";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int CONNECT_TIMEOUT = 10_000; // 10 seconds
    private static final int READ_TIMEOUT = 30_000; // 30 seconds

    public static String getDBUrl() {
        return DB_URL;
    }

    public static String getPropertiesFile() {
        return PROPERTIES_FILE;
    }

    public static int getConnectTimeout() {
        return CONNECT_TIMEOUT;
    }

    public static int getReadTimeout() {
        return READ_TIMEOUT;
    }

    public static DateTimeFormatter getDateFormatter() {
        return DATE_FORMATTER;
    }

    // SHA-512 hashes of blocks already printed in this JVM run.
    // Highlander rule: identical RPSL object → show it only once.
    // Storing 64-byte hashes instead of full block text keeps the Set compact
    // even when hundreds of route/route6 blocks are emitted in one run.
    private static final Set<String> printedBlockHashes = new HashSet<>();

    /**
     * Prints an RPSL block to stdout with two layers of deduplication:
     *
     * 1. Highlander (inter-block): SHA-512 of the normalised block is checked
     *    against a JVM-lifetime Set. Duplicate block → silent no-op.
     *
     * 2. Intra-block (RFC 2622 §2 aware): lines belonging to the same logical
     *    attribute are joined — continuation lines (starting with ' ', '\t', or
     *    '+') are concatenated to their parent before the combined key is added
     *    to a block-scoped Set. If the joined key is already in the Set the
     *    entire attribute group (parent + continuations) is suppressed.
     *    The Set is discarded when the block finishes; no state is carried over
     *    to the next call.
     */
    public static void printBlock(String block) {
        if (block == null || block.isEmpty()) return;

        // Highlander: hash the normalised block and bail if already seen
        try {
            if (!printedBlockHashes.add(initializeDatabase.sha512(block.strip()))) return;
        } catch (Exception e) {
            log.warn("SHA-512 block hash failed, falling back to full-text dedup", e);
            if (!printedBlockHashes.add(block.strip())) return;
        }

        // Intra-block dedup
        Set<String> attrSeen = new HashSet<>();
        List<String> group = new ArrayList<>();   // original lines of current attribute
        StringBuilder joinedKey = new StringBuilder(); // stripped+joined for comparison

        for (String line : block.split("\n", -1)) {
            if (line.isBlank()) {
                flushAttrGroup(group, joinedKey, attrSeen);
                group.clear();
                joinedKey.setLength(0);
                attrSeen.clear();
                System.out.println();
            } else if (!group.isEmpty() && isContinuation(line)) {
                group.add(line);
                joinedKey.append('\n').append(line.strip());
            } else {
                flushAttrGroup(group, joinedKey, attrSeen);
                group.clear();
                joinedKey.setLength(0);
                group.add(line);
                joinedKey.append(line.strip());
            }
        }
        flushAttrGroup(group, joinedKey, attrSeen);
    }

    private static boolean isContinuation(String line) {
        char c = line.charAt(0);
        return c == ' ' || c == '\t' || c == '+';
    }

    private static void flushAttrGroup(List<String> group, StringBuilder joinedKey, Set<String> seen) {
        if (group.isEmpty()) return;
        if (seen.add(joinedKey.toString())) {
            group.forEach(System.out::println);
        }
    }
}
