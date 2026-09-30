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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.Config;

/**
 *
 * @author olden
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessFiles {

    private record DownloadedFile(String url, Path tempFile, String lastModified, long fileSize) {

    }

    /** Attempts per file, and the first back-off between them (doubling each time). */
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 2_000;

    /**
     * Re-fetch and re-parse every file, ignoring the recorded Last-Modified and
     * size. Needed whenever the parser changes what it extracts: the data on the
     * mirror is unchanged, so without this the run skips every download and the
     * new logic never sees the files.
     */
    private final boolean force;

    protected Connection connection;
    protected String processUrl;
    protected Path tempFile;
    protected String lastModified;
    protected long fileSize;

    /**
     * Files this instance could not fetch. A run that skipped a download is not
     * a successful run, and saying so at the end keeps a stale dataset from
     * looking like a fresh one.
     */
    private int failedDownloads = 0;

    public int getFailedDownloads() {
        return this.failedDownloads;
    }

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
        if (this.force) {
            log.info("Forcing download of {}", this.processUrl);
            return true;
        }
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
            // Keep each future next to its URL so a failure can name the file it
            // was for; "Download failed" on its own says nothing useful.
            List<String> ordered = new ArrayList<>(urls);
            List<Future<DownloadedFile>> futures = new ArrayList<>(urls.size());
            for (String url : ordered) {
                futures.add(executor.submit(() -> downloadWithRetry(url)));
            }
            for (int i = 0; i < futures.size(); i++) {
                String url = ordered.get(i);
                try {
                    result.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    this.failedDownloads++;
                    log.error("Giving up on {} — it will be retried on the next run", url, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.failedDownloads++;
                    log.error("Download of {} interrupted", url, e);
                }
            }
        }
        return result;
    }

    /**
     * Downloads a file, retrying transient transport failures.
     *
     * <p>These are multi-hundred-megabyte transfers held open for tens of
     * seconds, so an occasional reset, timeout or TLS record failure is normal
     * rather than exceptional. Without a retry one such failure silently skipped
     * the file for the whole run: {@code file_metadata} is left untouched, so
     * nothing is corrupted, but the data simply stays stale until the next run.
     *
     * <p>Each attempt opens a fresh connection — a failed TLS session cannot be
     * resumed mid-stream — and the partial temp file is removed by
     * {@link #downloadOne}, so an attempt never inherits the previous one's bytes.
     */
    private DownloadedFile downloadWithRetry(String url) throws URISyntaxException, IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                return downloadOne(url);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == DOWNLOAD_ATTEMPTS) {
                    break;
                }
                long backoffMillis = RETRY_BACKOFF_MILLIS << (attempt - 1);
                log.warn("Download of {} failed on attempt {}/{} ({}: {}) — retrying in {} ms",
                        url, attempt, DOWNLOAD_ATTEMPTS, e.getClass().getSimpleName(), e.getMessage(),
                        backoffMillis);
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
        throw new IOException("Download of " + url + " failed after " + DOWNLOAD_ATTEMPTS
                + " attempts", lastFailure);
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
