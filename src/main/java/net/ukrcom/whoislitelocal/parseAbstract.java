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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/**
 *
 * @author olden
 */
public class parseAbstract implements parseInterface {

    protected String line;
    protected int MAX_FREE_LIST_COUNT = 100;

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
            runIncrementalVacuumSmart(pf);
            // Update file metadata
            try (PreparedStatement stmt = pf.connection.prepareStatement(
                    "INSERT OR REPLACE INTO file_metadata (url, last_modified, file_size) VALUES (?, ?, ?)")) {
                stmt.setString(1, pf.processUrl);
                stmt.setString(2, pf.lastModified);
                stmt.setLong(3, pf.fileSize);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                pf.logger.error("Error store metadata for URL {}, SQLException {}", pf.processUrl, ex);
            }
        } catch (IOException ex) {
            pf.logger.error("Can't parsing temporary file {}", pf.tempFile);
        } catch (CompressorException ex) {
            pf.logger.error("Compression error while parsing {}", pf.tempFile, ex);
        } finally {
            // Delete temporary file
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
        try (PreparedStatement stmt = pf.connection.prepareStatement("PRAGMA freelist_count");
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                int freelistCount = rs.getInt(1);

                if (freelistCount == 0) {
                    return;
                }

                pf.logger.debug("Current freelist_count: {}", freelistCount);

                if (freelistCount >= MAX_FREE_LIST_COUNT) { // поріг – налаштовується
                    int pagesToVacuum = freelistCount / 2;
                    try (PreparedStatement vacuumStmt = pf.connection.prepareStatement("PRAGMA incremental_vacuum(" + pagesToVacuum + ")")) {
                        vacuumStmt.execute();
                        pf.logger.debug("Ran incremental_vacuum({})", pagesToVacuum);
                    }
                } else {
                    pf.logger.debug("freelist_count ({}) below threshold, skipping vacuum", freelistCount);
                }
            }

        } catch (SQLException e) {
            pf.logger.warn("Failed to check freelist_count or run incremental_vacuum: {}", e.getMessage());
        }
    }

}
