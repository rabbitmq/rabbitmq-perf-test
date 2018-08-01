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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class DatadogMetricsTest {

    static final int NB_REQUESTS = 5;
    final AtomicReference<String> apiKey = new AtomicReference<>();
    final AtomicReference<String> appKey = new AtomicReference<>();
    final AtomicReference<String> content = new AtomicReference<>();
    final AtomicReference<String> description = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(NB_REQUESTS);
    int port;
    Server server;
    DatadogMetrics metrics;

    @BeforeEach
    public void init() throws Exception {
        port = TestUtils.randomNetworkPort();
        server = startMockDatadogService();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (metrics != null) {
            metrics.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void metricsShouldBeSentToDatadogHttpEndpoint() throws Exception {
        metrics = new DatadogMetrics();
        Options options = metrics.options();

        CommandLineParser parser = new GnuParser();
        CommandLine rawCmd = parser.parse(
            options,
            ("--metrics-datadog-uri http://localhost:" + port + "/datadog "
                + "--metrics-datadog-api-key APIKEY --metrics-datadog-application-key APPKEY "
                + "--metrics-datadog-step-size 1 --metrics-datadog-host-tag host --metrics-datadog-descriptions")
                .split(" ")
        );
        CommandLineProxy cmd = new CommandLineProxy(options, rawCmd, name -> null);
        CompositeMeterRegistry registry = new CompositeMeterRegistry();
        registry.config().commonTags("host", "test");
        AtomicInteger gauge = new AtomicInteger(42);
        Gauge.builder("dummy", gauge, g -> g.doubleValue()).description("this is a dummy meter").register(registry);
        metrics.configure(cmd, registry, null);

        assertTrue(latch.await(10, TimeUnit.SECONDS), NB_REQUESTS + " metrics requests should have been sent by now");

        assertEquals("APIKEY", apiKey.get());
        assertEquals("APPKEY", appKey.get());
        assertTrue(description.get().contains("\"description\":\"this is a dummy meter\""));
        assertTrue(content.get().contains("\"metric\":\"dummy\""));
        assertTrue(content.get().contains("42.0"));
        assertTrue(content.get().contains("\"host\":\"test\""));
    }

    private Server startMockDatadogService() throws Exception {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        // difference between those 2 should be high enough to avoid a warning
        threadPool.setMinThreads(2);
        threadPool.setMaxThreads(12);
        server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.setConnectors(new Connector[] { connector });

        ContextHandler context = new ContextHandler();
        context.setContextPath("/datadog");
        context.setHandler(new AbstractHandler() {

            @Override
            public void handle(String s, Request request, HttpServletRequest httpServletRequest, HttpServletResponse response)
                throws IOException {
                if (request.getParameter("api_key") != null) {
                    apiKey.set(request.getParameter("api_key"));
                }
                if (request.getParameter("application_key") != null) {
                    appKey.set(request.getParameter("application_key"));
                }

                String body = request.getReader().lines().collect(Collectors.joining("\n"));
                DatadogMetricsTest.this.content.set(body);

                if (body.contains("\"description\"")) {
                    description.set(body);
                }

                request.setHandled(true);
                latch.countDown();
            }
        });

        server.setHandler(context);

        server.setStopTimeout(1000);
        server.start();
        return server;
    }
}
