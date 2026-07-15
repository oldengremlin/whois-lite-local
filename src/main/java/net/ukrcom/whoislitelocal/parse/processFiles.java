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
import static net.ukrcom.whoislitelocal.initializeDatabase.registerSha512Function;

/**
 *
 * @author olden
 */
@Slf4j
public class processFiles {

    private record DownloadedFile(String url, Path tempFile, String lastModified, long fileSize) {}

    protected Connection connection;
    protected String processUrl;
    protected Path tempFile;
    protected String lastModified;
    protected long fileSize;

    public processFiles process(String paramUrls, parseInterface parseFile) throws
            IOException, SQLException, URISyntaxException {
        if (paramUrls == null || paramUrls.trim().isEmpty()) {
            log.info("No URLs configured for {}, skipping", paramUrls);
            return this;
        }
        Properties props = new Properties();
        try (InputStream input = processFiles.class.getClassLoader().getResourceAsStream(Config.getPropertiesFile())) {
            if (input == null) {
                throw new IOException("Configuration file not found in classpath: " + Config.getPropertiesFile());
            }
            props.load(input);
        }
        String[] urls = props.getProperty(paramUrls).split(",");
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            registerSha512Function(conn);
            this.connection = conn;
            try (var pragmaStmt = conn.createStatement()) {
                pragmaStmt.execute("PRAGMA busy_timeout = 30000");
            }
            this.connection.setAutoCommit(false);

            // Phase 1: determine which URLs need downloading
            List<String> toDownload = new ArrayList<>();
            for (String url : urls) {
                this.processUrl = url.trim();
                if (shouldDownloadFile()) {
                    toDownload.add(this.processUrl);
                } else {
                    log.info("Skipping download for {}: file unchanged", url);
                }
            }

            // Phase 2: download all needed URLs in parallel
            List<DownloadedFile> downloaded = downloadParallel(toDownload);

            // Phase 3: sequential parse loop
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

    private boolean shouldDownloadFile() throws SQLException, IOException,
            URISyntaxException {
        try (PreparedStatement stmt = this.connection.prepareStatement(
                "SELECT last_modified, file_size FROM file_metadata WHERE url = ?")) {
            stmt.setString(1, this.processUrl);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) {
                return true; // No metadata, download
            }
            this.lastModified = rs.getString("last_modified");
            this.fileSize = rs.getLong("file_size");
            URI uri = new URI(this.processUrl);
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
        URI uri = new URI(url);
        HttpURLConnection connHttp = (HttpURLConnection) uri.toURL().openConnection();
        connHttp.setConnectTimeout(Config.getConnectTimeout());
        connHttp.setReadTimeout(Config.getReadTimeout());
        String lm = connHttp.getHeaderField("Last-Modified") != null ? connHttp.getHeaderField("Last-Modified") : "";
        long fs = connHttp.getContentLengthLong();
        Path tf = Files.createTempFile("whoislite_", ".txt");
        try (InputStream inputStream = connHttp.getInputStream()) {
            log.info("Downloading {} to temporary file {}", url, tf);
            Files.copy(inputStream, tf, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connHttp.disconnect();
        }
        return new DownloadedFile(url, tf, lm, fs);
    }
}
