// Copyright (c) 2023 Broadcom. All Rights Reserved.
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

import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StatsTest {

  private static final Duration INTERVAL = Duration.ofMillis(10);
  private final AtomicInteger reportCount = new AtomicInteger();

  private static void startTask(Runnable task) {
    new Thread(task).start();
  }

  @BeforeEach
  void init() {
    reportCount.set(0);
  }

  @Test
  void gaugesAreResetIfThereIsNoActivity() throws Exception {
    MeterRegistry registry = new SimpleMeterRegistry();
    Stats stats = new SimpleStats(INTERVAL, true, registry, "");
    Gauge published = registry.get("published").gauge();
    Gauge consumed = registry.get("consumed").gauge();
    assertThat(published.value()).isZero();
    AtomicBoolean keepSending = new AtomicBoolean(true);
    AtomicBoolean keepConsuming = new AtomicBoolean(true);
    try {
      startTask(
          () -> {
            while (keepSending.get()) {
              stats.handleSend();
            }
          });
      startTask(
          () -> {
            while (keepConsuming.get()) {
              stats.handleRecv(1);
            }
          });
      waitAtMost(10, () -> reportCount.get() > 1);
      waitAtMost(10, () -> published.value() > 0);
      waitAtMost(10, () -> consumed.value() > 0);
      keepSending.set(false);
      keepConsuming.set(false);

      Thread.sleep(4 * INTERVAL.toMillis());
      // we simulate a service polling 2 times
      stats.maybeResetGauges();
      Thread.sleep(INTERVAL.toMillis());
      stats.maybeResetGauges();
      assertThat(published.value()).isZero();
      assertThat(consumed.value()).isZero();
    } finally {
      keepSending.set(false);
      keepConsuming.set(false);
    }
  }

  @Test
  void gaugesAreResetAutomaticallyIfActivityOnOneOfThem() throws Exception {
    MeterRegistry registry = new SimpleMeterRegistry();
    Stats stats = new SimpleStats(INTERVAL, true, registry, "");
    Gauge published = registry.get("published").gauge();
    Gauge consumed = registry.get("consumed").gauge();
    assertThat(published.value()).isZero();
    AtomicBoolean keepSending = new AtomicBoolean(true);
    AtomicBoolean keepConsuming = new AtomicBoolean(true);
    try {
      startTask(
          () -> {
            while (keepSending.get()) {
              stats.handleSend();
            }
          });
      startTask(
          () -> {
            while (keepConsuming.get()) {
              stats.handleRecv(1);
            }
          });
      waitAtMost(10, () -> reportCount.get() > 1);
      waitAtMost(10, () -> published.value() > 0);
      waitAtMost(10, () -> consumed.value() > 0);
      keepSending.set(false);

      Thread.sleep(4 * INTERVAL.toMillis());
      // we simulate a service polling 2 times
      stats.maybeResetGauges();
      // give some time to handle consuming (all stats methods are synchronized)
      Thread.sleep(INTERVAL.toMillis());
      stats.maybeResetGauges();
      // the gauge is actually reset in the report calculation
      assertThat(published.value()).isZero();
      // this one is active, so it still reports something
      assertThat(consumed.value()).isPositive();
    } finally {
      keepSending.set(false);
      keepConsuming.set(false);
    }
  }

  private class SimpleStats extends Stats {

    public SimpleStats(
        Duration interval, boolean useMs, MeterRegistry registry, String metricsPrefix) {
      super(interval, useMs, registry, metricsPrefix);
    }

    @Override
    protected void report(long now) {
      long elapsedTime = Duration.ofNanos(elapsedInterval.get()).toMillis();
      double rate = rate(sendCountInterval.get(), elapsedTime);
      this.published(rate);
      rate = rate(recvCountInterval.get(), elapsedTime);
      this.received(rate);
      reportCount.incrementAndGet();
    }

    private double rate(long count, long elapsed) {
      return NANO_TO_SECOND * count / elapsed;
    }
  }
}
