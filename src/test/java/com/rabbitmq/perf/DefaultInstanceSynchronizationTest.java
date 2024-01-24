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
package com.rabbitmq.perf;

import static com.rabbitmq.perf.TestUtils.validXml;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

@EnabledForJreRange(min = JRE.JAVA_11)
public class DefaultInstanceSynchronizationTest {

  static String XML =
      "<config xmlns=\"urn:org:jgroups\"\n"
          + "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
          + "  xsi:schemaLocation=\"urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd\">\n"
          + "  <TCP bind_port=\"7800\" bind_addr=\"SITE_LOCAL\"\n"
          + "    />\n"
          + "  <org.jgroups.protocols.kubernetes.KUBE_PING\n"
          + "    namespace=\"${namespace}\"\n"
          + "    port_range=\"1\"\n"
          + "  />\n"
          + "  <MERGE3 max_interval=\"30000\"\n"
          + "    min_interval=\"10000\"/>\n"
          + "  <VERIFY_SUSPECT timeout=\"1500\"  />\n"
          + "  <BARRIER />\n"
          + "  <pbcast.NAKACK2 xmit_interval=\"500\"\n"
          + "    xmit_table_num_rows=\"100\"\n"
          + "    xmit_table_msgs_per_row=\"2000\"\n"
          + "    xmit_table_max_compaction_time=\"30000\"\n"
          + "    use_mcast_xmit=\"false\"\n"
          + "    discard_delivered_msgs=\"true\"/>\n"
          + "  <UNICAST3\n"
          + "    xmit_table_num_rows=\"100\"\n"
          + "    xmit_table_msgs_per_row=\"1000\"\n"
          + "    xmit_table_max_compaction_time=\"30000\"/>\n"
          + "  <pbcast.STABLE desired_avg_gossip=\"50000\"\n"
          + "    max_bytes=\"8m\"/>\n"
          + "  <pbcast.GMS print_local_addr=\"true\" join_timeout=\"3000\" />\n"
          + "  <MFC max_credits=\"4M\"\n"
          + "    min_threshold=\"0.4\"/>\n"
          + "  <FRAG2 frag_size=\"60K\"  />\n"
          + "  <pbcast.STATE_TRANSFER />\n"
          + "\n"
          + "</config>";

  static InputStream xml() {
    return new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8));
  }

  private static PrintStream noOpPrintStream() {
    return new PrintStream(
        new OutputStream() {
          @Override
          public void write(int b) {}
        });
  }

  @Test
  void processConfigurationFileNamespaceIsReplaced() throws Exception {
    Class<?> defaultClass = Class.forName("com.rabbitmq.perf.DefaultInstanceSynchronization");
    Method method =
        defaultClass.getDeclaredMethod("processConfigurationFile", InputStream.class, String.class);
    assertThat(method.invoke(null, xml(), "performance-test").toString())
        .is(validXml())
        .contains("namespace=\"performance-test\"");
  }

  @Test
  void shouldContinueWhenClusterIsFormed() throws Exception {
    String id = UUID.randomUUID().toString();
    int expectedInstances = 3;
    Duration timeout = Duration.ofSeconds(30);
    List<InstanceSynchronization> syncs =
        IntStream.range(0, expectedInstances)
            .mapToObj(
                unused ->
                    Utils.defaultInstanceSynchronization(
                        id, expectedInstances, null, timeout, noOpPrintStream()))
            .collect(toList());
    CountDownLatch latch = new CountDownLatch(expectedInstances);
    ExecutorService executorService = Executors.newFixedThreadPool(expectedInstances);
    try {
      syncs.forEach(
          sync -> {
            executorService.submit(
                (Callable<Void>)
                    () -> {
                      sync.synchronize();
                      latch.countDown();
                      return null;
                    });
          });
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      executorService.shutdownNow();
    }
  }
}
