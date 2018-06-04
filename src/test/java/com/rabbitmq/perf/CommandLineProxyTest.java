// Copyright (c) 2018-Present Pivotal Software, Inc.  All rights reserved.
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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandLineProxyTest {

    @Test
    public void delegateWorksOk() throws ParseException {
        Map<String, String> env = new HashMap<>();
        String line = "-r 100 -f persistent -f mandatory -p";
        CommandLineProxy cmd = getCommandLineProxy(env, line);

        assertEquals(
            "100",
            cmd.getOptionValue('r')
        );
        assertEquals(
            "100",
            cmd.getOptionValue("r", "")
        );
        assertEquals(
            "100",
            cmd.getOptionValue('r', "")
        );
        assertEquals(
            "default",
            cmd.getOptionValue("u", "default")
        );
        assertEquals(
            "default",
            cmd.getOptionValue('u', "default")
        );
        assertArrayEquals(
            new String[] { "persistent", "mandatory" },
            cmd.getOptionValues('f')
        );
        assertTrue(
            cmd.hasOption("p")
        );
        assertTrue(
            cmd.hasOption('p')
        );
    }

    @Test
    public void envVariablesOverrideAndAreUsed() throws ParseException {
        Map<String, String> env = new HashMap<>();
        env.put("RATE", "200");
        env.put("PRODUCER_CHANNEL_COUNT", "10");

        String line = "-r 100";
        CommandLineProxy cmd = getCommandLineProxy(env, line);

        assertEquals(
            "200",
            cmd.getOptionValue('r')
        );
        assertEquals(
            "10",
            cmd.getOptionValue('X')
        );
    }

    @Test
    public void envVariablesSupportTypeString() throws ParseException {
        Map<String, String> env = new HashMap<>();
        env.put("RATE", "200");
        CommandLineProxy cmd = getCommandLineProxy(env, "");
        assertEquals(
            "200",
            cmd.getOptionValue('r')
        );
    }

    @Test
    public void envVariablesSupportTypeStringArray() throws ParseException {
        Map<String, String> env = new HashMap<>();
        env.put("FLAG", "mandatory,persistent");
        CommandLineProxy cmd = getCommandLineProxy(env, "");
        assertArrayEquals(
            new String[] { "mandatory", "persistent" },
            cmd.getOptionValues('f')
        );
    }

    @Test
    public void envVariablesSupportTypeBooleanFalse() throws ParseException {
        Map<String, String> env = new HashMap<>();
        env.put("AUTO_DELETE", "FALSE");
        CommandLineProxy cmd = getCommandLineProxy(env, "");
        assertFalse(
            cmd.hasOption("ad")
        );
    }

    @Test
    public void envVariablesSupportTypeBooleanTrue() throws ParseException {
        Map<String, String> env = new HashMap<>();
        env.put("AUTO_DELETE", "TRUE");
        CommandLineProxy cmd = getCommandLineProxy(env, "");
        assertTrue(
            cmd.hasOption("ad")
        );
    }

    private CommandLineProxy getCommandLineProxy(Map<String, String> env, String line) throws ParseException {
        Function<String, String> envLookup = variable -> env.get(variable);
        Options options = PerfTest.getOptions();
        CommandLineParser parser = new GnuParser();
        CommandLine rawCmd = parser.parse(
            options,
            line.split(" ")
        );
        return new CommandLineProxy(
            options,
            rawCmd,
            PerfTest.LONG_OPTION_TO_ENVIRONMENT_VARIABLE.andThen(envLookup)
        );
    }
}
