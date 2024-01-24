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

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

abstract class MetricsFormatterUtils {

  static final String LATENCY_HEADER = "min/median/75th/95th/99th";
  static final String MESSAGE_RATE_LABEL = "msg/s";

  static final float NANO_TO_SECOND = 1_000_000_000;

  private MetricsFormatterUtils() {}

  static String formatTime(Duration time) {
    return format("%.3f", time.toNanos() / NANO_TO_SECOND);
  }

  static String formatRate(double rate) {
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

  static String formatLatency(long[] stats, TimeUnit latencyCollectionTimeUnit) {
    return format(
        "%d/%d/%d/%d/%d %s",
        stats[0], stats[1], stats[2], stats[3], stats[4], unit(latencyCollectionTimeUnit));
  }

  private static String unit(TimeUnit latencyCollectionTimeUnit) {
    if (latencyCollectionTimeUnit == MILLISECONDS) {
      return "ms";
    } else if (latencyCollectionTimeUnit == NANOSECONDS) {
      // we use nanoseconds to measure duration, but microseconds for the output
      return "µs";
    } else {
      throw new IllegalArgumentException("Time unit not supported: " + latencyCollectionTimeUnit);
    }
  }
}
