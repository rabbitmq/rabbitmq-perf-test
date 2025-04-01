// Copyright (c) 2025 Broadcom. All Rights Reserved.
// The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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
package com.rabbitmq.perf.it;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.perf.PerfTest;
import com.rabbitmq.perf.PerfTestMulti;
import com.rabbitmq.perf.TestUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

public class PerfTestMultiIT {

  private static final Gson GSON = new Gson();

  Path specFile, resultFile;

  @BeforeEach
  void setUp(@TempDir Path directory) throws Exception {
    specFile = directory.resolve("spec.json");
    resultFile = directory.resolve("result.json");
  }

  @Test
  void simpleScenario() throws Exception {
    String spec =
        "[{'name': 'simple', 'type': 'simple', 'params':\n"
            + "[{'time-limit': 2, 'producer-count': 1, 'consumer-count': 1}]}]";
    writeSpec(spec);
    run();
    assertReceiveRatePositive();
  }

  @Test
  void queuePattern(TestInfo info) throws Exception {
    String prefix = TestUtils.randomName(info);
    int queueCount = 3;
    String spec =
        String.format(
            "[{'name': 'simple', 'type': 'simple', 'params':\n"
                + "[{"
                + "'time-limit': 2, 'producer-count': 6, 'consumer-count': 3,"
                + "'flags': 'persistent', 'auto-delete': false,"
                + "'queue-pattern': '%s', 'queue-sequence-from': 1, 'queue-sequence-to': %d"
                + "}]}]",
            prefix + "-%d", queueCount);
    writeSpec(spec);
    run();
    assertReceiveRatePositive();
    try (Connection c = TestUtils.connectionFactory().newConnection()) {
      Channel ch = c.createChannel();
      for (int i = 1; i <= queueCount; i++) {
        String queueName = String.format("%s-%d", prefix, i);
        ch.queueDeclarePassive(queueName);
        ch.queueDelete(queueName);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void assertReceiveRatePositive() throws IOException {
    String result = new String(Files.readAllBytes(resultFile), StandardCharsets.UTF_8);
    Type type = new TypeToken<Map<String, Object>>() {}.getType();
    Map<String, Object> results = GSON.fromJson(result, type);
    Map<String, Object> scenario = (Map<String, Object>) results.values().iterator().next();
    List<Map<String, Object>> samples = (List<Map<String, Object>>) scenario.get("samples");
    assertThat(samples).isNotEmpty();
    for (Map<String, Object> sample : samples) {
      if (((Number) sample.get("recv-msg-rate")).intValue() > 0) {
        return;
      }
    }
    fail("The receive rate is not positive");
  }

  private void run() throws Exception {
    String[] args = {specFile.toAbsolutePath().toString(), resultFile.toAbsolutePath().toString()};
    PerfTest.PerfTestOptions options = new PerfTest.PerfTestOptions();
    options
        .setSystemExiter(status -> {})
        .setConsoleOut(new PrintStream(new ByteArrayOutputStream()));
    PerfTestMulti.main(args, options);
  }

  private void writeSpec(String spec) throws IOException {
    Files.write(specFile, spec.getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
  }
}
