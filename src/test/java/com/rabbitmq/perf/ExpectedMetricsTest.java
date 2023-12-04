// Copyright (c) 2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc.
// and/or its subsidiaries.
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

import static com.rabbitmq.perf.Assertions.assertThat;
import static com.rabbitmq.perf.ExpectedMetrics.METRICS_CONSUMED;
import static com.rabbitmq.perf.ExpectedMetrics.METRICS_PUBLISHED;
import static com.rabbitmq.perf.StartListener.Type.CONSUMER;
import static com.rabbitmq.perf.StartListener.Type.PRODUCER;
import static java.util.Arrays.asList;

import com.rabbitmq.perf.ValueIndicator.Listener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ExpectedMetricsTest {

  MulticastParams params;
  MeterRegistry registry;
  Map<String, Object> exposedMetrics;
  String prefix = "";
  ExpectedMetrics expectedMetrics;

  @BeforeEach
  void init() {
    params = new MulticastParams();
    registry = new SimpleMeterRegistry();
    exposedMetrics = new HashMap<>();
  }

  @Test
  void noMetricsIfNotRateInstructions() {
    expectedMetrics();
    assertThat(registry).isEmpty();
  }

  @Test
  void exposedMetricsTakesPrecedenceAndStaysTheSame() {
    params.setProducerRateLimit(1);
    params.setConsumerRateLimit(50);
    exposedMetrics.put(METRICS_PUBLISHED, 200);
    exposedMetrics.put(METRICS_CONSUMED, 100);
    expectedMetrics = expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED).gauge(METRICS_PUBLISHED).hasValue(200);
    assertThat(registry).has(METRICS_CONSUMED).gauge(METRICS_CONSUMED).hasValue(100);
    expectedMetrics.register(new FixedValueIndicator<>(1.0f), null);
    expectedMetrics.agentStarted(PRODUCER);
    expectedMetrics.agentStarted(CONSUMER);
    assertThat(registry).gauge(METRICS_PUBLISHED).hasValue(200);
    assertThat(registry).gauge(METRICS_CONSUMED).hasValue(100);
  }

  @Test
  void randomExposedMetricsAreExposed() {
    String name = "expected_confirm_latency";
    exposedMetrics.put(name, 0.1);
    expectedMetrics = expectedMetrics();
    assertThat(registry).has(name).gauge(name).hasValue(0.1);
  }

  @Test
  void producerRateLimitShouldCreateMetrics() {
    params.setProducerRateLimit(1);
    expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED);
  }

  @Test
  void variableRatesShouldCreateMetrics() {
    params.setPublishingRates(asList("10:100", "50:10"));
    expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED);
  }

  @Test
  void publishingIntervalShouldCreateMetrics() {
    params.setPublishingInterval(Duration.ofSeconds(2));
    expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED);
  }

  @Test
  void consumerRateLimitShouldCreateMetrics() {
    params.setConsumerRateLimit(1);
    expectedMetrics();
    assertThat(registry).has(METRICS_CONSUMED);
  }

  @Test
  void metricsShouldChangeWhenPublishersStartedWithFixedRate() {
    float rate = 100;
    params.setProducerRateLimit(rate);
    expectedMetrics = expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED);
    Gauge metrics = registry.get(METRICS_PUBLISHED).gauge();
    assertThat(metrics).hasValue(0);
    expectedMetrics.register(new FixedValueIndicator<>(rate), null);
    assertThat(metrics).hasValue(0);
    expectedMetrics.agentStarted(PRODUCER);
    assertThat(metrics).hasValue(rate);
    expectedMetrics.agentStarted(PRODUCER);
    assertThat(metrics).hasValue(rate * 2);
    expectedMetrics.agentStarted(PRODUCER);
    assertThat(metrics).hasValue(rate * 3);
  }

  @Test
  void metricsShouldChangeWhenPublishersStartedWithPublishingInterval() {
    Duration publishingInterval = Duration.ofSeconds(2);
    params.setPublishingInterval(publishingInterval);
    expectedMetrics = expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED);
    Gauge metrics = registry.get(METRICS_PUBLISHED).gauge();
    assertThat(metrics).hasValue(0);
    expectedMetrics.register(new FixedValueIndicator<>(-1.0f), publishingInterval);
    assertThat(metrics).hasValue(0);
    expectedMetrics.agentStarted(PRODUCER);
    assertThat(metrics).hasValue(0.5);
    expectedMetrics.agentStarted(PRODUCER);
    assertThat(metrics).hasValue(1);
    expectedMetrics.agentStarted(PRODUCER);
    assertThat(metrics).hasValue(1.5);
  }

  @Test
  void metricsShouldChangeWhenPublishersStartedVariableRates() {
    // [RATE]:[DURATION] syntax, but it does not matter for this test
    params.setPublishingRates(asList("10:30", "20:30"));
    expectedMetrics = expectedMetrics();
    assertThat(registry).has(METRICS_PUBLISHED);
    Gauge metrics = registry.get(METRICS_PUBLISHED).gauge();
    assertThat(metrics).hasValue(0);
    AtomicReference<Float> rate = new AtomicReference<>(10.0f);
    AtomicReference<Listener<Float>> listener = new AtomicReference<>();
    ValueIndicator<Float> rateIndicator =
        new ValueIndicator<Float>() {
          @Override
          public Float getValue() {
            return rate.get();
          }

          @Override
          public void register(Listener<Float> lst) {
            listener.set(lst);
          }

          @Override
          public void start() {}

          @Override
          public boolean isVariable() {
            return true;
          }

          @Override
          public List<Float> values() {
            return null;
          }
        };
    expectedMetrics.register(rateIndicator, null);
    assertThat(metrics).hasValue(0);
    int producerCount = 10;
    IntStream.range(0, producerCount)
        .forEach(
            i -> {
              expectedMetrics.agentStarted(PRODUCER);
              assertThat(metrics).hasValue(rate.get() * (i + 1));
            });
    // simulate the value change
    rate.set(20.0f);
    listener.get().valueChanged(10.0f, rate.get()); // supposed to be called by ValueIndicator
    assertThat(metrics).hasValue(rate.get() * producerCount);
  }

  @Test
  void metricsShouldChangeWhenConsumersStartedWithFixedRate() {
    float rate = 100;
    params.setConsumerRateLimit(rate);
    expectedMetrics = expectedMetrics();
    assertThat(registry).has(METRICS_CONSUMED);
    Gauge metrics = registry.get(METRICS_CONSUMED).gauge();
    assertThat(metrics).hasValue(0);
    expectedMetrics.agentStarted(CONSUMER);
    assertThat(metrics).hasValue(rate);
    expectedMetrics.agentStarted(CONSUMER);
    assertThat(metrics).hasValue(rate * 2);
    expectedMetrics.agentStarted(CONSUMER);
    assertThat(metrics).hasValue(rate * 3);
  }

  ExpectedMetrics expectedMetrics() {
    return new ExpectedMetrics(params, registry, prefix, exposedMetrics);
  }
}
