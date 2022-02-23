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

import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StatsTest {

  private static final long INTERVAL = 10;
  private final AtomicInteger reportCount = new AtomicInteger();

  @BeforeEach
  void init() {
    reportCount.set(0);
  }

  @Test
  void maybeResetGauges() throws Exception {
    MeterRegistry registry = new SimpleMeterRegistry();
    Stats stats = new SimpleStats(INTERVAL, true, registry, "");
    Gauge published = registry.get("published").gauge();
    assertThat(published.value()).isZero();
    AtomicBoolean keepSending = new AtomicBoolean(true);
    new Thread(
            () -> {
              while (keepSending.get()) {
                stats.handleSend();
              }
            })
        .start();
    waitAtMost(10, () -> reportCount.get() > 1);
    assertThat(published.value()).isPositive();
    keepSending.set(false);

    Thread.sleep(4 * INTERVAL);
    // we simulate a service polling 2 times
    stats.maybeResetGauges();
    stats.maybeResetGauges();
    assertThat(published.value()).isZero();
  }

  private class SimpleStats extends Stats {

    public SimpleStats(long interval, boolean useMs, MeterRegistry registry, String metricsPrefix) {
      super(interval, useMs, registry, metricsPrefix);
    }

    @Override
    protected void report(long now) {
      double ratePublished = rate(sendCountInterval, elapsedInterval);
      this.published(ratePublished);
      reportCount.incrementAndGet();
    }

    private double rate(long count, long elapsed) {
      return 1000.0 * count / elapsed;
    }
  }
}
