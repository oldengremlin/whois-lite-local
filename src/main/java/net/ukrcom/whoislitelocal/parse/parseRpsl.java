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
package net.ukrcom.whoislitelocal.parse;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import static net.ukrcom.whoislitelocal.initializeDatabase.sha512;

/**
 *
 * @author olden
 */

/*
            Key is —
                abuse-mailbox:
                address:
                admin-c:
                as-block:
                as-set:
                aut-num:
                created:
                descr:
                domain:
                e-mail:
                filter-set:
                inet-rtr:
                inet6num:
                inetnum:
                irt:
                key-cert:
                last-modified:
                member-of:
                mnt-by:
                mnt-routes:
                mntner:
                nic-hdl:
                notify:
                org:
                organisation:
                extractedSubKeyValue:
                peering-set:
                person:
                phone:
                pingable:
                poem:
                poetic-form:
                remarks:
                role:
                extractedKeyValue-set:
                route6:
                extractedKeyValue:
                rtr-set:
                source:
                tech-c:
 */
@Slf4j
public class parseRpsl extends parseAbstract implements parseInterface {

    private processFiles pf;
    private int batchCount = 0;
    private int batchCountRpslOrigin = 0;
    private int batchCountRpslMntBy = 0;
    private boolean needInitializeTempTables = true;
    private boolean ignoreNext = false;
    private int linesOfBlock = 0;
    private StringBuilder block;
    private String key, value;
    private PreparedStatement storeSelectStmt, storeUpdateStmt, storeInsertStmt;
    private PreparedStatement storeInsertRpslOrigin, storeInsertRpslMntBy;
    private PreparedStatement storeInsertTempRpslOrigin, storeInsertTempRpslMntBy;
    private PreparedStatement storeTempStmt;

    private final Set<String> allowedKeys = Set.of(
            "aut-num",
            "as-set",
            "organisation",
            "mntner",
            "role",
            "route",
            "route6"
    );
    private final Map<String, String> blockCache = new HashMap<>();
    private final int BATCH_SIZE = 1000;

    @Override
    public void parse(processFiles pf) {
        this.pf = pf;
        try (
                InputStream fileIn = Files.newInputStream(this.pf.tempFile);
                BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
                InputStream decompressedIn = tryDecompress(bufferedIn);
                InputStreamReader decoder = new InputStreamReader(decompressedIn, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(decoder)) {

            if (this.needInitializeTempTables) {
                // Initialize temporary tables once per process
                this.pf.connection.createStatement().execute("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_rpsl (
                        key TEXT NOT NULL,
                        value TEXT NOT NULL,
                        UNIQUE(key, value)
                    )""");

                this.pf.connection.createStatement().execute("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_rpsl_origin (
                	origin TEXT NOT NULL COLLATE NOCASE,
                        route TEXT NOT NULL,
                        UNIQUE(origin, route)
                    )""");
                this.pf.connection.createStatement().execute("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_rpsl_mntby (
                	key TEXT NOT NULL,
                        value TEXT NOT NULL COLLATE NOCASE,
                	mntby TEXT NOT NULL COLLATE NOCASE,
                	UNIQUE(mntby, key, value)
                    )""");

                this.needInitializeTempTables = false;
            } else {
                // Clear temporary tables for this file
                this.pf.connection.createStatement().execute("DELETE FROM temp_rpsl");
            }
            this.blockCache.clear();

            try (PreparedStatement selectStmt = this.pf.connection.prepareStatement(
                    "SELECT sha512(block) AS shablock FROM rpsl WHERE key=? AND value=?");
                 PreparedStatement updateStmt = this.pf.connection.prepareStatement(
                         "UPDATE rpsl SET block=? WHERE key=? AND value=?");
                 PreparedStatement insertStmt = this.pf.connection.prepareStatement(
                         "INSERT OR IGNORE INTO rpsl (key, value, block) VALUES (?, ?, ?)");
                 PreparedStatement insertRpslOrigin = this.pf.connection.prepareStatement(
                         "INSERT OR REPLACE INTO rpsl_origin (origin, route) VALUES (?, ?)");
                 PreparedStatement insertRpslMntBy = this.pf.connection.prepareStatement(
                         "INSERT OR REPLACE INTO rpsl_mntby (key, value, mntby) VALUES (?, ?, ?)");
                 PreparedStatement insertTempRpslOrigin = this.pf.connection.prepareStatement(
                         "INSERT OR REPLACE INTO temp_rpsl_origin (origin, route) VALUES (?, ?)");
                 PreparedStatement insertTempRpslMntBy = this.pf.connection.prepareStatement(
                         "INSERT OR REPLACE INTO temp_rpsl_mntby (key, value, mntby) VALUES (?, ?, ?)");
                 PreparedStatement tempStmt = this.pf.connection.prepareStatement(
                         "INSERT OR IGNORE INTO temp_rpsl (key, value) VALUES (?, ?)")) {

                this.storeSelectStmt = selectStmt;
                this.storeUpdateStmt = updateStmt;
                this.storeInsertStmt = insertStmt;
                this.storeInsertRpslOrigin = insertRpslOrigin;
                this.storeInsertRpslMntBy = insertRpslMntBy;
                this.storeInsertTempRpslOrigin = insertTempRpslOrigin;
                this.storeInsertTempRpslMntBy = insertTempRpslMntBy;
                this.storeTempStmt = tempStmt;

                while ((this.line = reader.readLine()) != null) {
                    if (!this.line.startsWith("#") && !this.line.startsWith("%")) {
                        store(this.pf);
                    }
                }

                // Save any remaining block
                if (this.linesOfBlock > 0 && this.block != null && !this.block.isEmpty()) {
                    this.batchCount = this.BATCH_SIZE - 1;
                    saveBlock();
                }

                if (this.batchCountRpslOrigin > 0) {
                    this.storeInsertRpslOrigin.executeBatch();
                    this.storeInsertTempRpslOrigin.executeBatch();
                }

                if (this.batchCountRpslMntBy > 0) {
                    this.storeInsertRpslMntBy.executeBatch();
                    this.storeInsertTempRpslMntBy.executeBatch();
                }

                runIncrementalVacuumSmart(pf);

            } catch (SQLException ex) {
                log.error("Failed to process RPSL batch", ex);
            } catch (Exception ex) {
                log.error("Exception", ex);
            }
            // Update file metadata
            try (PreparedStatement stmt = this.pf.connection.prepareStatement(
                    "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                stmt.setString(1, this.pf.processUrl);
                stmt.setString(2, this.pf.lastModified);
                stmt.setLong(3, this.pf.fileSize);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                log.error("Error storing metadata for URL {}, SQLException {}", this.pf.processUrl, ex);
            }

            cleanupOutdatedRpsl(this.pf);

        } catch (IOException ex) {
            log.error("Can't parse temporary file {}", this.pf.tempFile, ex);
        } catch (SQLException ex) {
            log.error("Failed to process file or cleanup rpsl", ex);
        } finally {
            try {
                Files.delete(this.pf.tempFile);
                log.info("Deleted temporary file {}", this.pf.tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temporary file {}: {}", this.pf.tempFile, e.getMessage());
            }
        }
    }

    @Override
    public void store(processFiles pf) {

//        log.debug("0. line=\"{}\"\n"
//                + "                                                                                      "
//                + "   ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}",
//                this.line, this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
        if (this.line.trim().isEmpty()) {
            initBeginBlock();
//            log.debug("1. ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}", this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
            return;
        } else if (this.ignoreNext) {
//            log.debug("2. ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}", this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
            return;
        } else if (this.linesOfBlock == 0 && isBlockAlreadyPresent()) {
            this.ignoreNext = true;
//            log.debug("3. ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}", this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
            return;
        }
        if (this.block == null) {
            log.warn("Unexpected content before first block separator, skipping: {}", this.line);
            return;
        }
        this.block.append(this.line.stripTrailing());
        this.block.append("\n");
        this.linesOfBlock++;

//        log.debug("4. line=\"{}\"\n"
//                + "                                                                                      "
//                + "   ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}, block=\n{}",
//                this.line, this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value, this.block.toString());
    }

    private void initBeginBlock() {
        if (this.linesOfBlock > 0) {
            saveBlock();
        }
        this.linesOfBlock = 0;
        this.ignoreNext = false;
        this.block = new StringBuilder();
    }

    private boolean isBlockAlreadyPresent() {
        String[] parts = this.line.split("\\s+", 2);
        if (parts.length < 2) {
            log.warn("Invalid RPSL line format: {}", this.line);
            return true;
        }
        this.key = parts[0].trim().replaceFirst(":$", "");
        this.value = parts[1].trim();
        if (this.blockCache.containsKey(this.key)) {
            if (this.blockCache.get(key).equals(this.value)) {
                log.warn("Object {} already exists in {}", this.value, this.key);
                return true;
            }
        }
        log.info("Begin new block: [{} : {}]", this.key, this.value);
        this.blockCache.put(this.key, this.value);
        return false;
    }

    private void saveBlock() {

        if (this.key == null || this.value == null || this.block == null || this.block.isEmpty()) {
            return;
        }

        if (!allowedKeys.contains(this.key)) {
            return;
        }

        switch (this.key) {
            case "route", "route6" ->
                storeRpslOrigin();
            case "role", "aut-num", "as-set" ->
                storeRpslMntBy();
        }

        try {
            this.storeSelectStmt.setString(1, this.key);
            this.storeSelectStmt.setString(2, this.value);
            ResultSet rs = storeSelectStmt.executeQuery();
            if (rs.next()) {

                String existingShaBlock = rs.getString("shablock");
                String shaBlock = sha512(this.block.toString());
                log.debug("[{} - {} : {}] SHA512 DB: [ {} ]", this.batchCount, this.key, this.value, existingShaBlock);
                log.debug("[{} - {} : {}] SHA512   : [ {} ]", this.batchCount, this.key, this.value, shaBlock);
                if (existingShaBlock.equals(shaBlock)) {
                    // Block unchanged — still register as seen to protect from cleanup
                    this.storeTempStmt.setString(1, this.key);
                    this.storeTempStmt.setString(2, this.value);
                    this.storeTempStmt.addBatch();
                    if (++this.batchCount >= this.BATCH_SIZE) {
                        this.storeInsertStmt.executeBatch();
                        this.storeTempStmt.executeBatch();
                        log.info("Executed batch of {} RPSL records", this.batchCount);
                        this.batchCount = 0;
                    }
                    return;
                }

                this.storeUpdateStmt.setString(1, this.block.toString());
                this.storeUpdateStmt.setString(2, this.key);
                this.storeUpdateStmt.setString(3, this.value);
                this.storeUpdateStmt.executeUpdate();
                log.info("Update RPSL records for [{} : {}]", this.key, this.value);
            } else {
                this.storeInsertStmt.setString(1, this.key);
                this.storeInsertStmt.setString(2, this.value);
                this.storeInsertStmt.setString(3, this.block.toString());
                this.storeInsertStmt.addBatch();
                log.debug("Insert RPSL records for [{} : {}]", this.key, this.value);
            }

            this.storeTempStmt.setString(1, this.key);
            this.storeTempStmt.setString(2, this.value);
            this.storeTempStmt.addBatch();

            if (++this.batchCount >= this.BATCH_SIZE) {
                this.storeInsertStmt.executeBatch();
                this.storeTempStmt.executeBatch();
                log.info("Executed batch of {} RPSL records", this.batchCount);
                this.batchCount = 0;
            }

        } catch (SQLException ex) {
            log.warn("Can't add RPSL [{}:{}] to batch, SQLException {}", this.key, this.value, ex);
        } catch (Exception ex) {
            log.warn("Exception {}", ex);
        }
    }

    private void cleanupOutdatedRpsl(processFiles pf) throws SQLException {
        if (this.blockCache.isEmpty()) {
            log.info("No processed, skipping outdated rpsl cleanup");
            return;
        }
        // Iterate over distinct key types seen in this file (not individual values),
        // and remove any DB record of that type that was absent from the parsed file.
        // temp_rpsl holds all (key, value) pairs actually processed, so NOT EXISTS
        // correctly identifies stale records across the entire key type.
        try (PreparedStatement deleteRpslStmt = this.pf.connection.prepareStatement(
                "DELETE FROM rpsl WHERE key = ? AND NOT EXISTS "
                + "(SELECT 1 FROM temp_rpsl t WHERE t.key = rpsl.key AND t.value = rpsl.value)")) {
            for (String keyType : this.blockCache.keySet()) {
                deleteRpslStmt.setString(1, keyType);
                int deleted = deleteRpslStmt.executeUpdate();
                if (deleted > 0) {
                    log.info("Deleted {} outdated rpsl records of type [{}]", deleted, keyType);
                }
            }
        }
    }

    private Map.Entry<String, List<String>> blockExtractor(String subKey) {
        String extractedKeyValue = null;
        List<String> extractedSubKeyValues = new ArrayList<>();

        for (String blockLine : this.block.toString().lines().toList()) {
            blockLine = blockLine.trim();
            if (blockLine.startsWith(this.key + ":")) {
                extractedKeyValue = blockLine.split("\\s+", 2)[1];
            } else if (blockLine.startsWith(subKey + ":")) {
                String extractedValue = blockLine.split("\\s+", 2)[1];
                extractedSubKeyValues.add(extractedValue);
            }
        }

        return new AbstractMap.SimpleEntry<>(extractedKeyValue, extractedSubKeyValues);
    }

    private void storeRpslOrigin() {
        Map.Entry<String, List<String>> result = blockExtractor("origin");
        String rpsl_originRoute = result.getKey();
        List<String> origins = result.getValue();
        saveRpslOrigin(rpsl_originRoute, origins);
    }

    private void storeRpslMntBy() {
        Map.Entry<String, List<String>> result = blockExtractor("mnt-by");
        String mntbyObjectId = result.getKey();
        List<String> rpsl_mntbyValues = result.getValue();
        saveRpslMntBy(mntbyObjectId, rpsl_mntbyValues);
    }

    private void saveRpslOrigin(String rpsl_originRoute, List<String> origins) {
        try {
            for (String origin : origins) {
                this.storeInsertRpslOrigin.setString(1, origin);
                this.storeInsertRpslOrigin.setString(2, rpsl_originRoute);
                this.storeInsertRpslOrigin.addBatch();

                this.storeInsertTempRpslOrigin.setString(1, origin);
                this.storeInsertTempRpslOrigin.setString(2, rpsl_originRoute);
                this.storeInsertTempRpslOrigin.addBatch();

                log.debug("[{}] Store RPSL origin for {} → [{} : {}]",
                        this.batchCountRpslOrigin, this.key, rpsl_originRoute, origin);

                if (++this.batchCountRpslOrigin >= this.BATCH_SIZE) {
                    this.storeInsertRpslOrigin.executeBatch();
                    this.storeInsertTempRpslOrigin.executeBatch();
                    this.batchCountRpslOrigin = 0;
                }

            }
        } catch (SQLException ex) {
            log.warn("Can't store RPSL origin for {} → [{} : {}]",
                    this.batchCountRpslOrigin, this.key, rpsl_originRoute, origins, ex);
        }
    }

    private void saveRpslMntBy(String mntbyObjectId, List<String> rpsl_mntbyValues) {
        try {
            for (String mntbyValue : rpsl_mntbyValues) {
                this.storeInsertRpslMntBy.setString(1, this.key);
                this.storeInsertRpslMntBy.setString(2, mntbyObjectId);
                this.storeInsertRpslMntBy.setString(3, mntbyValue);
                this.storeInsertRpslMntBy.addBatch();

                this.storeInsertTempRpslMntBy.setString(1, this.key);
                this.storeInsertTempRpslMntBy.setString(2, mntbyObjectId);
                this.storeInsertTempRpslMntBy.setString(3, mntbyValue);
                this.storeInsertTempRpslMntBy.addBatch();

                log.debug("[{}] Store RPSL mnt-by for {} → [{} : {}]",
                        this.batchCountRpslMntBy, this.key, mntbyObjectId, mntbyValue);

                if (++this.batchCountRpslMntBy >= this.BATCH_SIZE) {
                    this.storeInsertRpslMntBy.executeBatch();
                    this.storeInsertTempRpslMntBy.executeBatch();
                    this.batchCountRpslMntBy = 0;
                }

            }
        } catch (SQLException ex) {
            log.warn("Can't store RPSL mnt-by for {} → [{} : {}]",
                    this.batchCountRpslMntBy, this.key, mntbyObjectId, rpsl_mntbyValues);
        }
    }

    private void cleanupRpslOriginAndMntBy() throws SQLException {
        try (PreparedStatement deleteRpslOrigin = this.pf.connection.prepareStatement("DELETE FROM rpsl_origin "
                + "WHERE NOT EXISTS ( "
                + "SELECT 1 FROM temp_rpsl_origin "
                + "WHERE temp_rpsl_origin.origin = rpsl_origin.origin "
                + "  AND temp_rpsl_origin.route = rpsl_origin.route "
                + ")");
             PreparedStatement deleteRpslMntBy = this.pf.connection.prepareStatement("DELETE FROM rpsl_mntby "
                     + "WHERE NOT EXISTS ( "
                     + "SELECT 1 FROM temp_rpsl_mntby "
                     + "WHERE temp_rpsl_mntby.mntby = rpsl_mntby.mntby "
                     + "  AND temp_rpsl_mntby.key = rpsl_mntby.key "
                     + "  AND temp_rpsl_mntby.value = rpsl_mntby.value"
                     + ")")) {

            int deleted;

            deleted = deleteRpslOrigin.executeUpdate();
            if (deleted > 0) {
                log.info("Deleted {} outdated rpsl_origin", deleted);
            }

            deleted = deleteRpslMntBy.executeUpdate();
            if (deleted > 0) {
                log.info("Deleted {} outdated rpsl_mntby", deleted);
            }

        }
    }

}
