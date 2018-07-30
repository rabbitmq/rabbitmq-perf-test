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

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class MetricsTest {

    @Test
    public void noDuplicateOptionBetweenMetrics() {
        Set<String> options = new HashSet<>();
        List<Metrics> metrics = new ArrayList<>();
        metrics.add(new BaseMetrics());
        metrics.add(new DatadogMetrics());
        metrics.add(new JmxMetrics());
        metrics.add(new PrometheusMetrics());
        for (Metrics metric : metrics) {
            for (Object optObj : metric.options().getOptions()) {
                Option option = (Option) optObj;
                assertTrue(options.add(option.getOpt()), "Option already exists: " + option.getOpt());
            }
        }
    }

    @Test
    public void noDuplicateOptionWithPerfTest() {
        Options perfTestOptions = PerfTest.getOptions();
        for (Object optObj : new CompositeMetrics().options().getOptions()) {
            Option option = (Option) optObj;
            assertFalse(perfTestOptions.hasOption(option.getOpt()), "Option already exists: " + option.getOpt());
        }
    }
}
