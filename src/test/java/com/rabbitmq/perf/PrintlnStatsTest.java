// Copyright (c) 2018 Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.perf;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.countMatches;
import static org.apache.commons.lang3.StringUtils.splitByWholeSeparatorPreserveAllTokens;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class PrintlnStatsTest {

    StringWriter csvOut;
    ByteArrayOutputStream consoleOut;
    String output;

    static Configurator configure() {
        return new Configurator();
    }

    static TestConfiguration[] testArguments() {
        return ArrayUtils.addAll(testConfigurationsMicroSeconds(), testConfigurationsMilliSeconds());
    }

    static TestConfiguration[] testConfigurationsMicroSeconds() {
        return new TestConfiguration[]{
                tc("All metrics (sent, received, returned, confirmed, nacked)",
                        configure().sent(true).received(true).returned(true).confirmed(true),
                        array("sent", "returned", "confirmed", "nacked", "received",
                                "min/median/75th/95th/99th consumer latency",
                                "confirm latency"),
                        2),
                tc("Sent, received",
                        configure().sent(true).received(true).returned(false).confirmed(false),
                        array("sent", "received", "min/median/75th/95th/99th consumer latency"),
                        1,
                        array("returned", "confirmed", "nacked", "confirm latency")),
                tc("Sent, received, confirmed",
                        configure().sent(true).received(true).returned(false).confirmed(true),
                        array("sent", "confirmed", "nacked", "received",
                                "min/median/75th/95th/99th consumer latency",
                                "confirm latency"),
                        2,
                        array("returned")),
                tc("Sent, received, returned",
                        configure().sent(true).received(true).returned(true).confirmed(false),
                        array("sent", "returned", "received",
                                "min/median/75th/95th/99th consumer latency"),
                        1,
                        array("confirmed", "nacked", "confirm latency")),
                tc("Sent",
                        configure().sent(true).received(false).returned(false).confirmed(false),
                        array("sent"),
                        0,
                        array("returned", "confirmed", "nacked", "received",
                                "min/median/75th/95th/99th consumer latency",
                                "consumer latency", "confirm latency")),
                tc("Sent, returned",
                        configure().sent(true).received(false).returned(true).confirmed(false),
                        array("sent", "returned"),
                        0,
                        array("confirmed", "nacked", "received",
                                "min/median/75th/95th/99th consumer latency",
                                "consumer latency", "confirm latency")),
                tc("Sent, confirmed",
                        configure().sent(true).received(false).returned(false).confirmed(true),
                        array("sent", "confirmed", "nacked", "min/median/75th/95th/99th confirm latency"),
                        1,
                        array("returned", "received",
                                "consumer latency")),
                tc("Sent, returned, confirmed",
                        configure().sent(true).received(false).returned(true).confirmed(true),
                        array("sent", "returned", "confirmed", "nacked", "min/median/75th/95th/99th confirm latency"),
                        1,
                        array("received", "consumer latency")),
                tc("Sent, received",
                        configure().sent(true).received(true).returned(false).confirmed(false),
                        array("sent", "received", "min/median/75th/95th/99th consumer latency"),
                        1,
                        array("returned", "confirmed", "nacked", "confirm latency")),
                tc("Received",
                        configure().sent(false).received(true).returned(false).confirmed(false),
                        array("received", "min/median/75th/95th/99th consumer latency"),
                        1,
                        array("sent", "returned", "confirmed", "nacked", "confirm latency")),
        };
    }

    static TestConfiguration[] testConfigurationsMilliSeconds() {
        return Stream.of(testConfigurationsMicroSeconds())
                .map(configuration -> {
                    configuration.configurator.useMilliseconds(true);
                    return configuration;
                }).collect(Collectors.toList()).toArray(new TestConfiguration[]{});
    }

    static String[] array(String... values) {
        return values;
    }

    static TestConfiguration tc(String description, Configurator configurator, String[] expectedSubstringInOutput, int unitOccurrences, String... nonExpectedSubstringInOutput) {
        return new TestConfiguration(description, configurator, expectedSubstringInOutput, unitOccurrences, nonExpectedSubstringInOutput);
    }

    @BeforeEach
    public void init() {
        csvOut = new StringWriter();
        consoleOut = new ByteArrayOutputStream();
    }

    @ParameterizedTest
    @MethodSource("testArguments")
    public void stats(TestConfiguration testConfiguration) {
        execute(testConfiguration.configurator);
        checkCsv(testConfiguration.unit());
        assertThat(output.split(","), arrayWithSize(testConfiguration.expectedSubstringInOutput.length));
        assertThatOutputContains(testConfiguration.expectedSubstringInOutput);
        assertThatOutputDoesNotContains(testConfiguration.nonExpectedSubstringInOutput);
        assertThat(countMatches(output, "0/0/0/0/0 " + testConfiguration.unit().name), is(testConfiguration.unitOccurrences));
    }

    void execute(Configurator configurator) {
        PrintlnStats stats = stats(configurator);
        stats.report(System.currentTimeMillis());
        this.output = consoleOut.toString();
    }

    void assertThatOutputContains(String... substrings) {
        assertThat(output, stringContainsInOrder(substrings));
    }

    void assertThatOutputDoesNotContains(String... substrings) {
        for (String substring : substrings) {
            assertThat(output + " should not contain " + substring, output.contains(substring), is(false));
        }
    }

    void checkCsv(Unit unit) {
        String[] lines = csvOut.toString().split(System.getProperty("line.separator"));
        assertThat(lines, arrayWithSize(2));
        for (String line : lines) {
            assertThat(splitByWholeSeparatorPreserveAllTokens(line, ","), arrayWithSize(17));
        }
        assertThat(countMatches(lines[0], "(" + unit.name + ")"), is(5 * 2));
    }

    PrintlnStats stats(Configurator configurator) {
        return new PrintlnStats(
                "test-id", 1000L,
                configurator.sendStatsEnabled, configurator.recvStatsEnabled,
                configurator.returnStatsEnabled, configurator.confirmStatsEnabled,
                false, configurator.useMillis,
                new PrintWriter(csvOut), new SimpleMeterRegistry(), "metrics-prefix",
                new PrintStream(consoleOut)
        );
    }


    enum Unit {

        MS("ms"), MICROS("µs");

        private final String name;

        Unit(String unit) {
            this.name = unit;
        }
    }

    static class TestConfiguration {

        Configurator configurator;

        String[] expectedSubstringInOutput, nonExpectedSubstringInOutput;

        int unitOccurrences;

        String description;

        public TestConfiguration(String description, Configurator configurator, String[] expectedSubstringInOutput, int unitOccurrences, String... nonExpectedSubstringInOutput) {
            this.description = description;
            this.configurator = configurator;
            this.expectedSubstringInOutput = ArrayUtils.addAll(new String[]{"id", "test"}, expectedSubstringInOutput);
            this.nonExpectedSubstringInOutput = nonExpectedSubstringInOutput;
            this.unitOccurrences = unitOccurrences;
        }

        Unit unit() {
            return useMilliseconds() ? Unit.MS : Unit.MICROS;
        }

        boolean useMilliseconds() {
            return configurator.useMillis;
        }

        @Override
        public String toString() {
            return description + " (" + unit() + ")";
        }
    }

    static class Configurator {

        boolean sendStatsEnabled;
        boolean recvStatsEnabled;
        boolean returnStatsEnabled;
        boolean confirmStatsEnabled;
        boolean useMillis;


        Configurator sent(boolean enabled) {
            this.sendStatsEnabled = enabled;
            return this;
        }

        Configurator received(boolean enabled) {
            this.recvStatsEnabled = enabled;
            return this;
        }

        Configurator returned(boolean enabled) {
            this.returnStatsEnabled = enabled;
            return this;
        }

        Configurator confirmed(boolean enabled) {
            this.confirmStatsEnabled = enabled;
            return this;
        }

        Configurator useMilliseconds(boolean enabled) {
            this.useMillis = enabled;
            return this;
        }

    }
}
