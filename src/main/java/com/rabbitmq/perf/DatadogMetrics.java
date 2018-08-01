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

import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.datadog.DatadogConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.rabbitmq.perf.PerfTest.hasOption;
import static com.rabbitmq.perf.PerfTest.strArg;
import static java.lang.Boolean.valueOf;

/**
 *
 */
public class DatadogMetrics implements Metrics {

    private volatile MeterRegistry registry;

    public Options options() {
        Options options = new Options();
        options.addOption(new Option("mda", "metrics-datadog", false, "enable Datadog metrics"));
        options.addOption(new Option("mdk", "metrics-datadog-api-key", true, "Datadog API key"));
        options.addOption(new Option("mds", "metrics-datadog-step-size", true, "step size (reporting frequency) to use "
            + "in seconds, default is 10 seconds"));
        options.addOption(new Option("mdak", "metrics-datadog-application-key", true, "Datadog application key"));
        options.addOption(new Option("mdh", "metrics-datadog-host-tag", true, "tag that will be mapped to \"host\" when shipping metrics to datadog"));
        options.addOption(new Option("mdd", "metrics-datadog-descriptions", false, "if meter descriptions should be sent to Datadog"));
        options.addOption(new Option("mdu", "metrics-datadog-uri", true, "URI to ship metrics, useful when using "
            + "a proxy, default is https://app.datadoghq.com"));
        return options;
    }

    public void configure(CommandLineProxy cmd, CompositeMeterRegistry meterRegistry, ConnectionFactory factory) throws Exception {
        if (isEnabled(cmd)) {
            Map<String, String> dataCfg = new HashMap<>();
            dataCfg.put("datadog.apiKey", strArg(cmd, "mdk", null));
            dataCfg.put("datadog.step", strArg(cmd, "mds", "10"));
            dataCfg.put("datadog.applicationKey", strArg(cmd, "mdak", null));
            dataCfg.put("datadog.hostTag", strArg(cmd, "mdh", null));
            dataCfg.put("datadog.descriptions", valueOf(hasOption(cmd, "mdd")).toString());
            dataCfg.put("datadog.uri", strArg(cmd, "mdu", null));

            DatadogConfig config = new DatadogConfig() {

                @Override
                public Duration step() {
                    return Duration.ofSeconds(Integer.valueOf(dataCfg.get("datadog.step")));
                }

                @Override
                public String get(String k) {
                    return dataCfg.get(k);
                }
            };
            registry = new DatadogMeterRegistry(
                config,
                Clock.SYSTEM,
                new NamedThreadFactory("perf-test-metrics-datadog-")
            );
            meterRegistry.add(registry);
        }
    }

    public void close() {
        if (registry != null) {
            registry.close();
        }
    }

    @Override
    public String toString() {
        return "Datadog Metrics";
    }
}
