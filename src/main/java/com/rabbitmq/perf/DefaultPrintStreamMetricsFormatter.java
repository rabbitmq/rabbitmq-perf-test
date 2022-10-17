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

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Formatter that uses the "historical" PerfTest format to output metrics. Explicit, as it outputs
 * the metrics name before each value on each line.
 *
 * @since 2.19.0
 */
class DefaultPrintStreamMetricsFormatter implements MetricsFormatter {

  public static final String MESSAGE_RATE_LABEL = "msg/s";
  private static final float NANO_TO_SECOND = 1_000_000_000;
  private static final String LATENCY_HEADER = "min/median/75th/95th/99th";
  private final PrintStream out;
  private final String testId;
  private final boolean publishedEnabled, receivedEnabled,
      returnedEnabled, confirmedEnabled;
  private final TimeUnit latencyCollectionTimeUnit;

  DefaultPrintStreamMetricsFormatter(PrintStream out, String testId, boolean publishedEnabled,
      boolean receivedEnabled,
      boolean returnedEnabled, boolean confirmedEnabled,
      TimeUnit latencyCollectionTimeUnit) {
    if (latencyCollectionTimeUnit != MILLISECONDS && latencyCollectionTimeUnit != NANOSECONDS) {
      throw new IllegalArgumentException(
          "Latency collection unit must be ms or ns, not " + latencyCollectionTimeUnit);
    }
    this.latencyCollectionTimeUnit = latencyCollectionTimeUnit;
    this.out = out;
    this.testId = testId;
    this.publishedEnabled = publishedEnabled;
    this.receivedEnabled = receivedEnabled;
    this.returnedEnabled = returnedEnabled;
    this.confirmedEnabled = confirmedEnabled;
  }

  private static String unit(TimeUnit latencyCollectionTimeUnit) {
    if (latencyCollectionTimeUnit == MILLISECONDS) {
      return "ms";
    } else {
      return "µs";
    }
  }

  private static String formatRate(double rate) {
    if (rate == 0.0) {
      return format("%d", (long) rate);
    } else if (rate < 1) {
      return format("%1.2f", rate);
    } else if (rate < 10) {
      return format("%1.1f", rate);
    } else {
      return format("%d", (long) rate);
    }
  }

  private static String formatRate(String label, double rate) {
    return ", " + label + ": " + formatRate(rate) + " " + MESSAGE_RATE_LABEL;
  }

  @Override
  public void header() {

  }

  @Override
  public void report(Duration durationSinceStart, double publishedRate, double confirmedRate,
      double nackedRate, double returnedRate, double receivedRate, long[] confirmedLatencyStats,
      long[] consumerLatencyStats) {
    StringBuilder builder = new StringBuilder()
        .append(
            format("id: %s, time %.3f s", testId, durationSinceStart.toNanos() / NANO_TO_SECOND));
    if (this.publishedEnabled) {
      builder.append(formatRate("sent", publishedRate));
    }

    if (this.returnedEnabled) {
      builder.append(formatRate("returned", returnedRate));
    }

    if (this.publishedEnabled && this.confirmedEnabled) {
      builder.append(
          formatRate("confirmed", confirmedRate));
      builder.append(
          formatRate("nacked", nackedRate));
    }

    if (this.receivedEnabled) {
      builder.append(formatRate("received", receivedRate));
    }

    if (shouldDisplayConsumerLatency() || shouldDisplayConfirmLatency()) {
      builder.append(format(", %s ", LATENCY_HEADER));
    }
    if (shouldDisplayConsumerLatency()) {
      builder.append("consumer latency: " + formatLatency(consumerLatencyStats));
    }
    if (shouldDisplayConsumerLatency() && shouldDisplayConfirmLatency()) {
      builder.append(", ");
    }

    if (shouldDisplayConfirmLatency()) {
      builder.append("confirm latency: " + formatLatency(confirmedLatencyStats));
    }

    this.out.println(builder);
  }

  @Override
  public void summary(Duration elapsed, double ratePublished, double rateReceived,
      long[] consumedLatencyTotal,
      long[] confirmedLatencyTotal) {
    String lineSeparator = System.getProperty("line.separator");
    StringBuilder summary = new StringBuilder("id: " + this.testId + ", sending rate avg: " +
        formatRate(ratePublished) +
        " " + MESSAGE_RATE_LABEL);
    summary.append(lineSeparator);

    if (elapsed.toMillis() > 0) {
      summary.append("id: " + this.testId + ", receiving rate avg: " +
          formatRate(rateReceived) +
          " " + MESSAGE_RATE_LABEL).append(lineSeparator);
      if (shouldDisplayConsumerLatency()) {
        summary.append(format("id: %s, consumer latency %s %s",
            this.testId, LATENCY_HEADER, formatLatency(consumedLatencyTotal))
        ).append(lineSeparator);
      }
      if (shouldDisplayConfirmLatency()) {
        summary.append(format("id: %s, confirm latency %s %s",
            this.testId, LATENCY_HEADER, formatLatency(confirmedLatencyTotal)
        )).append(lineSeparator);
      }
    }
    this.out.print(summary);
  }

  private String formatLatency(long[] stats) {
    return format("%d/%d/%d/%d/%d %s", stats[0], stats[1], stats[2], stats[3], stats[4],
        unit(this.latencyCollectionTimeUnit));
  }

  private boolean shouldDisplayConsumerLatency() {
    return receivedEnabled;
  }

  boolean shouldDisplayConfirmLatency() {
    return publishedEnabled && confirmedEnabled;
  }

}
