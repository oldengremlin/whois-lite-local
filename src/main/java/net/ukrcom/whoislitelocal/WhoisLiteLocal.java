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

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;

public class WhoisLiteLocal {

    public static void main(String[] args) {
        try {
            new initializeDatabase().createTables();
            new processFiles().process("urls_extended", new parseExtended());
            new processFiles().process("asnames", new parseAsnames());
        } catch (IOException e) {
            Config.getLogger().error("Main process (IOException)", e);
        } catch (SQLException e) {
            Config.getLogger().error("Main process (SQLException)", e);
        } catch (URISyntaxException e) {
            Config.getLogger().error("Main process (URISyntaxException)", e);
        }
    }

}
