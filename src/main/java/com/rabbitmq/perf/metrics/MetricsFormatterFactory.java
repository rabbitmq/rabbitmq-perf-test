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

import static java.util.stream.Collectors.joining;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Factory to create {@link MetricsFormatter}.
 *
 * @since 2.19.0
 */
public class MetricsFormatterFactory {

  private static final Map<String, Function<Context, MetricsFormatter>> FACTORIES = new ConcurrentHashMap<String, Function<Context, MetricsFormatter>>() {{
    put("default", context -> new DefaultPrintStreamMetricsFormatter(context.out, context.testId,
        context.publishedEnabled, context.receivedEnabled, context.returnedEnabled,
        context.confirmedEnabled,
        context.latencyCollectionTimeUnit));
    put("compact", context -> new CompactPrintStreamMetricsFormatter(context.out,
        context.publishedEnabled, context.receivedEnabled, context.returnedEnabled,
        context.confirmedEnabled,
        context.latencyCollectionTimeUnit));
  }};

  public static List<String> types() {
    List<String> types = new ArrayList<>(FACTORIES.keySet());
    Collections.sort(types);
    return types;
  }

  public static MetricsFormatter create(String type, Context context) {
    Function<Context, MetricsFormatter> factory = FACTORIES.get(type);
    if (factory == null) {
      throw new IllegalArgumentException(
          String.format("Unknown metrics formatter: %s. Possible values are %s.",
              type, types().stream().collect(joining(", "))));
    }
    return factory.apply(context);
  }

  public static class Context {

    private final PrintStream out;
    private final String testId;
    private final boolean publishedEnabled;
    private final boolean receivedEnabled;
    private final boolean returnedEnabled;
    private final boolean confirmedEnabled;
    private final TimeUnit latencyCollectionTimeUnit;

    public Context(PrintStream out, String testId,
        boolean publishedEnabled,
        boolean receivedEnabled,
        boolean returnedEnabled, boolean confirmedEnabled,
        TimeUnit latencyCollectionTimeUnit) {

      this.out = out;
      this.testId = testId;
      this.publishedEnabled = publishedEnabled;
      this.receivedEnabled = receivedEnabled;
      this.returnedEnabled = returnedEnabled;
      this.confirmedEnabled = confirmedEnabled;
      this.latencyCollectionTimeUnit = latencyCollectionTimeUnit;
    }

  }

}
