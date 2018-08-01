// Copyright (c) 2018 Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
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

import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.rabbitmq.perf.OptionsUtils.forEach;

/**
 *
 */
public class CompositeMetrics implements Metrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeMetrics.class);

    private final List<Metrics> metrics = new ArrayList<>();

    public CompositeMetrics() {
        metrics.add(new BaseMetrics());
        metrics.add(new PrometheusMetrics());
        metrics.add(new DatadogMetrics());
        metrics.add(new JmxMetrics());
    }

    @Override
    public Options options() {
        Options options = new Options();
        for (Metrics metric : metrics) {
            forEach(metric.options(), option -> {
                if (options.hasOption(option.getOpt())) {
                    throw new IllegalStateException("Option already existing: " + option.getOpt());
                } else {
                    options.addOption(option);
                }
            });
        }
        return options;
    }

    @Override
    public void configure(CommandLineProxy cmd, CompositeMeterRegistry meterRegistry, ConnectionFactory factory) throws Exception {
        for (Metrics metric : metrics) {
            metric.configure(cmd, meterRegistry, factory);
        }
    }

    @Override
    public boolean isEnabled(CommandLineProxy cmd) {
        for (Metrics metric : metrics) {
            if (metric.isEnabled(cmd)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (Metrics metric : metrics) {
            try {
                metric.close();
            } catch (Exception e) {
                LOGGER.warn("Error while closing metrics {}", metrics, e);
            }
        }
    }
}
