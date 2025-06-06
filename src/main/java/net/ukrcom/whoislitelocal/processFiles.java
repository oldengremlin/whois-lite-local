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

import ch.qos.logback.classic.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 *
 * @author olden
 */
public class processFiles {

    protected final Logger logger;
    protected Connection connection;
    protected String processUrl;
    protected Path tempFile;
    protected String lastModified;
    protected long fileSize;

    public processFiles() {
        this.logger = Config.getLogger();
    }

    public processFiles process(String paramUrls, parseInterface parseFile) throws IOException, SQLException, URISyntaxException {
        if (paramUrls == null || paramUrls.trim().isEmpty()) {
            logger.info("No URLs configured for {}, skipping", paramUrls);
            return this;
        }
        Properties props = new Properties();
        try (var input = Files.newInputStream(Paths.get("src/main/resources", Config.getPropertiesFile()))) {
            props.load(input);
        }
        String[] urls = props.getProperty(paramUrls).split(",");
        try (Connection conn = DriverManager.getConnection(Config.getDBUrl())) {
            this.connection = conn;
            this.connection.setAutoCommit(false);
            for (String url : urls) {
                this.processUrl = url.trim();
                if (shouldDownloadFile()) {
                    download();
                    logger.info("Parsing temporary file {} for {}", this.tempFile, this.processUrl);
                    parseFile.parse(this);
                } else {
                    logger.info("Skipping download for {}: file unchanged", url);
                }
            }
            this.connection.commit();
        }
        return this;
    }

    private boolean shouldDownloadFile() throws SQLException, IOException, URISyntaxException {
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

    private void download() throws URISyntaxException, MalformedURLException, IOException {
        URI uri = new URI(this.processUrl);
        HttpURLConnection connHttp = (HttpURLConnection) uri.toURL().openConnection();
        connHttp.setConnectTimeout(Config.getConnectTimeout());
        connHttp.setReadTimeout(Config.getReadTimeout());
        this.lastModified = connHttp.getHeaderField("Last-Modified") != null ? connHttp.getHeaderField("Last-Modified") : "";
        this.fileSize = connHttp.getContentLengthLong();

        // Create temporary file
        this.tempFile = Files.createTempFile("whoislite_", ".txt");
        try (InputStream inputStream = connHttp.getInputStream()) {
            logger.info("Downloading {} to temporary file {}", this.processUrl, this.tempFile);
            Files.copy(inputStream, this.tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connHttp.disconnect();
        }
    }

}
