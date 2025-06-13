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

import net.ukrcom.whoislitelocal.retrieve.*;
import net.ukrcom.whoislitelocal.parse.*;
import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import org.apache.commons.cli.ParseException;

public class WhoisLiteLocal {

    public static void main(String[] args) {
        try {
            CommandLineParser parser = new CommandLineParser(args);
            if (parser.isHelpRequested()) {
                CommandLineParser.printHelp();
                System.exit(0xff);
            } else if (parser.isGetData()) {
                executeGetData();
            } else if (parser.isRetrieveAutNum()) {
                executeRetrieveAutNum(parser.getAutNum());
            } else if (parser.isRetrieveAsSet()) {
                executeRetrieveAsSet(parser.getAsSet());
            } else if (parser.isRetrieveMntBy()) {
                executeRetrieveMntBy(parser.getMntBy());
            } else if (parser.isRetrieveMntner()) {
                executeRetrieveMntner(parser.getMntner());
            } else if (parser.isRetrieveOrganisation()) {
                executeRetrieveOrganisation(parser.getOrganisation());
            } else if (parser.isRouteOrigin()) {
                executeRouteOrigin(parser.getRouteOrigin());
            } else {
                CommandLineParser.printHelp();
                System.exit(0xfd);
            }
        } catch (ParseException ex) {
            CommandLineParser.printHelp();
            System.exit(0xfe);
        }
    }

    private static void executeGetData() {
        long startTime = System.currentTimeMillis();
        try {
            new initializeDatabase().createTables();
            new processFiles().process("urls_extended", new parseExtended());
            new processFiles().process("asnames", new parseAsnames());
            new processFiles().process("geolocations", new parseGeolocations());
            new processFiles().process("ripedb", new parseRpsl());
        } catch (IOException e) {
            Config.getLogger().error("Main process (IOException)", e);
        } catch (SQLException e) {
            Config.getLogger().error("Main process (SQLException)", e);
        } catch (URISyntaxException e) {
            Config.getLogger().error("Main process (URISyntaxException)", e);
        } finally {
            Config.getLogger().info("executeGetData completed in {} ms", System.currentTimeMillis() - startTime);
        }
    }

    private static void executeRetrieveAutNum(String autNum) {
        new retrieveAutNum(autNum).printAutNum().printOrg();
    }

    private static void executeRetrieveAsSet(String asSet) {
        new retrieveAsSet(asSet).printAsSet();
    }

    private static void executeRetrieveMntBy(String mntBy) {
        new retrieveMntBy(mntBy).printMntBy();
    }

    private static void executeRetrieveMntner(String mntBy) {
        new retrieveMntner(mntBy).printMntner().printMntnerRole();
    }

    private static void executeRetrieveOrganisation(String autNum) {
        new retrieveOrganisation(autNum).Load().printOrg();
    }

    private static void executeRouteOrigin(String autNum) {
        new retrieveRouteOrigin(autNum).printRouteOrigin();
    }

}
