// Copyright (c) 2019-2023 Broadcom. All Rights Reserved.
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
package com.rabbitmq.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class PerfTestMultiTest {

  @Test
  public void scenarios() {
    String json =
        "[{'name': 'no-ack-long', 'type': 'simple', 'interval': 10000,"
            + "  'params': [{'time-limit': 500}]},"
            + " {'name': 'headline-publish', 'type': 'rate-vs-latency', "
            + "  'params': [{'time-limit': 30, 'producer-count': 10, 'consumer-count': 0}]},"
            + " {'name': 'message-sizes-small', 'type': 'varying',"
            + "  'params': [{'time-limit': 30}], "
            + "  'variables': [{'name':'min-msg-size', 'values': [0, 100, 200, 500, 1000, 2000, 5000]}]}"
            + "]";
    Scenario[] scenarios = PerfTestMulti.scenarios(json, status -> {});
    assertThat(scenarios).hasSize(3);
    assertThat(scenarios[0]).isInstanceOf(SimpleScenario.class);
    SimpleScenario simpleScenario = (SimpleScenario) scenarios[0];
    assertThat(simpleScenario.getName()).isEqualTo("no-ack-long");
    assertThat(scenarios[1]).isInstanceOf(RateVsLatencyScenario.class);
    RateVsLatencyScenario rateVsLatencyScenario = (RateVsLatencyScenario) scenarios[1];
    assertThat(rateVsLatencyScenario.getName()).isEqualTo("headline-publish");
    assertThat(scenarios[2]).isInstanceOf(VaryingScenario.class);
    VaryingScenario varyingScenario = (VaryingScenario) scenarios[2];
    assertThat(varyingScenario.getName()).isEqualTo("message-sizes-small");
    assertThat(varyingScenario.getVariables()).hasSize(1);
    Variable variable = varyingScenario.getVariables()[0];
    assertThat(variable.getValues()).hasSize(7);
    double[] variableValues = new double[] {0, 100, 200, 500, 1000, 2000, 5000};
    for (int i = 0; i < variableValues.length; i++) {
      assertThat(variable.getValues().get(i).getName()).isEqualTo("minMsgSize");
      assertThat(variable.getValues().get(i).getValue()).isEqualTo(variableValues[i]);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void simpleScenarioStatsJsonWriting() {
    SimpleScenarioStats stats = new SimpleScenarioStats(1000);
    stats.handleSend();
    stats.report(System.nanoTime());
    stats.handleSend();
    stats.report(System.nanoTime());
    String json = PerfTestMulti.toJson(stats.results());
    Map<String, Object> results = gson().fromJson(json, Map.class);
    assertThat(results).hasSize(5);
    String[] rateKeys =
        new String[] {"send-bytes-rate", "recv-msg-rate", "send-msg-rate", "recv-bytes-rate"};
    assertThat(results.keySet()).contains(rateKeys);
    assertThat(results.keySet()).contains("samples");
    assertThat(results.get("samples")).isInstanceOf(List.class);
    List<Map<String, Object>> samples = (List<Map<String, Object>>) results.get("samples");
    assertThat(samples).hasSize(2);
    samples.forEach(
        sample -> {
          assertThat(sample).hasSize(5);
          assertThat(sample.keySet()).contains(rateKeys).contains("elapsed");
        });
  }

  @Test
  @SuppressWarnings("unchecked")
  public void varyingScenarioStatsJsonWriting() {
    VaryingScenarioStats varyingScenarioStats = new VaryingScenarioStats();
    SimpleScenarioStats stats =
        varyingScenarioStats.next(Arrays.asList(new MulticastValue("minMsgSize", 100)));
    stats.report(System.nanoTime());
    stats.report(System.nanoTime());
    stats = varyingScenarioStats.next(Arrays.asList(new MulticastValue("minMsgSize", 200)));
    stats.report(System.nanoTime());
    stats.report(System.nanoTime());
    String json = PerfTestMulti.toJson(varyingScenarioStats.results());
    Map<String, Object> results = gson().fromJson(json, Map.class);
    assertThat(results).hasSize(3).containsKeys("data", "dimension-values", "dimensions");

    assertThat(results.get("dimension-values")).isInstanceOf(Map.class);
    Map<String, Object> dimensionValues = (Map<String, Object>) results.get("dimension-values");
    assertThat(dimensionValues).hasSize(1).containsKeys("minMsgSize");
    assertThat(dimensionValues.get("minMsgSize")).isInstanceOf(List.class);
    assertThat((List<String>) dimensionValues.get("minMsgSize")).hasSize(2).contains("100", "200");

    assertThat(results.get("dimensions")).isInstanceOf(List.class);
    List<String> dimensions = (List<String>) results.get("dimensions");
    assertThat(dimensions).hasSize(1).contains("minMsgSize");

    assertThat(results.get("data")).isInstanceOf(Map.class);
    Map<String, Object> data = (Map<String, Object>) results.get("data");
    assertThat(data).hasSize(2).containsKeys("100", "200");

    String[] rateKeys =
        new String[] {"send-bytes-rate", "recv-msg-rate", "send-msg-rate", "recv-bytes-rate"};
    data.entrySet()
        .forEach(
            entry -> {
              assertThat(entry.getValue()).isInstanceOf(Map.class);
              Map<String, Object> result = (Map<String, Object>) entry.getValue();
              assertThat(result).containsKeys(rateKeys).containsKey("samples");
              assertThat(result.get("samples")).isInstanceOf(List.class);
              List<Map<String, Object>> samples = (List<Map<String, Object>>) result.get("samples");
              assertThat(samples).hasSize(2);
              samples.forEach(
                  sample -> {
                    assertThat(sample).hasSize(5).containsKeys(rateKeys).containsKey("elapsed");
                  });
            });
  }

  private Gson gson() {
    return new GsonBuilder().setPrettyPrinting().create();
  }
}
