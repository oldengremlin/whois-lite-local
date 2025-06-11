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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

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

    private boolean needInitializeTempTables = true;
    private final Map<String, String> blockCache = new HashMap<>();
    private boolean beginBlock = false;
    private int linesOfBlock = 0;
    private StringBuilder block;
    private String key, value;
    private processFiles pf;
    private static final int BATCH_SIZE = 1000;

    @Override
    public void parse(processFiles pf) {
        this.pf = pf;
        try (
                InputStream fileIn = Files.newInputStream(pf.tempFile);
                BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
                InputStream decompressedIn = tryDecompress(bufferedIn);
                InputStreamReader decoder = new InputStreamReader(decompressedIn, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(decoder)) {

            if (needInitializeTempTables) {
                // Initialize temporary tables once per process
                pf.connection.createStatement().execute("""
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_rpsl (
                        key TEXT NOT NULL,
                        value TEXT NOT NULL,
                        UNIQUE(key, value)
                    )""");
                needInitializeTempTables = false;
            } else {
                // Clear temporary tables for this file
                pf.connection.createStatement().execute("DELETE FROM temp_rpsl");
            }
            blockCache.clear();

            Connection conn = pf.connection;
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO rpsl (key, value, block) VALUES (?, ?, ?)");
                 PreparedStatement tempStmt = pf.connection.prepareStatement(
                         "INSERT OR IGNORE INTO temp_rpsl (key, value) VALUES (?, ?)")) {
                int batchCount = 0;
                while ((this.line = reader.readLine()) != null) {
                    if (!this.line.startsWith("#")) {
                        store(pf);
                        // Check if block is complete (new block started or end of file)
                        if (beginBlock && linesOfBlock > 0 && block != null && !block.isEmpty()) {
                            if (saveBlock(insertStmt, tempStmt)) {
                                batchCount++;
                                if (batchCount >= BATCH_SIZE) {
                                    insertStmt.executeBatch();
                                    tempStmt.executeBatch();
                                    batchCount = 0;
                                    pf.logger.info("Executed batch of {} RPSL records", BATCH_SIZE);
                                }
                            }
                        }
                    }
                }
                // Save any remaining block
                if (linesOfBlock > 0 && block != null && !block.isEmpty()) {
                    if (saveBlock(insertStmt, tempStmt)) {
                        batchCount++;
                    }
                }
                // Execute remaining batch
                if (batchCount > 0) {
                    insertStmt.executeBatch();
                    tempStmt.executeBatch();
                    pf.logger.info("Executed final batch of {} RPSL records", batchCount);
                }
            } catch (SQLException ex) {
                pf.logger.error("Failed to process RPSL batch", ex);
            }
            // Update file metadata
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                stmt.setString(1, pf.processUrl);
                stmt.setString(2, pf.lastModified);
                stmt.setLong(3, pf.fileSize);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                pf.logger.error("Error storing metadata for URL {}, SQLException {}", pf.processUrl, ex);
            }
            cleanupOutdatedRpsl(pf);
        } catch (IOException ex) {
            pf.logger.error("Can't parse temporary file {}", pf.tempFile, ex);
        } catch (CompressorException ex) {
            pf.logger.error("Compression error while parsing {}", pf.tempFile, ex);
        } catch (SQLException ex) {
            pf.logger.error("Failed to process file or cleanup rpsl", ex);
        } finally {
            try {
                Files.delete(pf.tempFile);
                pf.logger.info("Deleted temporary file {}", pf.tempFile);
            } catch (IOException e) {
                pf.logger.warn("Failed to delete temporary file {}: {}", pf.tempFile, e.getMessage());
            }
        }
    }

    @Override
    public void store(processFiles pf) {
        if (this.line.trim().isEmpty()) {
            initBeginBlock();
            return;
        } else if (this.linesOfBlock == 0 && checkBeginBlock()) {
            return;
        }
        this.block.append(this.line.trim());
        this.block.append("\n");
        this.linesOfBlock++;
    }

    private void initBeginBlock() {
        this.beginBlock = true;
        this.linesOfBlock = 0;
        this.block = new StringBuilder();
    }

    private boolean checkBeginBlock() {
        pf.logger.info("Begin new block: {}", this.line);
        String[] parts = this.line.split("\\s+", 2);
        if (parts.length < 2) {
            pf.logger.warn("Invalid RPSL line format: {}", this.line);
            return true;
        }
        key = parts[0].trim();
        value = parts[1].trim();
        if (blockCache.containsKey(key)) {
            if (blockCache.get(key).equals(value)) {
                pf.logger.warn("Object {} already exists in {}", value, key);
                return true;
            }
        }
        blockCache.put(key, value);
        return false;
    }

    private boolean saveBlock(PreparedStatement insertStmt, PreparedStatement tempStmt) {
        if (key == null || value == null || block == null || block.isEmpty()) {
            return false;
        }
        try {
            insertStmt.setString(1, this.key);
            insertStmt.setString(2, this.value);
            insertStmt.setString(3, this.block.toString());
            insertStmt.addBatch();

            tempStmt.setString(1, this.key);
            tempStmt.setString(2, this.value);
            tempStmt.addBatch();

            return true;
        } catch (SQLException ex) {
            pf.logger.warn("Can't add RPSL [{}:{}] to batch, SQLException {}", this.key, this.value, ex);
            return false;
        }
    }

    private void cleanupOutdatedRpsl(processFiles pf) throws SQLException {
        if (blockCache.isEmpty()) {
            pf.logger.info("No processed, skipping outdated rpsl cleanup");
            return;
        }
        try (PreparedStatement deleteRpslStmt = pf.connection.prepareStatement(
                "DELETE FROM rpsl WHERE key = ? AND value = ? AND NOT EXISTS "
                + "(SELECT 1 FROM temp_rpsl t WHERE t.key = rpsl.key AND t.value = rpsl.value)")) {
            for (Map.Entry<String, String> entry : blockCache.entrySet()) {
                String blockKey = entry.getKey();
                String blockValue = entry.getValue();
                deleteRpslStmt.setString(1, blockKey);
                deleteRpslStmt.setString(2, blockValue);
                int deleted = deleteRpslStmt.executeUpdate();
                if (deleted > 0) {
                    pf.logger.info("Deleted {} outdated rpsl: [{} : {}]", deleted, blockKey, blockValue);
                }
            }
        }
    }
}
