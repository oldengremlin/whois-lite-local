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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/**
 *
 * @author olden
 */
@Slf4j
public class parseAbstract implements parseInterface {

    protected String line;
    protected double VACUUM_FRAGMENTATION_THRESHOLD = 0.25;

    @Override
    public void parse(processFiles pf) {
        // Parse temporary file
//        try (BufferedReader reader = Files.newBufferedReader(pf.tempFile)) {
        try (
                InputStream fileIn = Files.newInputStream(pf.tempFile);
                BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
                InputStream decompressedIn = tryDecompress(bufferedIn);
                InputStreamReader decoder = new InputStreamReader(decompressedIn, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(decoder)) {
            while ((this.line = reader.readLine()) != null) {
                store(pf);
            }
            synchronized (pf.connection) {
                runIncrementalVacuumSmart(pf);
                try (PreparedStatement stmt = pf.connection.prepareStatement(
                        "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                    stmt.setString(1, pf.processUrl);
                    stmt.setString(2, pf.lastModified);
                    stmt.setLong(3, pf.fileSize);
                    stmt.executeUpdate();
                } catch (SQLException ex) {
                    log.error("Error store metadata for URL {}, SQLException {}", pf.processUrl, ex);
                }
            }
        } catch (IOException ex) {
            log.error("Can't parsing temporary file {}", pf.tempFile);
        } finally {
            // Delete temporary file
            try {
                Files.delete(pf.tempFile);
                log.info("Deleted temporary file {}", pf.tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temporary file {}: {}", pf.tempFile, e.getMessage());
            }
        }
    }

    @Override
    public void store(processFiles pf) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    protected InputStream tryDecompress(BufferedInputStream in) throws
            CompressorException, IOException {
        in.mark(1024); // Дозволяє повернутися назад, якщо не вдасться розпізнати формат
        try {
            CompressorInputStream compressorIn = new CompressorStreamFactory().createCompressorInputStream(in);
            return compressorIn;
        } catch (CompressorException e) {
            in.reset(); // Якщо не вдалося розпакувати — повертаємось і читаємо як звичайний текст
            return in;
        }
    }

    protected void runIncrementalVacuumSmart(processFiles pf) {
        // Caller must hold synchronized(pf.connection)
        try {
            int pageCount;
            int freelistCount;

            try (PreparedStatement stmt = pf.connection.prepareStatement("PRAGMA page_count");
                 ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                pageCount = rs.getInt(1);
            }

            try (PreparedStatement stmt = pf.connection.prepareStatement("PRAGMA freelist_count");
                 ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                freelistCount = rs.getInt(1);
            }

            if (pageCount == 0 || freelistCount == 0) {
                return;
            }

            double fragmentation = (double) freelistCount / pageCount;
            log.debug("freelist={}, pages={}, fragmentation={}%",
                    freelistCount, pageCount, String.format("%.1f", fragmentation * 100));

            if (fragmentation >= VACUUM_FRAGMENTATION_THRESHOLD) {
                try (PreparedStatement vacuumStmt = pf.connection.prepareStatement(
                        "PRAGMA incremental_vacuum(" + freelistCount + ")")) {
                    vacuumStmt.execute();
                    log.info("Ran incremental_vacuum({}) — fragmentation was {}%",
                            freelistCount, String.format("%.1f", fragmentation * 100));
                }
            }

        } catch (SQLException e) {
            log.warn("Failed to run incremental_vacuum: {}", e.getMessage());
        }
    }

}
