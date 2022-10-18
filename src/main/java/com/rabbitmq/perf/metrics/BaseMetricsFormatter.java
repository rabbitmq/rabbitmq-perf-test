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
import static com.rabbitmq.perf.metrics.MetricsFormatterUtils.formatLatency;
import static java.lang.String.format;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

abstract class BaseMetricsFormatter implements MetricsFormatter {

  protected final boolean publishedEnabled, receivedEnabled,
      returnedEnabled, confirmedEnabled;

  BaseMetricsFormatter(boolean publishedEnabled, boolean receivedEnabled, boolean returnedEnabled,
      boolean confirmedEnabled) {
    this.publishedEnabled = publishedEnabled;
    this.receivedEnabled = receivedEnabled;
    this.returnedEnabled = returnedEnabled;
    this.confirmedEnabled = confirmedEnabled;
  }

  protected boolean shouldDisplayConsumerLatency() {
    return receivedEnabled;
  }

  protected boolean shouldDisplayConfirmLatency() {
    return publishedEnabled && confirmedEnabled;
  }

  protected String summary(Duration elapsed, double ratePublished, double rateReceived,
      long[] consumedLatencyTotal,
      long[] confirmedLatencyTotal, String testId, TimeUnit latencyCollectionTimeUnit) {
    String lineSeparator = System.getProperty("line.separator");
    StringBuilder summary = new StringBuilder();
    String lineBeginning = "";
    if (testId != null) {
      lineBeginning = "id: " + testId + ", ";
    }
    summary.append(lineBeginning).append("sending rate avg: " +
        MetricsFormatterUtils.formatRate(ratePublished) +
        " " + MESSAGE_RATE_LABEL);
    summary.append(lineSeparator);

    if (elapsed.toMillis() > 0) {
      summary.append(lineBeginning).append("receiving rate avg: " +
          MetricsFormatterUtils.formatRate(rateReceived) +
          " " + MESSAGE_RATE_LABEL).append(lineSeparator);
      if (shouldDisplayConsumerLatency()) {
        summary.append(lineBeginning).append(format("consumer latency %s %s",
            LATENCY_HEADER, formatLatency(consumedLatencyTotal, latencyCollectionTimeUnit))
        ).append(lineSeparator);
      }
      if (shouldDisplayConfirmLatency()) {
        summary.append(lineBeginning).append(format("confirm latency %s %s",
            LATENCY_HEADER, formatLatency(confirmedLatencyTotal, latencyCollectionTimeUnit)
        )).append(lineSeparator);
      }
    }
    return summary.toString();
  }
}
