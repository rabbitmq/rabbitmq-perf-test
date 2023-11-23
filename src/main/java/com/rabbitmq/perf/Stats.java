// Copyright (c) 2007-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;

public abstract class Stats {

  protected static final float NANO_TO_SECOND = 1_000_000_000;

  private final AtomicBoolean ongoingReport = new AtomicBoolean(false);

  private final long intervalInNanoSeconds;

  protected final long startTime;
  protected final AtomicLong startTimeForGlobals = new AtomicLong(0);

  // Micrometer's metrics
  // instant rates
  private final DoubleAccumulator published, returned, confirmed, nacked, consumed;
  // latency update functions
  private final Consumer<Long> updateLatency;
  private final Consumer<Long> updateConfirmLatency;
  // end of Micrometer's metrics
  private AtomicLong lastPublishedCount = new AtomicLong(0);
  private AtomicLong lastReturnedCount = new AtomicLong(0);
  private AtomicLong lastConfirmedCount = new AtomicLong(0);
  private AtomicLong lastNackedCount = new AtomicLong(0);
  private AtomicLong lastConsumedCount = new AtomicLong(0);

  protected AtomicLong lastStatsTime = new AtomicLong(0);
  protected AtomicLong sendCountInterval = new AtomicLong(0);
  protected AtomicLong returnCountInterval = new AtomicLong(0);
  protected AtomicLong confirmCountInterval = new AtomicLong(0);
  protected AtomicLong nackCountInterval = new AtomicLong(0);
  protected AtomicLong recvCountInterval = new AtomicLong(0);
  protected AtomicLong sendCountTotal = new AtomicLong(0);
  protected AtomicLong recvCountTotal = new AtomicLong(0);
  protected AtomicLong latencyCountInterval = new AtomicLong(0);
  protected AtomicLong latencyCountTotal = new AtomicLong(0);
  protected AtomicLong minLatency = new AtomicLong(0);
  protected AtomicLong maxLatency = new AtomicLong(0);
  protected AtomicLong cumulativeLatencyInterval = new AtomicLong(0);
  protected AtomicLong cumulativeLatencyTotal = new AtomicLong(0);
  protected AtomicLong elapsedInterval = new AtomicLong(0);
  protected AtomicLong elapsedTotal = new AtomicLong(0);
  protected Histogram latency = new MetricRegistry().histogram("latency");
  protected Histogram confirmLatency = new MetricRegistry().histogram("confirm-latency");
  protected final AtomicReference<Histogram> globalLatency =
      new AtomicReference<>(new MetricRegistry().histogram("latency"));
  protected final AtomicReference<Histogram> globalConfirmLatency =
      new AtomicReference<>(new MetricRegistry().histogram("confirm-latency"));
  private final PerformanceMetrics performanceMetrics;

  public Stats(Duration interval) {
    this(interval, false, new SimpleMeterRegistry(), null, PerformanceMetrics.NO_OP);
  }

  public Stats(Duration interval, boolean useMs, MeterRegistry registry, String metricsPrefix) {
    this(interval, useMs, registry, metricsPrefix, PerformanceMetrics.NO_OP);
  }

  public Stats(
      Duration interval,
      boolean useMs,
      MeterRegistry registry,
      String metricsPrefix,
      PerformanceMetrics performanceMetrics) {
    this.intervalInNanoSeconds = interval.toNanos();
    startTime = System.nanoTime();
    this.startTimeForGlobals.set(startTime);
    this.performanceMetrics = performanceMetrics;
    this.performanceMetrics.start();

    metricsPrefix = metricsPrefix == null ? "" : metricsPrefix;

    Timer latencyTimer =
        Timer.builder(metricsPrefix + "latency")
            .description("message latency")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .distributionStatisticExpiry(Duration.ofNanos(this.intervalInNanoSeconds))
            .serviceLevelObjectives()
            .register(registry);

    Timer confirmLatencyTimer =
        Timer.builder(metricsPrefix + "confirm.latency")
            .description("confirm latency")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .distributionStatisticExpiry(Duration.ofNanos(this.intervalInNanoSeconds))
            .serviceLevelObjectives()
            .register(registry);

    DoubleBinaryOperator accumulatorFunction = (x, y) -> y;
    published =
        registry.gauge(
            metricsPrefix + "published", new DoubleAccumulator(accumulatorFunction, 0.0));
    returned =
        registry.gauge(metricsPrefix + "returned", new DoubleAccumulator(accumulatorFunction, 0.0));
    confirmed =
        registry.gauge(
            metricsPrefix + "confirmed", new DoubleAccumulator(accumulatorFunction, 0.0));
    nacked =
        registry.gauge(metricsPrefix + "nacked", new DoubleAccumulator(accumulatorFunction, 0.0));
    consumed =
        registry.gauge(metricsPrefix + "consumed", new DoubleAccumulator(accumulatorFunction, 0.0));

    updateLatency =
        useMs
            ? latency -> latencyTimer.record(latency, TimeUnit.MILLISECONDS)
            : latency -> latencyTimer.record(latency, TimeUnit.NANOSECONDS);

    updateConfirmLatency =
        useMs
            ? latency -> confirmLatencyTimer.record(latency, TimeUnit.MILLISECONDS)
            : latency -> confirmLatencyTimer.record(latency, TimeUnit.NANOSECONDS);

    reset(startTime);
  }

  private void reset(long t) {
    lastStatsTime.set(t);

    resetLastCounts();

    sendCountInterval.set(0);
    returnCountInterval.set(0);
    confirmCountInterval.set(0);
    nackCountInterval.set(0);
    recvCountInterval.set(0);

    minLatency.set(Long.MAX_VALUE);
    maxLatency.set(Long.MIN_VALUE);
    latencyCountInterval.set(0);
    cumulativeLatencyInterval.set(0L);
    latency = new MetricRegistry().histogram("latency");
    confirmLatency = new MetricRegistry().histogram("confirm-latency");
  }

  private void report() {
    long now = System.nanoTime();
    elapsedInterval.set(now - lastStatsTime.get());
    if (elapsedInterval.get() >= intervalInNanoSeconds) {
      elapsedTotal.addAndGet(elapsedInterval.get());
      if (ongoingReport.compareAndSet(false, true)) {
        synchronized (this) {
          report(now);
          reset(now);
        }
        ongoingReport.set(false);
      }
    }
  }

  protected abstract void report(long now);

  public void handleSend() {
    this.performanceMetrics.published();
    sendCountInterval.incrementAndGet();
    sendCountTotal.incrementAndGet();
    report();
  }

  public void handleReturn() {
    returnCountInterval.incrementAndGet();
    report();
  }

  public void handleConfirm(int numConfirms, long[] latencies) {
    confirmCountInterval.addAndGet(numConfirms);
    for (long latency : latencies) {
      this.confirmLatency.update(latency);
      this.globalConfirmLatency.get().update(latency);
      this.updateConfirmLatency.accept(latency);
    }
    report();
  }

  public void handleNack(int numAcks) {
    nackCountInterval.addAndGet(numAcks);
    report();
  }

  public void handleRecv(long latency) {
    recvCountInterval.incrementAndGet();
    recvCountTotal.incrementAndGet();
    if (latency > 0) {
      this.latency.update(latency);
      this.globalLatency.get().update(latency);
      this.updateLatency.accept(latency);
      minLatency.set(Math.min(minLatency.get(), latency));
      maxLatency.set(Math.max(maxLatency.get(), latency));
      cumulativeLatencyInterval.addAndGet(latency);
      cumulativeLatencyTotal.addAndGet(latency);
      latencyCountInterval.incrementAndGet();
      latencyCountTotal.incrementAndGet();
    }
    report();
  }

  protected void published(double rate) {
    this.published.accumulate(rate);
  }

  protected void returned(double rate) {
    this.returned.accumulate(rate);
  }

  protected void confirmed(double rate) {
    this.confirmed.accumulate(rate);
  }

  protected void nacked(double rate) {
    this.nacked.accumulate(rate);
  }

  protected void received(double rate) {
    this.consumed.accumulate(rate);
  }

  synchronized void maybeResetGauges() {
    if (noActivity()) {
      long now = System.nanoTime();
      elapsedInterval.set(now - lastStatsTime.get());

      if (elapsedInterval.get() >= 2 * intervalInNanoSeconds) {
        published.accumulate(0);
        returned.accumulate(0);
        confirmed.accumulate(0);
        nacked.accumulate(0);
        consumed.accumulate(0);
      }
    } else {
      resetLastCounts();
    }
  }

  private boolean noActivity() {
    return lastPublishedCount.get() == sendCountInterval.get()
        && lastReturnedCount.get() == returnCountInterval.get()
        && lastConfirmedCount.get() == confirmCountInterval.get()
        && lastNackedCount.get() == nackCountInterval.get()
        && lastConsumedCount.get() == recvCountInterval.get();
  }

  private void resetLastCounts() {
    lastPublishedCount.set(sendCountInterval.get());
    lastReturnedCount.set(returnCountInterval.get());
    lastConfirmedCount.set(confirmCountInterval.get());
    lastNackedCount.set(nackCountInterval.get());
    lastConsumedCount.set(recvCountInterval.get());
  }

  void resetGlobals() {
    this.sendCountTotal.set(0);
    this.recvCountTotal.set(0);
    this.startTimeForGlobals.set(System.nanoTime());
  }

  Duration interval() {
    return Duration.ofNanos(this.intervalInNanoSeconds);
  }
}
