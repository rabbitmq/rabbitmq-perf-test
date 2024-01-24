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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composite formatter that calls a list of formatters for each method.
 *
 * <p>Useful to combine the effects of several formatters.
 *
 * @since 2.19.0
 */
public class CompositeMetricsFormatter implements MetricsFormatter {

  private static final Logger LOGGER = LoggerFactory.getLogger(CompositeMetricsFormatter.class);
  private final List<MetricsFormatter> delegates = new CopyOnWriteArrayList<>();

  public CompositeMetricsFormatter(MetricsFormatter... formatters) {
    this.delegates.addAll(Arrays.asList(formatters));
  }

  private static <T> void iterate(List<T> delegates, Consumer<T> action, String description) {
    for (T delegate : delegates) {
      try {
        action.accept(delegate);
      } catch (Exception e) {
        LOGGER.warn(
            "Error while executing '{}' on '{}' metrics collector: {}",
            description,
            delegate.getClass().getSimpleName(),
            e.getMessage());
      }
    }
  }

  @Override
  public void header() {
    iterate(this.delegates, MetricsFormatter::header, "header");
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
    iterate(
        this.delegates,
        f ->
            f.report(
                durationSinceStart,
                publishedRate,
                confirmedRate,
                nackedRate,
                returnedRate,
                receivedRate,
                confirmedLatencyStats,
                consumerLatencyStats),
        "report");
  }

  @Override
  public void summary(
      Duration elapsed,
      double ratePublished,
      double rateReceived,
      long[] consumedLatencyTotal,
      long[] confirmedLatencyTotal) {
    iterate(
        this.delegates,
        f ->
            f.summary(
                elapsed, ratePublished, rateReceived, consumedLatencyTotal, confirmedLatencyTotal),
        "summary");
  }
}
