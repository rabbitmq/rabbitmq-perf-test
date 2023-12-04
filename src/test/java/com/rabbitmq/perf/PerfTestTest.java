// Copyright (c) 2018-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom
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

import static com.rabbitmq.perf.PerfTest.ENVIRONMENT_VARIABLE_LOOKUP;
import static com.rabbitmq.perf.PerfTest.ENVIRONMENT_VARIABLE_PREFIX;
import static com.rabbitmq.perf.PerfTest.LONG_OPTION_TO_ENVIRONMENT_VARIABLE;
import static com.rabbitmq.perf.PerfTest.convertKeyValuePairs;
import static com.rabbitmq.perf.PerfTest.getParser;
import static com.rabbitmq.perf.PerfTest.parsePublishingInterval;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

import com.rabbitmq.perf.PerfTest.PerfTestOptions;
import com.rabbitmq.perf.PerfTest.SystemExiter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** */
public class PerfTestTest {

  ByteArrayOutputStream out, err;
  RecordingSystemExiter systemExiter;

  @BeforeEach
  void init() {
    out = new ByteArrayOutputStream();
    err = new ByteArrayOutputStream();
    systemExiter = new RecordingSystemExiter();
  }

  @Test
  void getNioNbThreadsAndExecutorSize() {
    Object[][] parameters = {
      {
        4,
        -1,
        4,
        4 + 2,
        "2 extra threads for executor when only number of threads is specified",
        true
      },
      {4, 2, 4, 6, "2 extra threads for executor when specified number < nb threads", true},
      {-1, 4, 2, 4, "appropriate nb threads (-2) when only executor size is specified", true},
      {
        -1,
        2,
        -1,
        -1,
        "executor should be large enough for IO threads + a couple of extra threads",
        false
      },
    };
    for (Object[] parameter : parameters) {
      String message = (String) parameter[4];
      boolean passes = (boolean) parameter[5];
      try {
        int[] nbThreadsAndExecutorSize =
            PerfTest.getNioNbThreadsAndExecutorSize((int) parameter[0], (int) parameter[1]);
        assertArrayEquals(
            new int[] {(int) parameter[2], (int) parameter[3]}, nbThreadsAndExecutorSize, message);
        if (!passes) {
          fail(message + " (test should fail)");
        }
      } catch (IllegalArgumentException e) {
        if (passes) {
          fail(message + " (test shouldn't fail)");
        }
      }
    }
  }

  @Test
  public void longOptionToEnvironmentVariable() {
    String[][] parameters = {
      {"queue", "QUEUE"},
      {"routing-key", "ROUTING_KEY"},
      {"random-routing-key", "RANDOM_ROUTING_KEY"},
      {"skip-binding-queues", "SKIP_BINDING_QUEUES"},
    };
    for (String[] parameter : parameters) {
      assertEquals(parameter[1], LONG_OPTION_TO_ENVIRONMENT_VARIABLE.apply(parameter[0]));
    }
  }

  @Test
  public void stopLine() {
    String[][] parameters =
        new String[][] {
          {"reason1=1", "test stopped (reason1)"},
          {"reason1=2", "test stopped (reason1 [2])"},
          {"reason1=1 reason2=1", "test stopped (reason1, reason2)"},
          {"reason1=2 reason2=1", "test stopped (reason1 [2], reason2)"},
          {"reason1=2 reason2=1 reason3=1", "test stopped (reason1 [2], reason2, reason3)"}
        };
    for (String[] parameter : parameters) {
      assertThat(PerfTest.stopLine(reasons(parameter[0]))).isEqualTo(parameter[1]);
    }
  }

  Map<String, Integer> reasons(String reasons) {
    String[] reasonsArray = reasons.split(" ");
    Map<String, Integer> result = new HashMap<>();
    for (int i = 0; i < reasonsArray.length; i++) {
      String[] reason = reasonsArray[i].split("=");
      result.put(reason[0], Integer.parseInt(reason[1]));
    }
    return result;
  }

  @Test
  void convertPostProcessKeyValuePairs() {
    assertThat(convertKeyValuePairs("x-queue-type=quorum,max-length-bytes=100000"))
        .hasSize(2)
        .containsEntry("x-queue-type", "quorum")
        .containsEntry("max-length-bytes", 100000L);
    assertThat(convertKeyValuePairs("x-dead-letter-exchange=,x-queue-type=quorum"))
        .hasSize(2)
        .containsEntry("x-dead-letter-exchange", "")
        .containsEntry("x-queue-type", "quorum");
    assertThat(convertKeyValuePairs("x-dead-letter-exchange=amq.default,x-queue-type=quorum"))
        .hasSize(2)
        .containsEntry("x-dead-letter-exchange", "")
        .containsEntry("x-queue-type", "quorum");
    assertThat(convertKeyValuePairs("max-length-bytes=5,x-queue-type=quorum,max-length-bytes=10"))
        .hasSize(2)
        .containsEntry("x-queue-type", "quorum")
        .containsEntry("max-length-bytes", 10L);
    assertThat(convertKeyValuePairs("x-cancel-on-ha-failover=true,x-priority=10"))
        .hasSize(2)
        .containsEntry("x-cancel-on-ha-failover", true)
        .containsEntry("x-priority", 10L);
    assertThat(convertKeyValuePairs("x-cancel-on-ha-failover=false,x-priority=10"))
        .hasSize(2)
        .containsEntry("x-cancel-on-ha-failover", false)
        .containsEntry("x-priority", 10L);
  }

  @Test
  void convertPostProcessKeyValuePairsWithList() {
    assertThat(convertKeyValuePairs(asList("x-queue-type=quorum", "max-length-bytes=100000")))
        .hasSize(2)
        .containsEntry("x-queue-type", "quorum")
        .containsEntry("max-length-bytes", 100000L);
    assertThat(convertKeyValuePairs(asList("x-dead-letter-exchange=", "x-queue-type=quorum")))
        .hasSize(2)
        .containsEntry("x-dead-letter-exchange", "")
        .containsEntry("x-queue-type", "quorum");
    assertThat(
            convertKeyValuePairs(
                asList("x-dead-letter-exchange=amq.default", "x-queue-type=quorum")))
        .hasSize(2)
        .containsEntry("x-dead-letter-exchange", "")
        .containsEntry("x-queue-type", "quorum");
    assertThat(
            convertKeyValuePairs(
                asList("max-length-bytes=5", "x-queue-type=quorum", "max-length-bytes=10")))
        .hasSize(2)
        .containsEntry("x-queue-type", "quorum")
        .containsEntry("max-length-bytes", 10L);
    assertThat(
            convertKeyValuePairs(
                asList("max-length-bytes=5,x-queue-type=quorum", "max-length-bytes=10")))
        .hasSize(2)
        .containsEntry("x-queue-type", "quorum")
        .containsEntry("max-length-bytes", 10L);
  }

  @ParameterizedTest
  @CsvSource({
    "1,1000",
    "0.1,100",
    "0.5,500",
    "0.55,550",
    "2,2000",
    "15,15000",
  })
  void parsePublishingIntervalOK(String input, long expectedMs) {
    assertThat(parsePublishingInterval(input).toMillis()).isEqualTo(expectedMs);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.09", "-1", "0"})
  void parsePublishingIntervalKO(String input) {
    assertThatThrownBy(() -> parsePublishingInterval(input))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multicastParamsNoArgumentShouldPass() throws Exception {
    multicastParams("");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsQuorumOrStreamNotBoth() throws Exception {
    multicastParams("--stream-queue --quorum-queue");
    assertThat(systemExiter.status()).isEqualTo(1);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isNotBlank();
  }

  @Test
  void multicastParamsQuorumQueueFlagSetAppropriateArgumentsAndFlags() throws Exception {
    MulticastParams p = multicastParams("--quorum-queue");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getFlags()).isNotNull().contains("persistent");
    assertThat(p.getQueueArguments()).isNotNull().containsEntry("x-queue-type", "quorum");
    assertThat(p.isAutoDelete()).isFalse();

    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsStreamQueueFlagSetAppropriateArgumentsAndFlags() throws Exception {
    MulticastParams p = multicastParams("--stream-queue");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getFlags()).isNotNull().contains("persistent");
    assertThat(p.getQueueArguments()).isNotNull().containsEntry("x-queue-type", "stream");
    assertThat(p.isAutoDelete()).isFalse();
    assertThat(p.getConsumerPrefetch()).isEqualTo(200);
    assertThat(p.getQueueArguments()).containsEntry("x-max-length-bytes", 20_000_000_000L);
    assertThat(p.getQueueArguments())
        .containsEntry("x-stream-max-segment-size-bytes", 500_000_000L);

    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsMaxLengthBytesOk() throws Exception {
    MulticastParams p = multicastParams("--max-length-bytes 10gb");
    assertThat(p.getQueueArguments()).containsEntry("x-max-length-bytes", 10_000_000_000L);
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsMaxLengthBytesOverridesDefaultFromStreamFlag() throws Exception {
    MulticastParams p = multicastParams("--stream-queue --max-length-bytes 10gb");
    assertThat(p.getQueueArguments()).isNotNull().containsEntry("x-queue-type", "stream");
    assertThat(p.getQueueArguments()).containsEntry("x-max-length-bytes", 10_000_000_000L);
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsMaxLengthBytesSetToZeroMeansNoLimit() throws Exception {
    MulticastParams p = multicastParams("--max-length-bytes 0");
    assertThat(p.getQueueArguments()).doesNotContainKey("x-max-length-bytes");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsMaxLengthBytesSetToZeroMeansNoLimitEvenWithStreamFlag() throws Exception {
    MulticastParams p = multicastParams("--stream-queue --max-length-bytes 0");
    assertThat(p.getQueueArguments()).isNotNull().containsEntry("x-queue-type", "stream");
    assertThat(p.getQueueArguments()).doesNotContainKey("x-max-length-bytes");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsStreamMaxSegmentSizeBytesOk() throws Exception {
    MulticastParams p = multicastParams("--stream-max-segment-size-bytes 200mb");
    assertThat(p.getQueueArguments())
        .containsEntry("x-stream-max-segment-size-bytes", 200_000_000L);
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsStreamMaxSegmentSizeBytesOverridesDefaultFromStreamFlag() throws Exception {
    MulticastParams p = multicastParams("--stream-queue --stream-max-segment-size-bytes 200mb");
    assertThat(p.getQueueArguments()).isNotNull().containsEntry("x-queue-type", "stream");
    assertThat(p.getQueueArguments())
        .containsEntry("x-stream-max-segment-size-bytes", 200_000_000L);
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsMaxLengthBytesMustBeGreatherThanStreamMaxSegmentSize() throws Exception {
    multicastParams("--max-length-bytes 1gb " + "--stream-max-segment-size-bytes 2gb");
    assertThat(systemExiter.notCalled()).isFalse();
    assertThat(systemExiter.status()).isEqualTo(1);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isNotBlank();
  }

  @Test
  void multicastParamsQueueLeaderLocatorBalancedOk() throws Exception {
    MulticastParams p = multicastParams("--leader-locator balanced");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getQueueArguments())
        .isNotNull()
        .containsEntry("x-queue-leader-locator", "balanced");
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsQueueLeaderLocatorClientLocalOk() throws Exception {
    MulticastParams p = multicastParams("--leader-locator client-local");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getQueueArguments())
        .isNotNull()
        .containsEntry("x-queue-leader-locator", "client-local");
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsQueueLeaderLocatorInvalidValueFails() throws Exception {
    multicastParams("--leader-locator foo");
    assertThat(systemExiter.notCalled()).isFalse();
    assertThat(systemExiter.status()).isEqualTo(1);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isNotBlank();
  }

  @Test
  void multicastParamsMaxAgeValidShouldSetQueueArgumentInSeconds() throws Exception {
    MulticastParams p = multicastParams("--max-age P5DT8H");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getQueueArguments())
        .isNotNull()
        .containsEntry("x-max-age", (5 * 24 * 60 * 60 + 8 * 60 * 60) + "s");

    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsMaxAgeInvalidShouldExit() throws Exception {
    multicastParams("--max-age foo");

    assertThat(systemExiter.notCalled()).isFalse();
    assertThat(systemExiter.status()).isEqualTo(1);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isNotBlank();
  }

  @ParameterizedTest
  @ValueSource(strings = {"first", "last", "next"})
  void multicastParamsStreamConsumerOffsetFixedValuesOk(String offset) throws Exception {
    MulticastParams p = multicastParams("--stream-consumer-offset " + offset);
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getConsumerArguments()).isNotNull().containsEntry("x-stream-offset", offset);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsStreamConsumerOffsetLongForOffset() throws Exception {
    MulticastParams p = multicastParams("--stream-consumer-offset 42");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getConsumerArguments()).isNotNull().containsEntry("x-stream-offset", 42L);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsStreamConsumerOffsetTimestamp() throws Exception {
    MulticastParams p = multicastParams("--stream-consumer-offset 2020-06-03T07:45:54Z");
    assertThat(systemExiter.notCalled()).isTrue();
    assertThat(p.getConsumerArguments())
        .isNotNull()
        .containsEntry("x-stream-offset", new Date(1591170354000L));
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isBlank();
  }

  @Test
  void multicastParamsStreamConsumerOffsetInvalidValueShouldFail() throws Exception {
    multicastParams("--stream-consumer-offset foo");
    assertThat(systemExiter.notCalled()).isFalse();
    assertThat(systemExiter.status()).isEqualTo(1);
    assertThat(out.toString()).isBlank();
    assertThat(err.toString()).isNotBlank();
  }

  private MulticastParams multicastParams(String args) throws Exception {
    PerfTestOptions perfTestOptions = new PerfTestOptions();
    perfTestOptions.setConsoleOut(new PrintStream(out));
    perfTestOptions.setConsoleErr(new PrintStream(err));
    perfTestOptions.setSystemExiter(systemExiter);
    Options options = PerfTest.getOptions();
    CommandLineParser parser = getParser();
    CommandLine rawCmd = parser.parse(options, args.split(" "));
    CommandLineProxy cmd =
        new CommandLineProxy(
            options,
            rawCmd,
            LONG_OPTION_TO_ENVIRONMENT_VARIABLE
                .andThen(ENVIRONMENT_VARIABLE_PREFIX)
                .andThen(ENVIRONMENT_VARIABLE_LOOKUP));
    return PerfTest.multicastParams(cmd, Collections.emptyList(), perfTestOptions);
  }

  private static class RecordingSystemExiter implements SystemExiter {

    private AtomicInteger exitCount = new AtomicInteger(0);
    private AtomicInteger lastStatus = new AtomicInteger(-1);

    @Override
    public void exit(int status) {
      exitCount.incrementAndGet();
      lastStatus.set(status);
    }

    boolean notCalled() {
      return exitCount.get() == 0;
    }

    int status() {
      return lastStatus.get();
    }
  }
}
