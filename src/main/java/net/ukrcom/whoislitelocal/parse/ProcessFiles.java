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
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ThreadLocalRandom;
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
        int attempts = Config.getDownloadAttempts();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return downloadOne(url);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == attempts) {
                    break;
                }
                long backoffMillis = nextBackoff(attempt);
                log.warn("Download of {} failed on attempt {}/{} ({}: {}) — retrying in {} s",
                        url, attempt, attempts, e.getClass().getSimpleName(), e.getMessage(),
                        backoffMillis / 1000);
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
        // Every in-process attempt is spent. curl is worth one last try rather
        // than losing the file for the whole run: it races IPv4 against IPv6
        // (Happy Eyeballs, which HttpURLConnection does not do), and it uses a
        // different TLS stack, so a failure specific to either is not repeated.
        // Observed twice: curl fetched this file at full speed while the Java
        // path failed.
        try {
            log.warn("All {} in-process attempts for {} failed — falling back to curl", attempts, url);
            return downloadWithCurl(url);
        } catch (IOException curlFailure) {
            lastFailure.addSuppressed(curlFailure);
        }
        throw new IOException("Download of " + url + " failed after " + attempts
                + " attempts and a curl fallback", lastFailure);
    }

    /**
     * Fetches a file with the system curl, as a last resort.
     *
     * <p>Only the transfer is delegated. curl writes the bytes verbatim to the
     * same kind of temp file the in-process path produces, and parsing and
     * decompression stay where they are: {@link ParseAbstract#tryDecompress}
     * recognises the format from the content, not from how it arrived.
     *
     * <p>The safety rules of the in-process path are kept rather than bypassed:
     * the URL goes through {@link #openableUri} first, so https-only still holds;
     * {@code --max-filesize} applies the same size cap; redirects are confined to
     * https; and the command is built as an argument list for ProcessBuilder, so
     * no shell ever parses the URL.
     */
    private DownloadedFile downloadWithCurl(String url) throws URISyntaxException, IOException {
        URI uri = openableUri(url);
        Path target = Files.createTempFile("whoislite_", ".txt");
        Path headerDump = Files.createTempFile("whoislite_hdr_", ".txt");
        try {
            List<String> command = new ArrayList<>(List.of(
                    "curl", "--fail", "--location", "--silent", "--show-error",
                    "--connect-timeout", String.valueOf(Config.getConnectTimeout() / 1000),
                    "--max-time", String.valueOf(Config.getDownloadMaxSeconds()),
                    // Abort a transfer that has effectively stalled, mirroring the
                    // read timeout the in-process path sets on its socket.
                    "--speed-limit", "1024",
                    "--speed-time", String.valueOf(Config.getReadTimeout() / 1000),
                    "--max-filesize", String.valueOf(Config.getMaxDownloadBytes()),
                    "--dump-header", headerDump.toString(),
                    "--output", target.toString()));
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                command.add("--proto-redir");
                command.add("=https");
            }
            command.add(uri.toString());

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String curlOutput;
            try (InputStream out = process.getInputStream()) {
                curlOutput = new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("curl fetch of " + url + " was interrupted", e);
            }
            if (exitCode != 0) {
                throw new IOException("curl exited with status " + exitCode + " for " + url
                        + (curlOutput.isEmpty() ? "" : ": " + curlOutput));
            }

            long onDisk = Files.size(target);
            if (onDisk == 0) {
                throw new IOException("curl produced an empty file for " + url);
            }
            // Record the same figures the in-process path records, so the
            // unchanged-file check behaves identically on the next run.
            String lastModified = lastHeaderValue(headerDump, "last-modified");
            String contentLength = lastHeaderValue(headerDump, "content-length");
            long reportedSize = onDisk;
            if (!contentLength.isEmpty()) {
                try {
                    reportedSize = Long.parseLong(contentLength);
                } catch (NumberFormatException ignore) {
                    // keep the on-disk size
                }
            }
            log.info("curl fetched {} ({} bytes) to {}", url, onDisk, target);
            return new DownloadedFile(url, target, lastModified, reportedSize);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } finally {
            try {
                Files.deleteIfExists(headerDump);
            } catch (IOException ignore) {
                // a leftover header dump is harmless
            }
        }
    }

    /**
     * Returns the last value of a header in a curl dump, or an empty string.
     * The last one wins because {@code --location} appends a block per redirect
     * hop, and only the final response describes the file actually received.
     */
    private String lastHeaderValue(Path headerDump, String name) throws IOException {
        String prefix = name.toLowerCase() + ":";
        String value = "";
        for (String line : Files.readAllLines(headerDump, StandardCharsets.ISO_8859_1)) {
            if (line.toLowerCase().startsWith(prefix)) {
                value = line.substring(prefix.length()).trim();
            }
        }
        return value;
    }

    /**
     * Doubling back-off with up to 25% of random jitter.
     *
     * <p>The jitter matters because the usual reason a public mirror refuses a
     * download is that everyone's nightly job is pulling the same file at the same
     * minute. A fixed schedule would send every one of those clients back at the
     * same instant, repeatedly colliding in the same saturated window.
     */
    private long nextBackoff(int attempt) {
        long base = Config.getDownloadRetryBaseMillis() << (attempt - 1);
        return base + (long) (ThreadLocalRandom.current().nextDouble() * 0.25 * base);
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
