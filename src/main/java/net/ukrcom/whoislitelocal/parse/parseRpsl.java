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
import java.util.HashMap;
import java.util.Map;
import static net.ukrcom.whoislitelocal.initializeDatabase.sha512;
import org.apache.commons.compress.compressors.CompressorException;

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
                origin:
                peering-set:
                person:
                phone:
                pingable:
                poem:
                poetic-form:
                remarks:
                role:
                route-set:
                route6:
                route:
                rtr-set:
                source:
                tech-c:        
 */
public class parseRpsl extends parseAbstract implements parseInterface {

    private processFiles pf;
    private int batchCount = 0;
    private boolean needInitializeTempTables = true;
    private boolean beginBlock = false;
    private boolean ignoreNext = false;
    private int linesOfBlock = 0;
    private StringBuilder block;
    private String key, value;
    PreparedStatement storeSelectStmt, storeUpdateStmt, storeInsertStmt, storeTempStmt;

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
                 PreparedStatement tempStmt = this.pf.connection.prepareStatement(
                         "INSERT OR IGNORE INTO temp_rpsl (key, value) VALUES (?, ?)")) {

                this.storeSelectStmt = selectStmt;
                this.storeUpdateStmt = updateStmt;
                this.storeInsertStmt = insertStmt;
                this.storeTempStmt = tempStmt;

                while ((this.line = reader.readLine()) != null) {
                    if (!this.line.startsWith("#")) {
                        store(this.pf);
                    }
                }

                // Save any remaining block
                if (this.linesOfBlock > 0 && this.block != null && !this.block.isEmpty()) {
                    this.batchCount = this.BATCH_SIZE - 1;
                    saveBlock();
                }

                runIncrementalVacuumSmart(pf);

            } catch (SQLException ex) {
                this.pf.logger.error("Failed to process RPSL batch", ex);
            } catch (Exception ex) {
                this.pf.logger.error("Exception", ex);
            }
            // Update file metadata
            try (PreparedStatement stmt = this.pf.connection.prepareStatement(
                    "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                stmt.setString(1, this.pf.processUrl);
                stmt.setString(2, this.pf.lastModified);
                stmt.setLong(3, this.pf.fileSize);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                this.pf.logger.error("Error storing metadata for URL {}, SQLException {}", this.pf.processUrl, ex);
            }

            cleanupOutdatedRpsl(this.pf);

        } catch (IOException ex) {
            this.pf.logger.error("Can't parse temporary file {}", this.pf.tempFile, ex);
        } catch (CompressorException ex) {
            this.pf.logger.error("Compression error while parsing {}", this.pf.tempFile, ex);
        } catch (SQLException ex) {
            this.pf.logger.error("Failed to process file or cleanup rpsl", ex);
        } finally {
            try {
                Files.delete(this.pf.tempFile);
                this.pf.logger.info("Deleted temporary file {}", this.pf.tempFile);
            } catch (IOException e) {
                this.pf.logger.warn("Failed to delete temporary file {}: {}", this.pf.tempFile, e.getMessage());
            }
        }
    }

    @Override
    public void store(processFiles pf) {

//        this.pf.logger.debug("0. line=\"{}\"\n"
//                + "                                                                                      "
//                + "   ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}",
//                this.line, this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
        if (this.line.trim().isEmpty()) {
            initBeginBlock();
//            this.pf.logger.debug("1. ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}", this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
            return;
        } else if (this.ignoreNext) {
//            this.pf.logger.debug("2. ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}", this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
            return;
        } else if (this.linesOfBlock == 0 && isBlockAlreadyPresent()) {
            this.ignoreNext = true;
//            this.pf.logger.debug("3. ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}", this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value);
            return;
        }
        this.block.append(this.line.trim());
        this.block.append("\n");
        this.linesOfBlock++;

//        this.pf.logger.debug("4. line=\"{}\"\n"
//                + "                                                                                      "
//                + "   ignoreNext={}, linesOfBlock={}, beginBlock={}, key={}, value={}, block=\n{}",
//                this.line, this.ignoreNext, this.linesOfBlock, this.beginBlock, this.key, this.value, this.block.toString());
    }

    private void initBeginBlock() {
        if (this.linesOfBlock > 0) {
            saveBlock();
        }
        this.beginBlock = true;
        this.linesOfBlock = 0;
        this.ignoreNext = false;
        this.block = new StringBuilder();
    }

    private boolean isBlockAlreadyPresent() {
        String[] parts = this.line.split("\\s+", 2);
        if (parts.length < 2) {
            this.pf.logger.warn("Invalid RPSL line format: {}", this.line);
            return true;
        }
        this.key = parts[0].trim().replaceFirst(":$", "");
        this.value = parts[1].trim();
        if (this.blockCache.containsKey(this.key)) {
            if (this.blockCache.get(key).equals(this.value)) {
                pf.logger.warn("Object {} already exists in {}", this.value, this.key);
                return true;
            }
        }
        this.pf.logger.info("Begin new block: [{} : {}]", this.key, this.value);
        this.blockCache.put(this.key, this.value);
        return false;
    }

    private void saveBlock() {

        this.beginBlock = false;
        if (this.key == null || this.value == null || this.block == null || this.block.isEmpty()) {
            return;
        }

        try {
            this.storeSelectStmt.setString(1, this.key);
            this.storeSelectStmt.setString(2, this.value);
            ResultSet rs = storeSelectStmt.executeQuery();
            if (rs.next()) {

                String existingShaBlock = rs.getString("shablock");
                String shaBlock = sha512(this.block.toString());
                this.pf.logger.debug("[{} - {} : {}] SHA512 DB: [ {} ]", this.batchCount, this.key, this.value, existingShaBlock);
                this.pf.logger.debug("[{} - {} : {}] SHA512   : [ {} ]", this.batchCount, this.key, this.value, shaBlock);
                if (existingShaBlock.equals(shaBlock)) {
                    return;
                }

                this.storeUpdateStmt.setString(1, this.block.toString());
                this.storeUpdateStmt.setString(2, this.key);
                this.storeUpdateStmt.setString(3, this.value);
                this.storeUpdateStmt.executeUpdate();
                this.pf.logger.info("Update RPSL records for [{} : {}]", this.key, this.value);
            } else {
                this.storeInsertStmt.setString(1, this.key);
                this.storeInsertStmt.setString(2, this.value);
                this.storeInsertStmt.setString(3, this.block.toString());
                this.storeInsertStmt.addBatch();
                this.pf.logger.debug("Insert RPSL records for [{} : {}]", this.key, this.value);
            }

            this.storeTempStmt.setString(1, this.key);
            this.storeTempStmt.setString(2, this.value);
            this.storeTempStmt.addBatch();

            if (++this.batchCount >= BATCH_SIZE) {
                this.storeInsertStmt.executeBatch();
                this.storeTempStmt.executeBatch();
                this.pf.logger.info("Executed batch of {} RPSL records", this.batchCount);
                this.batchCount = 0;
            }

        } catch (SQLException ex) {
            this.pf.logger.warn("Can't add RPSL [{}:{}] to batch, SQLException {}", this.key, this.value, ex);
        } catch (Exception ex) {
            this.pf.logger.warn("Exception {}", ex);
        }
    }

    private void cleanupOutdatedRpsl(processFiles pf) throws SQLException {
        if (this.blockCache.isEmpty()) {
            this.pf.logger.info("No processed, skipping outdated rpsl cleanup");
            return;
        }
        try (PreparedStatement deleteRpslStmt = this.pf.connection.prepareStatement(
                "DELETE FROM rpsl WHERE key = ? AND value = ? AND NOT EXISTS "
                + "(SELECT 1 FROM temp_rpsl t WHERE t.key = rpsl.key AND t.value = rpsl.value)")) {
            for (Map.Entry<String, String> entry : this.blockCache.entrySet()) {

                String blockKey = entry.getKey();
                String blockValue = entry.getValue();

                deleteRpslStmt.setString(1, blockKey);
                deleteRpslStmt.setString(2, blockValue);

                int deleted = deleteRpslStmt.executeUpdate();
                if (deleted > 0) {
                    this.pf.logger.info("Deleted {} outdated rpsl: [{} : {}]", deleted, blockKey, blockValue);
                }

            }
        }
    }

}
