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

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class CompositeMetrics implements Metrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeMetrics.class);

    private final List<Metrics> metrics = new ArrayList<>();

    public CompositeMetrics() {
        metrics.add(new BaseMetrics());
        metrics.add(new PrometheusMetrics());
    }

    @Override
    public Options options() {
        Options options = new Options();
        for (Metrics metric : metrics) {
            for (Object optObj : metric.options().getOptions()) {
                Option option = (Option) optObj;
                if (options.hasOption(option.getOpt())) {
                    throw new IllegalStateException("Option already existing: " + option.getOpt());
                } else {
                    options.addOption(option);
                }
            }
        }
        return options;
    }

    @Override
    public void configure(CommandLineProxy cmd, CompositeMeterRegistry meterRegistry) throws Exception {
        for (Metrics metric : metrics) {
            metric.configure(cmd, meterRegistry);
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
