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
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 *
 */
public class JmxMetricsTest {

    Metrics metrics = new JmxMetrics();

    @AfterEach
    public void tearDown() throws Exception {
        metrics.close();
    }

    @Test
    public void metricsShouldBeExposedAsMbeans() throws Exception {
        Options options = metrics.options();

        CommandLineParser parser = new GnuParser();
        CommandLine rawCmd = parser.parse(
            options,
            ("--metrics-jmx").split(" ")
        );
        CommandLineProxy cmd = new CommandLineProxy(options, rawCmd, name -> null);
        CompositeMeterRegistry registry = new CompositeMeterRegistry();
        AtomicInteger metric = registry.gauge("dummy", new AtomicInteger(0));
        metric.set(42);
        metrics.configure(cmd, registry, null);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Set<ObjectName> objectNames = server.queryNames(new ObjectName("*:name=dummy"), null);
        assertEquals(1, objectNames.size());
        ObjectName objectName = objectNames.iterator().next();
        MBeanInfo info = server.getMBeanInfo(objectName);
        Object attribute = server.getAttribute(objectName, info.getAttributes()[0].getName());
        assertEquals(metric.get(), Double.valueOf(attribute.toString()).intValue());
    }
}
