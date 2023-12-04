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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rabbitmq.perf.Metrics.ConfigurationContext;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.commons.cli.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** */
public class DatadogMetricsTest {

  static final int NB_REQUESTS = 5;
  final AtomicReference<String> apiKey = new AtomicReference<>();
  final AtomicReference<String> appKey = new AtomicReference<>();
  final AtomicReference<String> content = new AtomicReference<>();
  final AtomicReference<String> description = new AtomicReference<>();
  final CountDownLatch latch = new CountDownLatch(NB_REQUESTS);
  int port;
  HttpServer server;
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
      server.stop(0);
    }
  }

  @Test
  public void metricsShouldBeSentToDatadogHttpEndpoint() throws Exception {
    metrics = new DatadogMetrics();
    Options options = metrics.options();

    CommandLineParser parser = new DefaultParser();
    CommandLine rawCmd =
        parser.parse(
            options,
            ("--metrics-datadog-uri http://localhost:"
                    + port
                    + "/datadog "
                    + "--metrics-datadog-api-key APIKEY --metrics-datadog-application-key APPKEY "
                    + "--metrics-datadog-step-size 1 --metrics-datadog-host-tag host --metrics-datadog-descriptions")
                .split(" "));
    CommandLineProxy cmd = new CommandLineProxy(options, rawCmd, name -> null);
    CompositeMeterRegistry registry = new CompositeMeterRegistry();
    registry.config().commonTags("host", "test");
    AtomicInteger gauge = new AtomicInteger(42);
    Gauge.builder("dummy", gauge, g -> g.doubleValue())
        .description("this is a dummy meter")
        .register(registry);
    metrics.configure(new ConfigurationContext(cmd, registry, null, null, null, null));

    assertTrue(
        latch.await(10, TimeUnit.SECONDS),
        NB_REQUESTS + " metrics requests should have been sent by now");

    assertEquals("APIKEY", apiKey.get());
    assertEquals("APPKEY", appKey.get());
    assertTrue(description.get().contains("\"description\":\"this is a dummy meter\""));
    assertTrue(content.get().contains("\"metric\":\"dummy\""));
    assertTrue(content.get().contains("42.0"));
    assertTrue(content.get().contains("\"host\":\"test\""));
  }

  private HttpServer startMockDatadogService() throws Exception {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/datadog",
        exchange -> {
          Map<String, String> parameters =
              Arrays.stream(exchange.getRequestURI().getQuery().split("&"))
                  .map(parameter -> parameter.split("="))
                  .collect(
                      Collectors.toMap(
                          parameterNameValue -> parameterNameValue[0],
                          parameterNameValue -> parameterNameValue[1]));
          if (parameters.get("api_key") != null) {
            apiKey.set(parameters.get("api_key"));
          }
          if (parameters.get("application_key") != null) {
            appKey.set(parameters.get("application_key"));
          }
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String body = reader.lines().collect(Collectors.joining("\n"));
            DatadogMetricsTest.this.content.set(body);

            if (body.contains("\"description\"")) {
              description.set(body);
            }
          }
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();

          latch.countDown();
        });
    server.start();
    return server;
  }
}
