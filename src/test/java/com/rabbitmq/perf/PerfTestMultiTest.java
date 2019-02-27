// Copyright (c) 2019 Pivotal Software, Inc.  All rights reserved.
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;

public class PerfTestMultiTest {

    @Test
    public void scenarios() {
        String json = "[{'name': 'no-ack-long', 'type': 'simple', 'interval': 10000," +
                "  'params': [{'time-limit': 500}]}," +
                " {'name': 'headline-publish', 'type': 'rate-vs-latency', " +
                "  'params': [{'time-limit': 30, 'producer-count': 10, 'consumer-count': 0}]}," +
                " {'name': 'message-sizes-small', 'type': 'varying'," +
                "  'params': [{'time-limit': 30}], " +
                "  'variables': [{'name':'min-msg-size', 'values': [0, 100, 200, 500, 1000, 2000, 5000]}]}" +
                "]";
        Scenario[] scenarios = PerfTestMulti.scenarios(json, status -> {
        });
        assertThat(scenarios, arrayWithSize(3));
        assertThat(scenarios[0], is(instanceOf(SimpleScenario.class)));
        SimpleScenario simpleScenario = (SimpleScenario) scenarios[0];
        assertThat(simpleScenario.getName(), is("no-ack-long"));
        assertThat(scenarios[1], is(instanceOf(RateVsLatencyScenario.class)));
        RateVsLatencyScenario rateVsLatencyScenario = (RateVsLatencyScenario) scenarios[1];
        assertThat(rateVsLatencyScenario.getName(), is("headline-publish"));
        assertThat(scenarios[2], is(instanceOf(VaryingScenario.class)));
        VaryingScenario varyingScenario = (VaryingScenario) scenarios[2];
        assertThat(varyingScenario.getName(), is("message-sizes-small"));
        assertThat(varyingScenario.getVariables(), arrayWithSize(1));
        Variable variable = varyingScenario.getVariables()[0];
        assertThat(variable.getValues(), hasSize(7));
        double[] variableValues = new double[]{0, 100, 200, 500, 1000, 2000, 5000};
        for (int i = 0; i < variableValues.length; i++) {
            assertThat(variable.getValues().get(i).getName(), is("minMsgSize"));
            assertThat(variable.getValues().get(i).getValue(), is(variableValues[i]));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void simpleScenarioStatsJsonWriting() {
        SimpleScenarioStats stats = new SimpleScenarioStats(1000);
        stats.handleSend();
        stats.report(System.currentTimeMillis());
        stats.handleSend();
        stats.report(System.currentTimeMillis());
        String json = PerfTestMulti.toJson(stats.results());
        Map<String, Object> results = gson().fromJson(json, Map.class);
        assertThat(results, aMapWithSize(5));
        String [] rateKeys = new String[] {"send-bytes-rate", "recv-msg-rate", "send-msg-rate", "recv-bytes-rate"};
        assertThat(results.keySet(), hasItems(rateKeys));
        assertThat(results.keySet(), hasItems("samples"));
        assertThat(results.get("samples"), is(instanceOf(List.class)));
        List<Map<String, Object>> samples = (List<Map<String, Object>>) results.get("samples");
        assertThat(samples, hasSize(2));
        samples.forEach(sample -> {
            assertThat(sample, aMapWithSize(5));
            assertThat(sample.keySet(), hasItems(rateKeys));
            assertThat(sample.keySet(), hasItem("elapsed"));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void varyingScenarioStatsJsonWriting() {
        VaryingScenarioStats varyingScenarioStats = new VaryingScenarioStats();
        SimpleScenarioStats stats = varyingScenarioStats.next(Arrays.asList(new MulticastValue("minMsgSize", 100)));
        stats.report(System.currentTimeMillis());
        stats.report(System.currentTimeMillis());
        stats = varyingScenarioStats.next(Arrays.asList(new MulticastValue("minMsgSize", 200)));
        stats.report(System.currentTimeMillis());
        stats.report(System.currentTimeMillis());
        String json = PerfTestMulti.toJson(varyingScenarioStats.results());
        Map<String, Object> results = gson().fromJson(json, Map.class);
        assertThat(results, aMapWithSize(3));
        assertThat(results.keySet(), hasItems("data", "dimension-values", "dimensions"));

        assertThat(results.get("dimension-values"), is(instanceOf(Map.class)));
        Map<String, Object> dimensionValues = (Map<String, Object>) results.get("dimension-values");
        assertThat(dimensionValues, aMapWithSize(1));
        assertThat(dimensionValues.keySet(), hasItems("minMsgSize"));
        assertThat(dimensionValues.get("minMsgSize"), is(instanceOf(List.class)));
        assertThat((List<String>) dimensionValues.get("minMsgSize"), hasSize(2));
        assertThat((List<String>) dimensionValues.get("minMsgSize"), hasItems("100", "200"));

        assertThat(results.get("dimensions"), is(instanceOf(List.class)));
        List<String> dimensions = (List<String>) results.get("dimensions");
        assertThat(dimensions, hasSize(1));
        assertThat(dimensions, hasItems("minMsgSize"));

        assertThat(results.get("data"), is(instanceOf(Map.class)));
        Map<String, Object> data = (Map<String, Object>) results.get("data");
        assertThat(data, aMapWithSize(2));
        assertThat(data.keySet(), hasItems("100", "200"));

        String [] rateKeys = new String[] {"send-bytes-rate", "recv-msg-rate", "send-msg-rate", "recv-bytes-rate"};
        data.entrySet().forEach(entry -> {
            assertThat(entry.getValue(), is(instanceOf(Map.class)));
            Map<String, Object> result = (Map<String, Object>) entry.getValue();
            assertThat(result.keySet(), hasItems(rateKeys));
            assertThat(result.keySet(), hasItems("samples"));
            assertThat(result.get("samples"), is(instanceOf(List.class)));
            List<Map<String, Object>> samples = (List<Map<String, Object>>) result.get("samples");
            assertThat(samples, hasSize(2));
            samples.forEach(sample -> {
                assertThat(sample, aMapWithSize(5));
                assertThat(sample.keySet(), hasItems(rateKeys));
                assertThat(sample.keySet(), hasItem("elapsed"));
            });

        });
    }

    private Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

}
