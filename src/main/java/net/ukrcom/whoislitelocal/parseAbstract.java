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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 *
 * @author olden
 */
public class parseAbstract implements parseInterface {

    protected String line;

    @Override
    public void parse(processFiles pf) {
        // Parse temporary file
        try (BufferedReader reader = Files.newBufferedReader(pf.tempFile)) {
            while ((this.line = reader.readLine()) != null) {
                store(pf);
            }
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

}
