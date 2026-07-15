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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.whoislitelocal.parse.*;
import net.ukrcom.whoislitelocal.retrieve.*;
import org.apache.commons.cli.ParseException;

@Slf4j
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
            } else if (parser.isNetworkOrigin()) {
                executeNetworkOrigin(parser.getNetworkOrigin());
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

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Void> f1 = executor.submit((Callable<Void>) () -> {
                    new processFiles().process("urls_extended", new parseExtended());
                    return null;
                });
                Future<Void> f2 = executor.submit((Callable<Void>) () -> {
                    new processFiles().process("asnames", new parseAsnames());
                    return null;
                });
                Future<Void> f3 = executor.submit((Callable<Void>) () -> {
                    new processFiles().process("geolocations", new parseGeolocations());
                    return null;
                });

                try {
                    f1.get();
                } catch (ExecutionException e) {
                    log.error("urls_extended processing failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("urls_extended processing interrupted", e);
                }

                try {
                    f2.get();
                } catch (ExecutionException e) {
                    log.error("asnames processing failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("asnames processing interrupted", e);
                }

                try {
                    f3.get();
                } catch (ExecutionException e) {
                    log.error("geolocations processing failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("geolocations processing interrupted", e);
                }
            }

            new processFiles().process("ripedb", new parseRpsl());

        } catch (IOException e) {
            log.error("Main process (IOException)", e);
        } catch (SQLException e) {
            log.error("Main process (SQLException)", e);
        } catch (URISyntaxException e) {
            log.error("Main process (URISyntaxException)", e);
        } finally {
            log.info("executeGetData completed in {} ms", System.currentTimeMillis() - startTime);
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

    private static void executeNetworkOrigin(String netNum) {
        new retrieveNetworkOrigin(netNum).printNetworkOrigin();
    }

}
