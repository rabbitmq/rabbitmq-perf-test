package com.rabbitmq.perf;

import static com.rabbitmq.perf.TestUtils.validXml;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

public class DefaultInstanceSynchronizationTest {

  static String XML = "<config xmlns=\"urn:org:jgroups\"\n"
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

  @Test
  void processConfigurationFileNamespaceIsReplaced() throws Exception {
    assertThat(DefaultInstanceSynchronization.processConfigurationFile(xml(), "performance-test"))
        .is(validXml())
        .contains("namespace=\"performance-test\"");
  }

  @Test
  void shouldContinueWhenClusterIsFormed() throws Exception {
    String id = UUID.randomUUID().toString();
    int expectedInstances = 3;
    Duration timeout = Duration.ofSeconds(30);
    List<DefaultInstanceSynchronization> syncs = IntStream.range(0, expectedInstances)
        .mapToObj(unused -> new DefaultInstanceSynchronization(
            id, expectedInstances, null, timeout
        )).collect(toList());
    CountDownLatch latch = new CountDownLatch(expectedInstances);
    ExecutorService executorService = Executors.newFixedThreadPool(expectedInstances);
    try {
      syncs.forEach(sync -> {
        executorService.submit((Callable<Void>) () -> {
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
