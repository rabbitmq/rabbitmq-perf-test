// Copyright (c) 2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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
package com.rabbitmq.perf.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Histogram;
import com.rabbitmq.perf.NamedThreadFactory;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.DoubleBinaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation that collects metrics with concurrent utilities.
 *
 * @since 2.19.0
 */
public final class DefaultPerformanceMetrics implements PerformanceMetrics, AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPerformanceMetrics.class);
  private static final float MS_TO_SECOND = 1_000;
  private final ScheduledExecutorService scheduledExecutorService;
  private final AtomicLong lastTick = new AtomicLong(-1);
  private final AtomicLong startTime = new AtomicLong(-1);
  // to calculate rates: published, confirmed, nacked, returned, received
  private final AtomicLong published = new AtomicLong(0);
  private final AtomicLong confirmed = new AtomicLong(0);
  private final AtomicLong nacked = new AtomicLong(0);
  private final AtomicLong returned = new AtomicLong(0);
  private final AtomicLong received = new AtomicLong(0);
  // to calculate rates
  private final AtomicLong lastPublished = new AtomicLong(0);
  private final AtomicLong lastConfirmed = new AtomicLong(0);
  private final AtomicLong lastNacked = new AtomicLong(0);
  private final AtomicLong lastReturned = new AtomicLong(0);
  private final AtomicLong lastReceived = new AtomicLong(0);
  // for the summary
  private final AtomicLong startTimeForTotal = new AtomicLong(-1);
  private final AtomicLong publishedTotal = new AtomicLong(0);
  private final AtomicLong receivedTotal = new AtomicLong(0);
  private final AtomicReference<Histogram> consumedLatencyTotal, confirmedLatencyTotal;
  // Micrometer's metrics
  // instant rates
  private final DoubleAccumulator publishedRate,
      confirmedRate,
      nackedRate,
      returnedRate,
      receivedRate;
  // latencies: confirmed, consumed
  private final Timer consumedLatencyTimer, confirmedLatencyTimer;
  private final DistributionSummary confirmedSize;
  // end of Micrometer's metrics
  private final Duration interval;
  private final TimeUnit latencyCollectionTimeUnit;
  private final AtomicBoolean firstReport = new AtomicBoolean(false);
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicReference<Histogram> consumedLatency, confirmedLatency;
  private final MetricsFormatter formatter;

  public DefaultPerformanceMetrics(
      Duration interval,
      TimeUnit latencyCollectionTimeUnit,
      MeterRegistry registry,
      String metricsPrefix,
      MetricsFormatter formatter) {
    this.interval = interval;
    if (latencyCollectionTimeUnit != MILLISECONDS && latencyCollectionTimeUnit != NANOSECONDS) {
      throw new IllegalArgumentException(
          "Latency collection unit must be ms or ns, not " + latencyCollectionTimeUnit);
    }
    this.latencyCollectionTimeUnit = latencyCollectionTimeUnit;
    this.scheduledExecutorService =
        Executors.newScheduledThreadPool(
            1, new NamedThreadFactory("perf-test-metrics-scheduling-"));
    this.formatter = formatter;

    metricsPrefix = metricsPrefix == null ? "" : metricsPrefix;

    DoubleBinaryOperator accumulatorFunction = (x, y) -> y;
    publishedRate =
        registry.gauge(
            metricsPrefix + "published", new DoubleAccumulator(accumulatorFunction, 0.0));
    confirmedRate =
        registry.gauge(
            metricsPrefix + "confirmed", new DoubleAccumulator(accumulatorFunction, 0.0));
    nackedRate =
        registry.gauge(metricsPrefix + "nacked", new DoubleAccumulator(accumulatorFunction, 0.0));
    returnedRate =
        registry.gauge(metricsPrefix + "returned", new DoubleAccumulator(accumulatorFunction, 0.0));
    receivedRate =
        registry.gauge(metricsPrefix + "consumed", new DoubleAccumulator(accumulatorFunction, 0.0));

    consumedLatencyTimer =
        timer(metricsPrefix + "latency", "message latency", this.interval, registry);
    confirmedLatencyTimer =
        timer(metricsPrefix + "confirm.latency", "confirm latency", this.interval, registry);

    confirmedSize =
        DistributionSummary.builder(metricsPrefix + "confirm.size")
            .description("number of confirms received per confirm frame")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .distributionStatisticExpiry(this.interval)
            .serviceLevelObjectives()
            .register(registry);

    this.consumedLatency = new AtomicReference<>(histogram());
    this.confirmedLatency = new AtomicReference<>(histogram());

    this.consumedLatencyTotal = new AtomicReference<>(histogram());
    this.confirmedLatencyTotal = new AtomicReference<>(histogram());

    this.startTime.set(System.nanoTime());
    this.lastTick.set(startTime.get());
    this.startTimeForTotal.set(startTime.get());
  }

  private static Histogram histogram() {
    return new Histogram(new ExponentiallyDecayingReservoir());
  }

  private static Timer timer(
      String name, String description, Duration expiry, MeterRegistry registry) {
    return Timer.builder(name)
        .description(description)
        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
        .distributionStatisticExpiry(expiry)
        .serviceLevelObjectives()
        .register(registry);
  }

  private static double rate(long count, long elapsedInMs) {
    return MS_TO_SECOND * count / elapsedInMs;
  }

  private static double swapAndCalculateRate(
      AtomicLong current, AtomicLong last, long elapsedTimeInMs) {
    long currentValue = current.get();
    long count = currentValue - last.get();
    last.set(currentValue);
    return rate(count, elapsedTimeInMs);
  }

  private static Runnable wrapInCatch(Runnable runnable) {
    return () -> {
      try {
        runnable.run();
      } catch (Exception e) {
        LOGGER.warn("Error while processing metrics", e);
      }
    };
  }

  @Override
  public void start() {
    if (this.started.compareAndSet(false, true)) {
      startTime.set(System.nanoTime());
      lastTick.set(startTime.get());
      startTimeForTotal.set(startTime.get());

      scheduledExecutorService.scheduleAtFixedRate(
          wrapInCatch(
              () -> {
                if (!this.started.get()) {
                  return;
                }
                if (noActivity()) {
                  this.publishedRate.accumulate(0);
                  this.confirmedRate.accumulate(0);
                  this.nackedRate.accumulate(0);
                  this.returnedRate.accumulate(0);
                  this.receivedRate.accumulate(0);
                  this.confirmedLatency.set(histogram());
                  this.consumedLatency.set(histogram());
                } else {
                  metrics(System.nanoTime());
                }
              }),
          interval.getSeconds(),
          interval.getSeconds(),
          TimeUnit.SECONDS);
    }
  }

  void metrics(long currentTime) {
    Duration duration = Duration.ofNanos(currentTime - lastTick.get());
    lastTick.set(currentTime);

    Duration durationSinceStart = Duration.ofNanos(currentTime - startTime.get());

    long elapsedTimeInMs = duration.toMillis();

    double ratePublished = swapAndCalculateRate(published, lastPublished, elapsedTimeInMs);
    double rateConfirmed = swapAndCalculateRate(confirmed, lastConfirmed, elapsedTimeInMs);
    double rateNacked = swapAndCalculateRate(nacked, lastNacked, elapsedTimeInMs);
    double rateReturned = swapAndCalculateRate(returned, lastReturned, elapsedTimeInMs);
    double rateReceived = swapAndCalculateRate(received, lastReceived, elapsedTimeInMs);

    this.publishedRate.accumulate(ratePublished);
    this.confirmedRate.accumulate(rateConfirmed);
    this.nackedRate.accumulate(rateNacked);
    this.returnedRate.accumulate(rateReturned);
    this.receivedRate.accumulate(rateReceived);

    long[] confirmedLatencyStats = getStats(this.confirmedLatency.get());
    long[] consumedLatencyStats = getStats(this.consumedLatency.get());

    this.confirmedLatency.set(histogram());
    this.consumedLatency.set(histogram());

    if (this.started.get()) {
      if (this.firstReport.compareAndSet(false, true)) {
        this.formatter.header();
      }
      this.formatter.report(
          durationSinceStart,
          ratePublished,
          rateConfirmed,
          rateNacked,
          rateReturned,
          rateReceived,
          confirmedLatencyStats,
          consumedLatencyStats);
    }
  }

  private long[] getStats(Histogram histogram) {
    return new long[] {
      div(histogram.getSnapshot().getMin()),
      div(histogram.getSnapshot().getMedian()),
      div(histogram.getSnapshot().get75thPercentile()),
      div(histogram.getSnapshot().get95thPercentile()),
      div(histogram.getSnapshot().get99thPercentile())
    };
  }

  private long div(double p) {
    if (this.latencyCollectionTimeUnit == MILLISECONDS) {
      return (long) p;
    } else {
      // we get ns, so we divide to get microseconds, easier to read
      return (long) (p / 1000L);
    }
  }

  @Override
  public void published() {
    this.published.incrementAndGet();
    this.publishedTotal.incrementAndGet();
  }

  @Override
  public void confirmed(int count, long[] latencies) {
    this.confirmed.addAndGet(count);
    this.confirmedSize.record(count);
    for (long latency : latencies) {
      this.confirmedLatencyTimer.record(latency, this.latencyCollectionTimeUnit);
      this.confirmedLatency.get().update(latency);
      this.confirmedLatencyTotal.get().update(latency);
    }
  }

  @Override
  public void nacked(int count) {
    this.nacked.addAndGet(count);
  }

  @Override
  public void returned() {
    this.returned.incrementAndGet();
  }

  @Override
  public void received(long latency) {
    this.received.incrementAndGet();
    this.receivedTotal.incrementAndGet();
    if (latency > 0) {
      this.consumedLatencyTimer.record(latency, this.latencyCollectionTimeUnit);
      this.consumedLatency.get().update(latency);
      this.consumedLatencyTotal.get().update(latency);
    }
  }

  @Override
  public Duration interval() {
    return interval;
  }

  private boolean noActivity() {
    return this.published.get() == this.lastPublished.get()
        && this.confirmed.get() == this.lastConfirmed.get()
        && this.nacked.get() == this.lastNacked.get()
        && this.returned.get() == this.lastReturned.get()
        && this.received.get() == this.lastReceived.get();
  }

  private void printFinal() {
    long now = System.nanoTime();
    long st = this.startTimeForTotal.get();
    Duration elapsedDuration = Duration.ofNanos(now - st);
    long elapsed = elapsedDuration.toMillis();

    double ratePublished = this.publishedTotal.get() * MS_TO_SECOND / elapsed;
    double rateReceived = this.receivedTotal.get() * MS_TO_SECOND / elapsed;
    long[] consumeLatencyTotal = getStats(this.consumedLatencyTotal.get());
    long[] confirmedLatencyTotal = getStats(this.confirmedLatencyTotal.get());
    this.formatter.summary(
        elapsedDuration, ratePublished, rateReceived, consumeLatencyTotal, confirmedLatencyTotal);
  }

  @Override
  public void resetGlobals() {
    // reset published and received totals
    // reset the time used to calculate the final report rates
    // reset global confirmed and received latencies as well
    this.publishedTotal.set(0);
    this.receivedTotal.set(0);
    this.consumedLatencyTotal.set(histogram());
    this.confirmedLatencyTotal.set(histogram());
    this.startTimeForTotal.set(System.nanoTime());
  }

  @Override
  public void close() {
    if (this.started.compareAndSet(true, false)) {
      this.scheduledExecutorService.shutdownNow();
      printFinal();
    }
  }

  // for testing
  void started(boolean value) {
    this.started.set(value);
  }
}
