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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;

/**
 *
 * @author olden
 */
@Slf4j
public class ProcessFiles {

    private record DownloadedFile(String url, Path tempFile, String lastModified, long fileSize) {

    }

    protected Connection connection;
    protected String processUrl;
    protected Path tempFile;
    protected String lastModified;
    protected long fileSize;

    public ProcessFiles process(String paramUrls, ParseInterface parseFile) throws
            IOException, SQLException, URISyntaxException {
        String[] urls = readUrls(paramUrls);
        if (urls.length == 0) {
            return this;
        }

        // Phase 1: determine which URLs need downloading (short read-only connection, no transaction)
        List<String> toDownload = new ArrayList<>();
        try (Connection readConn = DriverManager.getConnection(Config.getDBUrl())) {
            try (var stmt = readConn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 30000");
            }
            for (String url : urls) {
                this.processUrl = url.trim();
                if (shouldDownloadFile(readConn)) {
                    toDownload.add(this.processUrl);
                } else {
                    log.info("Skipping download for {}: file unchanged", url);
                }
            }
        }

        // Phase 2: download all needed URLs in parallel (no DB involvement)
        List<DownloadedFile> downloaded = downloadParallel(toDownload);

        if (downloaded.isEmpty()) {
            return this;
        }

        // Phase 3: parse + write (own connection — used by sequential parsers like ParseRpsl)
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            this.connection = conn;
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 30000");
            }
            this.connection.setAutoCommit(false);

            for (DownloadedFile df : downloaded) {
                this.processUrl = df.url();
                this.tempFile = df.tempFile();
                this.lastModified = df.lastModified();
                this.fileSize = df.fileSize();
                log.info("Parsing temporary file {} for {}", this.tempFile, this.processUrl);
                parseFile.parse(this);
            }

            this.connection.commit();
        }
        return this;
    }

    public ProcessFiles process(String paramUrls, ParseInterface parseFile, Connection sharedConn) throws
            IOException, SQLException, URISyntaxException {
        String[] urls = readUrls(paramUrls);
        if (urls.length == 0) {
            return this;
        }

        // Phase 1: check which URLs need downloading (short read-only connection)
        List<String> toDownload = new ArrayList<>();
        try (Connection readConn = DriverManager.getConnection(Config.getDBUrl())) {
            try (var stmt = readConn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 30000");
            }
            for (String url : urls) {
                this.processUrl = url.trim();
                if (shouldDownloadFile(readConn)) {
                    toDownload.add(this.processUrl);
                } else {
                    log.info("Skipping download for {}: file unchanged", url);
                }
            }
        }

        // Phase 2: download in parallel (no DB)
        List<DownloadedFile> downloaded = downloadParallel(toDownload);

        if (downloaded.isEmpty()) {
            return this;
        }

        // Phase 3: parse + write using the caller-managed shared connection
        this.connection = sharedConn;
        for (DownloadedFile df : downloaded) {
            this.processUrl = df.url();
            this.tempFile = df.tempFile();
            this.lastModified = df.lastModified();
            this.fileSize = df.fileSize();
            log.info("Parsing temporary file {} for {}", this.tempFile, this.processUrl);
            parseFile.parse(this);
        }
        return this;
    }

    /**
     * Reads the URL list for one property key. Returns an empty array — never
     * null — when the key is absent or blank, so a partially filled properties
     * file skips that group instead of failing.
     */
    private String[] readUrls(String paramUrls) throws IOException {
        Properties props = new Properties();
        try (InputStream input = ProcessFiles.class.getClassLoader().getResourceAsStream(Config.getPropertiesFile())) {
            if (input == null) {
                throw new IOException("Configuration file not found in classpath: " + Config.getPropertiesFile());
            }
            props.load(input);
        }
        String raw = props.getProperty(paramUrls);
        if (raw == null || raw.isBlank()) {
            log.info("No URLs configured for {}, skipping", paramUrls);
            return new String[0];
        }
        return raw.split(",");
    }

    private boolean shouldDownloadFile(Connection readConn) throws SQLException, IOException,
                                                                   URISyntaxException {
        try (PreparedStatement stmt = readConn.prepareStatement(
                "SELECT last_modified, file_size FROM file_metadata WHERE url = ?")) {
            stmt.setString(1, this.processUrl);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return true; // No metadata, download
                }
                this.lastModified = rs.getString("last_modified");
                this.fileSize = rs.getLong("file_size");
            }
            URI uri = openableUri(this.processUrl);
            HttpURLConnection connHttp = (HttpURLConnection) uri.toURL().openConnection();
            try {
                connHttp.setRequestMethod("HEAD");
                connHttp.setConnectTimeout(Config.getConnectTimeout());
                connHttp.setReadTimeout(Config.getReadTimeout());
                String serverLastModified = connHttp.getHeaderField("Last-Modified") != null
                                            ? connHttp.getHeaderField("Last-Modified") : "";
                long serverFileSize = connHttp.getContentLengthLong();
                return !serverLastModified.equals(lastModified) || serverFileSize != fileSize;
            } finally {
                connHttp.disconnect();
            }
        }
    }

    private List<DownloadedFile> downloadParallel(List<String> urls) {
        List<DownloadedFile> result = new ArrayList<>(urls.size());
        if (urls.isEmpty()) {
            return result;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DownloadedFile>> futures = new ArrayList<>(urls.size());
            for (String url : urls) {
                futures.add(executor.submit(() -> downloadOne(url)));
            }
            for (Future<DownloadedFile> f : futures) {
                try {
                    result.add(f.get());
                } catch (ExecutionException e) {
                    log.error("Download failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Download interrupted", e);
                }
            }
        }
        return result;
    }

    private DownloadedFile downloadOne(String url) throws URISyntaxException, IOException {
        URI uri = openableUri(url);
        HttpURLConnection connHttp = (HttpURLConnection) uri.toURL().openConnection();
        connHttp.setConnectTimeout(Config.getConnectTimeout());
        connHttp.setReadTimeout(Config.getReadTimeout());
        String lm = connHttp.getHeaderField("Last-Modified") != null ? connHttp.getHeaderField("Last-Modified") : "";
        long fs = connHttp.getContentLengthLong();

        long maxBytes = Config.getMaxDownloadBytes();
        if (fs > maxBytes) {
            connHttp.disconnect();
            throw new IOException("Refusing to download " + url + ": Content-Length " + fs
                    + " exceeds the limit of " + maxBytes + " bytes");
        }

        // Created only after the request is accepted, and removed again if the
        // transfer fails — otherwise every failed download leaves a stray file.
        Path tf = Files.createTempFile("whoislite_", ".txt");
        try (InputStream inputStream = connHttp.getInputStream()) {
            log.info("Downloading {} to temporary file {}", url, tf);
            long copied = Files.copy(inputStream, tf, StandardCopyOption.REPLACE_EXISTING);
            if (copied > maxBytes) {
                throw new IOException("Download of " + url + " exceeded the limit of " + maxBytes
                        + " bytes (server did not declare an accurate Content-Length)");
            }
            return new DownloadedFile(url, tf, lm, fs);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tf);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } finally {
            connHttp.disconnect();
        }
    }

    /**
     * Rejects anything that is not HTTPS. Downloaded data becomes the answer to
     * "who owns this address", so it must not be modifiable in transit. Plain
     * HTTP to a loopback address stays allowed so the tool can be tested against
     * a local mirror.
     */
    private URI openableUri(String url) throws URISyntaxException, IOException {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("https".equals(scheme)) {
            return uri;
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        if ("http".equals(scheme) && (host.equals("127.0.0.1") || host.equals("::1") || host.equals("localhost"))) {
            log.warn("Using plain HTTP to {} — acceptable for a local mirror only", host);
            return uri;
        }
        throw new IOException("Refusing to fetch " + url + ": only https:// is allowed "
                + "(plain http:// permitted for localhost only)");
    }
}
