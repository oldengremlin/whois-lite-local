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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 *
 * @author olden
 */
public class CommandLineParser {

    private static final Options options = new Options()
            .addOption(
                    Option.builder("gd").longOpt("get-data")
                            .desc("Download and process data from configured URLs")
                            .build()
            )
            .addOption(
                    Option.builder("ran").longOpt("retrieve-aut-num")
                            .hasArg()
                            .argName("AS-num")
                            .desc("Get information on the aut-num object and its related objects.")
                            .build()
            )
            .addOption(
                    Option.builder("ras").longOpt("retrieve-as-set")
                            .hasArg()
                            .argName("AS-set")
                            .desc("Get information on the as-set object and its related objects.")
                            .build()
            )
            .addOption(
                    Option.builder("rmb").longOpt("retrieve-mnt-by")
                            .hasArg()
                            .argName("mntnr")
                            .desc("Get information on the as-set object and its related objects.")
                            .build()
            )
            .addOption(
                    Option.builder("ro").longOpt("retrieve-organisation")
                            .hasArg()
                            .argName("AS-num")
                            .desc("Get information on the as-set object and its related objects.")
                            .build()
            )
            .addOption("h", "help", false, "Show help");

    private final CommandLine cmd;

    public CommandLineParser(String[] args) throws ParseException {
        DefaultParser parser = new DefaultParser();
        cmd = parser.parse(options, args);
    }

    public boolean isGetData() {
        return cmd.hasOption("get-data") || cmd.getOptions().length == 0;
    }

    public boolean isHelpRequested() {
        return cmd.hasOption("help");
    }

    public boolean isRetrieveAutNum() {
        return cmd.hasOption("retrieve-aut-num");
    }

    public String getAutNum() {
        return cmd.getOptionValue("retrieve-aut-num").trim();
    }

    public boolean isRetrieveAsSet() {
        return cmd.hasOption("retrieve-as-set");
    }

    public String getAsSet() {
        return cmd.getOptionValue("retrieve-as-set").trim();
    }

    public boolean isRetrieveMntBy() {
        return cmd.hasOption("retrieve-mnt-by");
    }

    public String getMntBy() {
        return cmd.getOptionValue("retrieve-mnt-by").trim();
    }

    public boolean isRetrieveOrganisation() {
        return cmd.hasOption("retrieve-organisation");
    }

    public String getOrganisation() {
        return cmd.getOptionValue("retrieve-organisation").trim();
    }

    public static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar WhoisLiteLocal-1.0.0.jar", CommandLineParser.options, true);
    }
}
