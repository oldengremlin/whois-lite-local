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
import java.time.format.DateTimeFormatter;
import org.slf4j.LoggerFactory;

/**
 *
 * @author olden
 */
public class Config {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(WhoisLiteLocal.class);
    private static final String DB_URL = "jdbc:sqlite:whoislitelocal.db";
    private static final String PROPERTIES_FILE = "whoislitelocal.properties";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int CONNECT_TIMEOUT = 10_000; // 10 seconds
    private static final int READ_TIMEOUT = 30_000; // 30 seconds

    public static Logger getLogger() {
        return logger;
    }

    public static String getDBUrl() {
        return DB_URL;
    }

    public static String getPropertiesFile() {
        return PROPERTIES_FILE;
    }

    public static int getConnectTimeout() {
        return CONNECT_TIMEOUT;
    }

    public static int getReadTimeout() {
        return READ_TIMEOUT;
    }

    public static DateTimeFormatter getDateFormatter() {
        return DATE_FORMATTER;
    }
}
