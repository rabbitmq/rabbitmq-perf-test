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
package com.rabbitmq.perf.metrics;

import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.MESSAGE_RATE_LABEL;
import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.formatRate;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

class CompactPrintStreamMetricsFormatter extends BaseMetricsFormatter implements MetricsFormatter {

  private static final String DATE_TIME_FORMAT = "yyyy/MM/dd HH:mm:ss";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);

  // e.g. 2025/04/08 16:54:19
  private static final String TIME_FORMAT = "%-" + DATE_TIME_FORMAT.length() + "s";
  // up to 999999 (6), space (1), msg/s (5), leading space to separate (1)
  private static final String RATE_FORMAT = "%13s";
  // 6 values of 3 digits, 5 separators, 1 space, 2 characters for unit, 1 space to separate
  private static final String LATENCY_FORMAT = "%27s";
  private static final int MAX_ALIGNED_LATENCY = 27 - 1; // no space to separate

  private final PrintStream out;
  private final TimeUnit latencyCollectionTimeUnit;

  CompactPrintStreamMetricsFormatter(
      PrintStream out,
      boolean publishedEnabled,
      boolean receivedEnabled,
      boolean returnedEnabled,
      boolean confirmedEnabled,
      TimeUnit latencyCollectionTimeUnit) {
    super(publishedEnabled, receivedEnabled, returnedEnabled, confirmedEnabled);
    if (latencyCollectionTimeUnit != MILLISECONDS && latencyCollectionTimeUnit != NANOSECONDS) {
      throw new IllegalArgumentException(
          "Latency collection unit must be ms or ns, not " + latencyCollectionTimeUnit);
    }
    this.latencyCollectionTimeUnit = latencyCollectionTimeUnit;
    this.out = out;
  }

  @Override
  public void header() {
    StringBuilder builder = new StringBuilder().append(format(TIME_FORMAT, "time"));

    if (this.publishedEnabled) {
      builder.append(format(RATE_FORMAT, "sent"));
    }
    if (this.returnedEnabled) {
      builder.append(format(RATE_FORMAT, "returned"));
    }

    if (this.publishedEnabled && this.confirmedEnabled) {
      builder.append(format(RATE_FORMAT, "confirmed"));
      builder.append(format(RATE_FORMAT, "nacked"));
    }

    if (this.receivedEnabled) {
      builder.append(format(RATE_FORMAT, "received"));
    }

    if (shouldDisplayConsumerLatency()) {
      builder.append(format(LATENCY_FORMAT, "consumer latency"));
    }

    if (shouldDisplayConfirmLatency()) {
      builder.append(format(LATENCY_FORMAT, "confirm latency"));
    }

    out.println(builder);
  }

  @Override
  public void report(
      Duration durationSinceStart,
      double publishedRate,
      double confirmedRate,
      double nackedRate,
      double returnedRate,
      double receivedRate,
      long[] confirmedLatencyStats,
      long[] consumerLatencyStats) {
    StringBuilder builder =
        new StringBuilder().append(DATE_TIME_FORMATTER.format(LocalDateTime.now()));
    if (this.publishedEnabled) {
      builder.append(format(RATE_FORMAT, formatRate(publishedRate) + " " + MESSAGE_RATE_LABEL));
    }

    if (this.returnedEnabled) {
      builder.append(format(RATE_FORMAT, formatRate(returnedRate) + " " + MESSAGE_RATE_LABEL));
    }

    if (this.publishedEnabled && this.confirmedEnabled) {
      builder.append(format(RATE_FORMAT, formatRate(confirmedRate) + " " + MESSAGE_RATE_LABEL));
      builder.append(format(RATE_FORMAT, formatRate(nackedRate) + " " + MESSAGE_RATE_LABEL));
    }

    if (this.receivedEnabled) {
      builder.append(format(RATE_FORMAT, formatRate(receivedRate) + " " + MESSAGE_RATE_LABEL));
    }

    boolean latencyOverflow = false;
    if (shouldDisplayConsumerLatency()) {
      String latency =
          MetricsFormatterUtils.formatLatency(consumerLatencyStats, this.latencyCollectionTimeUnit);
      if (latency.length() > MAX_ALIGNED_LATENCY) {
        builder.append(" ").append(latency);
        latencyOverflow = true;
      } else {
        builder.append(format(LATENCY_FORMAT, latency));
      }
    }

    if (shouldDisplayConfirmLatency()) {
      String latency =
          MetricsFormatterUtils.formatLatency(
              confirmedLatencyStats, this.latencyCollectionTimeUnit);
      if (latency.length() > MAX_ALIGNED_LATENCY || latencyOverflow) {
        builder.append(" ").append(latency);
      } else {
        builder.append(format(LATENCY_FORMAT, latency));
      }
    }
    out.println(builder);
  }

  @Override
  public void summary(
      Duration elapsed,
      double ratePublished,
      double rateReceived,
      long[] consumedLatencyTotal,
      long[] confirmedLatencyTotal) {
    this.out.print(
        summary(
            elapsed,
            ratePublished,
            rateReceived,
            consumedLatencyTotal,
            confirmedLatencyTotal,
            null,
            this.latencyCollectionTimeUnit));
  }
}
