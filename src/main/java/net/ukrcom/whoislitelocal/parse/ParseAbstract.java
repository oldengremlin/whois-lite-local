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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/**
 *
 * @author olden
 */
@Slf4j
public class ParseAbstract implements ParseInterface {

    protected String line;
    protected double VACUUM_FRAGMENTATION_THRESHOLD = 0.25;

    /**
     * Rows this file failed to store. store() implementations log and continue
     * so one bad line cannot abort a whole dump, but a file that lost rows must
     * not be recorded as successfully processed — otherwise the next run sees
     * unchanged metadata, skips the download, and the gap becomes permanent.
     */
    private int storeErrors = 0;

    protected void recordStoreError() {
        this.storeErrors++;
    }

    protected void resetStoreErrors() {
        this.storeErrors = 0;
    }

    protected int storeErrorCount() {
        return this.storeErrors;
    }

    @Override
    public void parse(ProcessFiles pf) {
        resetStoreErrors();
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
                storeFileMetadata(pf, storeErrorCount());
            }
        } catch (IOException ex) {
            log.error("Can't parsing temporary file {}", pf.tempFile, ex);
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
    public void store(ProcessFiles pf) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    /**
     * Writes the file_metadata row that lets the next run skip an unchanged
     * download. Deliberately skipped when rows were lost: leaving the old
     * metadata in place makes the next run re-fetch and re-parse the file.
     */
    protected void storeFileMetadata(ProcessFiles pf, int errors) {
        if (errors > 0) {
            log.warn("Not recording metadata for {}: {} row(s) failed to store. "
                    + "The file will be downloaded and parsed again on the next run.",
                    pf.processUrl, errors);
            return;
        }
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

    protected InputStream tryDecompress(BufferedInputStream in) throws
            CompressorException, IOException {
        in.mark(1024); // Дозволяє повернутися назад, якщо не вдасться розпізнати формат
        try {
            CompressorInputStream compressorIn = new CompressorStreamFactory().createCompressorInputStream(in);
            // A small archive can expand without bound; cap what we are willing to read.
            return new BoundedInputStream(compressorIn, Config.getMaxDecompressedBytes());
        } catch (CompressorException e) {
            in.reset(); // Якщо не вдалося розпакувати — повертаємось і читаємо як звичайний текст
            return in;
        }
    }

    /**
     * Fails the read once more than {@code limit} bytes have been produced,
     * so a decompression bomb cannot run the parser indefinitely.
     */
    private static final class BoundedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        private BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        private void advance(long n) throws IOException {
            if (n <= 0) {
                return;
            }
            count += n;
            if (count > limit) {
                throw new IOException("Decompressed size exceeded the limit of " + limit
                        + " bytes — refusing to continue reading");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                advance(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            advance(n);
            return n;
        }
    }

    protected void runIncrementalVacuumSmart(ProcessFiles pf) {
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
