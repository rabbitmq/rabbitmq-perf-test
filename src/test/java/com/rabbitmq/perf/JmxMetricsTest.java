// Copyright (c) 2018-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom
// Inc. and/or its subsidiaries.
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
package com.rabbitmq.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rabbitmq.perf.Metrics.ConfigurationContext;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.apache.commons.cli.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** */
public class JmxMetricsTest {

  Metrics metrics = new JmxMetrics();

  @AfterEach
  public void tearDown() throws Exception {
    metrics.close();
  }

  @Test
  public void metricsShouldBeExposedAsMbeans() throws Exception {
    Options options = metrics.options();

    CommandLineParser parser = new DefaultParser();
    CommandLine rawCmd = parser.parse(options, ("--metrics-jmx").split(" "));
    CommandLineProxy cmd = new CommandLineProxy(options, rawCmd, name -> null);
    CompositeMeterRegistry registry = new CompositeMeterRegistry();
    AtomicInteger metric = registry.gauge("dummy", new AtomicInteger(0));
    metric.set(42);
    metrics.configure(new ConfigurationContext(cmd, registry, null, null, null, null));

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    //        Thread.sleep(1000000000);
    Set<ObjectName> objectNames =
        server.queryNames(new ObjectName("*:name=dummy,type=gauges"), null);
    assertEquals(1, objectNames.size());
    ObjectName objectName = objectNames.iterator().next();
    MBeanInfo info = server.getMBeanInfo(objectName);
    Object attribute = server.getAttribute(objectName, info.getAttributes()[0].getName());
    assertEquals(metric.get(), Double.valueOf(attribute.toString()).intValue());
  }
}
