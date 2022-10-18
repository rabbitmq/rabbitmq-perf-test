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

import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.formatTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Exports metrics to a CSV file.
 *
 * @since 2.19.0
 */
public class CsvMetricsFormatter extends BaseMetricsFormatter implements MetricsFormatter {

  private final PrintWriter out;
  private final String testId;
  private final String unit;

  public CsvMetricsFormatter(PrintWriter out, String testId, boolean publishedEnabled,
      boolean receivedEnabled,
      boolean returnedEnabled, boolean confirmedEnabled,
      TimeUnit latencyCollectionTimeUnit) {
    super(publishedEnabled, receivedEnabled, returnedEnabled, confirmedEnabled);
    if (latencyCollectionTimeUnit != MILLISECONDS && latencyCollectionTimeUnit != NANOSECONDS) {
      throw new IllegalArgumentException(
          "Latency collection unit must be ms or ns, not " + latencyCollectionTimeUnit);
    }
    this.unit = latencyCollectionTimeUnit == MILLISECONDS ? "ms" : "µs";
    this.out = out;
    this.testId = testId;
  }

  private static String rate(double rate, boolean display) {
    if (display) {
      return MetricsFormatterUtils.formatRate(rate);
    } else {
      return "";
    }
  }

  @Override
  public void header() {
    this.out.printf("id,time (s),published (msg/s),returned (msg/s)," +
            "confirmed (msg/s),nacked (msg/s)," +
            "received (msg/s),min consumer latency (%s),median consumer latency (%s)," +
            "75th p. consumer latency (%s),95th p. consumer latency (%s),99th p. consumer latency (%s),"
            +
            "min confirm latency (%s),median confirm latency (%s)," +
            "75th p. confirm latency (%s),95th p. confirm latency (%s),99th p. confirm latency (%s)%n",
        unit, unit, unit, unit, unit, unit, unit, unit, unit, unit);
  }

  @Override
  public void report(Duration durationSinceStart, double publishedRate, double confirmedRate,
      double nackedRate, double returnedRate, double receivedRate, long[] confirmedLatencyStats,
      long[] consumerLatencyStats) {

    this.out.println(
        testId + "," + formatTime(durationSinceStart) + "," +
            rate(publishedRate, publishedEnabled) + "," +
            rate(returnedRate, publishedEnabled && returnedEnabled) + "," +
            rate(confirmedRate, publishedEnabled && confirmedEnabled) + "," +
            rate(nackedRate, publishedEnabled && confirmedEnabled) + "," +
            rate(receivedRate, receivedEnabled) + "," +
            (shouldDisplayConsumerLatency() ?
                consumerLatencyStats[0] + "," +
                    consumerLatencyStats[1] + "," +
                    consumerLatencyStats[2] + "," +
                    consumerLatencyStats[3] + "," +
                    consumerLatencyStats[4] + ","
                : ",,,,,") +
            (shouldDisplayConfirmLatency() ?
                confirmedLatencyStats[0] + "," +
                    confirmedLatencyStats[1] + "," +
                    confirmedLatencyStats[2] + "," +
                    confirmedLatencyStats[3] + "," +
                    confirmedLatencyStats[4]
                : ",,,,")

    );
  }

  @Override
  public void summary(Duration elapsed, double ratePublished, double rateReceived,
      long[] consumedLatencyTotal, long[] confirmedLatencyTotal) {

  }
}
