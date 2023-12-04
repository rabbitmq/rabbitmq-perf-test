// Copyright (c) 2019-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom
// Inc. and/or its subsidiaries.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
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

import static com.rabbitmq.perf.PerfTest.getOptions;
import static com.rabbitmq.perf.PerfTest.getParser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Function;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class CliTest {

  static final Function<String, String> NO_OP_ARGUMENT_LOOKUP = in -> null;

  static CommandLineProxy cmd(String commandLine) throws ParseException {
    Options options = getOptions();
    CommandLineParser parser = getParser();
    CommandLine rawCmd = parser.parse(options, commandLine.split(" "));
    CommandLineProxy cmd = new CommandLineProxy(options, rawCmd, NO_OP_ARGUMENT_LOOKUP);
    return cmd;
  }

  @ParameterizedTest
  @CsvSource({"-d testId, testId", "--id testId, testId", "'', default-test-id"})
  public void strArg(String commandLine, String expectedArgumentValue) throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    String value = Utils.strArg(cmd, 'd', "default-test-id");
    assertEquals(expectedArgumentValue, value);
  }

  @ParameterizedTest
  @CsvSource({"-mh, true", "--metrics-help, true", "'', false"})
  public void hasOptionString(String commandLine, boolean expectedArgumentValue)
      throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    assertEquals(expectedArgumentValue, cmd.hasOption("mh"));
  }

  @ParameterizedTest
  @CsvSource({"-?, true", "--help, true", "'', false"})
  public void hasOptionChar(String commandLine, boolean expectedArgumentValue)
      throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    assertEquals(expectedArgumentValue, cmd.hasOption('?'));
  }

  @ParameterizedTest
  @CsvSource({"-i 10, 10", "--interval 10, 10", "'', 1"})
  public void intArg(String commandLine, int expectedArgumentValue) throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    int value = PerfTest.intArg(cmd, 'i', 1);
    assertEquals(expectedArgumentValue, value);
  }

  @ParameterizedTest
  @CsvSource({"-r 100, 100", "--rate 100, 100", "'', 0"})
  public void floatArg(String commandLine, int expectedArgumentValue) throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    float value = PerfTest.floatArg(cmd, 'r', 0.0f);
    assertEquals(expectedArgumentValue, value);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "-f persistent; persistent", "-f persistent -f mandatory; persistent mandatory",
        "-flag persistent; persistent", "-flag persistent -flag mandatory; persistent mandatory",
        "''; ''"
      },
      delimiter = ';')
  public void lstArg(String commandLine, String expectedAsString) throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    List<String> value = PerfTest.lstArg(cmd, 'f');
    String[] expectedValues = expectedAsString.split(" ");
    if (expectedValues.length > 0 && !expectedValues[0].isEmpty()) {
      for (String expectedValue : expectedValues) {
        assertThat(value).contains(expectedValue);
      }
    } else {
      assertThat(value).hasSize(0);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "-ad true, true", "--auto-delete true, true",
    "-ad false, false", "--auto-delete false, false",
    "'', true"
  })
  public void boolArg(String commandLine, boolean expectedArgumentValue) throws ParseException {
    CommandLineProxy cmd = cmd(commandLine);
    boolean value = PerfTest.boolArg(cmd, "ad", true);
    assertEquals(expectedArgumentValue, value);
  }
}
