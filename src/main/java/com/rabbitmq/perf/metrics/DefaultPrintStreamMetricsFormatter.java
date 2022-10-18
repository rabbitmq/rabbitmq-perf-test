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

package com.rabbitmq.perf.metrics;

import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.LATENCY_HEADER;
import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.MESSAGE_RATE_LABEL;
import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.formatTime;
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
class DefaultPrintStreamMetricsFormatter extends BaseMetricsFormatter implements
    MetricsFormatter {

  private final PrintStream out;
  private final String testId;

  private final TimeUnit latencyCollectionTimeUnit;

  DefaultPrintStreamMetricsFormatter(PrintStream out, String testId,
      boolean publishedEnabled,
      boolean receivedEnabled,
      boolean returnedEnabled, boolean confirmedEnabled,
      TimeUnit latencyCollectionTimeUnit) {
    super(publishedEnabled, receivedEnabled, returnedEnabled, confirmedEnabled);
    if (latencyCollectionTimeUnit != MILLISECONDS && latencyCollectionTimeUnit != NANOSECONDS) {
      throw new IllegalArgumentException(
          "Latency collection unit must be ms or ns, not " + latencyCollectionTimeUnit);
    }
    this.latencyCollectionTimeUnit = latencyCollectionTimeUnit;
    this.out = out;
    this.testId = testId;
  }

  private static String formatRate(String label, double rate) {
    return ", " + label + ": " + MetricsFormatterUtils.formatRate(rate) + " " + MESSAGE_RATE_LABEL;
  }

  @Override
  public void header() {

  }

  @Override
  public void report(Duration durationSinceStart, double publishedRate, double confirmedRate,
      double nackedRate, double returnedRate, double receivedRate, long[] confirmedLatencyStats,
      long[] consumerLatencyStats) {
    StringBuilder builder = new StringBuilder()
        .append(format("id: %s, ", testId))
        .append(format("time %s s", formatTime(durationSinceStart)));
    if (this.publishedEnabled) {
      builder.append(formatRate("sent", publishedRate));
    }

    if (this.returnedEnabled) {
      builder.append(formatRate("returned", returnedRate));
    }

    if (this.publishedEnabled && this.confirmedEnabled) {
      builder.append(formatRate("confirmed", confirmedRate));
      builder.append(formatRate("nacked", nackedRate));
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
    this.out.print(
        summary(elapsed, ratePublished, rateReceived, consumedLatencyTotal, confirmedLatencyTotal,
            this.testId, this.latencyCollectionTimeUnit));
  }

  private String formatLatency(long[] stats) {
    return MetricsFormatterUtils.formatLatency(stats, this.latencyCollectionTimeUnit);
  }


}
