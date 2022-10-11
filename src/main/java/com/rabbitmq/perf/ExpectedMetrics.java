// Copyright (c) 2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.perf.StartListener.Type;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.IntToDoubleFunction;

final class ExpectedMetrics {

  static final String METRICS_PUBLISHED = "expected_published";
  static final String METRICS_CONSUMED = "expected_consumed";

  private final AtomicInteger producers = new AtomicInteger(0);
  private final AtomicInteger consumers = new AtomicInteger(0);
  private final DoubleAccumulator expectedPublished, expectedConsumed;
  private final Map<String, DoubleAccumulator> exposed;
  private final IntToDoubleFunction consumingRateCalculation;
  // producer count to expected rate calculation
  private volatile IntToDoubleFunction publishingRateCalculation;

  ExpectedMetrics(MulticastParams params, MeterRegistry registry, String prefix,
      Map<String, Object> exposedMetrics) {
    exposedMetrics = exposedMetrics == null ? Collections.emptyMap() : exposedMetrics;
    if (exposedMetrics.containsKey(METRICS_PUBLISHED)) {
      Object expectedRateValue = exposedMetrics.get(METRICS_PUBLISHED);
      expectedPublished = registry.gauge(prefix + METRICS_PUBLISHED,
          createDoubleAccumulator(expectedRateValue));
    } else {
      if (params.getProducerRateLimit() >= 0 ||
          (params.getPublishingRates() != null && !params.getPublishingRates().isEmpty()) ||
          params.getPublishingInterval() != null) {
        // some rate instructions, so we create the expected metrics
        expectedPublished = registry.gauge(prefix + METRICS_PUBLISHED, new DoubleAccumulator(
            (previousValue, newValue) -> newValue, 0));
      } else {
        expectedPublished = new DoubleAccumulator((left, right) -> 0, 0);
      }
    }
    if (exposedMetrics.containsKey(METRICS_CONSUMED)) {
      Object expectedRateValue = exposedMetrics.get(METRICS_CONSUMED);
      expectedConsumed = registry.gauge(prefix + METRICS_CONSUMED,
          createDoubleAccumulator(expectedRateValue));
      consumingRateCalculation = c -> 0;
    } else {
      if (params.getConsumerRateLimit() > 0) {
        consumingRateCalculation = consumerCount -> consumerCount * params.getConsumerRateLimit();
        expectedConsumed = registry.gauge(prefix + METRICS_CONSUMED, new DoubleAccumulator(
            (previousValue, newValue) -> newValue, 0));
      } else {
        expectedConsumed = new DoubleAccumulator((left, right) -> 0, 0);
        consumingRateCalculation = c -> 0;
      }
    }

    exposedMetrics = new LinkedHashMap<>(exposedMetrics);
    exposedMetrics.remove(METRICS_PUBLISHED);
    exposedMetrics.remove(METRICS_CONSUMED);
    if (exposedMetrics.isEmpty()) {
      this.exposed = Collections.emptyMap();
    } else {
      this.exposed = new ConcurrentHashMap<>(exposedMetrics.size());
      for (Entry<String, Object> entry : exposedMetrics.entrySet()) {
        Object expectedValue = entry.getValue();
        // we keep a reference to them and update them
        // otherwise Prometheus shows a NaN value...
        DoubleAccumulator gauge = registry.gauge(prefix + entry.getKey(),
            createDoubleAccumulator(expectedValue));
        this.exposed.put(entry.getKey(), gauge);
      }
    }

  }

  private static DoubleAccumulator createDoubleAccumulator(Object expectedValue) {
    if (!(expectedValue instanceof Number)) {
      expectedValue = Double.valueOf(expectedValue.toString());
    }
    double expected = ((Number) expectedValue).doubleValue();
    return new DoubleAccumulator((left, right) -> expected, expected);
  }

  void agentStarted(Type type) {
    if (type == Type.PRODUCER) {
      producers.incrementAndGet();
      expectedPublished.accumulate(publishingRateCalculation.applyAsDouble(producers.get()));
    }
    if (type == Type.CONSUMER) {
      consumers.incrementAndGet();
      expectedConsumed.accumulate(consumingRateCalculation.applyAsDouble(consumers.get()));
    }
    this.exposed.values().forEach(g -> g.accumulate(0));
  }

  void register(ValueIndicator<Float> publishingRateIndicator, Duration publishingInterval) {
    if (publishingInterval == null) {
      publishingRateCalculation = producers -> producers * publishingRateIndicator.getValue();
      publishingRateIndicator.register(
          (oldValue, newValue) -> {
            expectedPublished.accumulate(
                publishingRateCalculation.applyAsDouble(producers.get()));
          });
    } else {
      publishingRateCalculation = producers -> producers / (double) publishingInterval.getSeconds();
    }
  }
}
