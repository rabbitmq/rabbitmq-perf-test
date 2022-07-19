// Copyright (c) 2018-2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import static com.rabbitmq.perf.PerfTest.intArg;
import static com.rabbitmq.perf.Utils.strArg;

/**
 *
 */
public class PrometheusMetrics implements Metrics {

    private volatile HttpServer server;

    private volatile PrometheusMeterRegistry registry;

    public Options options() {
        Options options = new Options();
        options.addOption(new Option("mpr", "metrics-prometheus", false, "enable Prometheus metrics"));
        options.addOption(new Option("mpe", "metrics-prometheus-endpoint", true, "the HTTP metrics endpoint, default is /metrics"));
        options.addOption(new Option("mpp", "metrics-prometheus-port", true, "the port to launch the HTTP metrics endpoint on, default is 8080"));

        return options;
    }

    public void configure(ConfigurationContext context) throws Exception {
        CommandLineProxy cmd = context.cmd();
        CompositeMeterRegistry meterRegistry = context.meterRegistry();
        if (isEnabled(cmd)) {
            registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            meterRegistry.add(registry);
            int prometheusHttpEndpointPort = intArg(cmd, "mpp", 8080);
            String prometheusHttpEndpoint = strArg(cmd, "mpe", "metrics");
            prometheusHttpEndpoint = prometheusHttpEndpoint.startsWith("/") ? prometheusHttpEndpoint : "/" + prometheusHttpEndpoint;
            server = HttpServer.create(new InetSocketAddress(prometheusHttpEndpointPort), 0);
            server.createContext(prometheusHttpEndpoint, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                byte[] content = registry.scrape().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(content);
                }
            });
            server.start();
        }
    }

    public void close() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Override
    public String toString() {
        return "Prometheus Metrics";
    }
}
