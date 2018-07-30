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
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.rabbitmq.perf.PerfTest.intArg;
import static com.rabbitmq.perf.PerfTest.strArg;

/**
 *
 */
public class PrometheusMetrics implements Metrics {

    private volatile Server server;

    private volatile PrometheusMeterRegistry registry;

    public Options options() {
        Options options = new Options();
        options.addOption(new Option("mpr", "metrics-prometheus", false, "enable Prometheus metrics"));
        options.addOption(new Option("mpe", "metrics-prometheus-endpoint", true, "the HTTP metrics endpoint, default is /metrics"));
        options.addOption(new Option("mpp", "metrics-prometheus-port", true, "the port to launch the HTTP metrics endpoint on, default is 8080"));
        return options;
    }

    public void configure(CommandLineProxy cmd, CompositeMeterRegistry meterRegistry, ConnectionFactory factory) throws Exception {
        if (isEnabled(cmd)) {
            registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            meterRegistry.add(registry);
            int prometheusHttpEndpointPort = intArg(cmd, "mpp", 8080);
            String prometheusHttpEndpoint = strArg(cmd, "mpe", "metrics");
            prometheusHttpEndpoint = prometheusHttpEndpoint.startsWith("/") ? prometheusHttpEndpoint : "/" + prometheusHttpEndpoint;
            QueuedThreadPool threadPool = new QueuedThreadPool();
            // difference between those 2 should be high enough to avoid a warning
            threadPool.setMinThreads(2);
            threadPool.setMaxThreads(12);
            server = new Server(threadPool);
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(prometheusHttpEndpointPort);
            server.setConnectors(new Connector[] { connector });

            ContextHandler context = new ContextHandler();
            context.setContextPath(prometheusHttpEndpoint);
            context.setHandler(new AbstractHandler() {

                @Override
                public void handle(String s, Request request, HttpServletRequest httpServletRequest, HttpServletResponse response)
                    throws IOException {
                    String scraped = registry.scrape();

                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentLength(scraped.length());
                    response.setContentType("text/plain");

                    response.getWriter().print(scraped);

                    request.setHandled(true);
                }
            });

            server.setHandler(context);

            server.setStopTimeout(1000);
            server.start();
        }
    }

    public void close() throws Exception {
        if (server != null) {
            server.stop();
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
