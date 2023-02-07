// Copyright (c) 2023 VMware, Inc. or its affiliates.  All rights reserved.
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.Receiver;
import org.jgroups.StateTransferException;
import org.jgroups.View;
import org.jgroups.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultInstanceSynchronization implements InstanceSynchronization {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      DefaultInstanceSynchronization.class);

  private final InstanceSynchronization delegate;

  DefaultInstanceSynchronization(String id, int expectedInstances, String namespace,
      Duration timeout) {
    if (expectedInstances > 1) {
      this.delegate = new JGroupsInstanceSynchronization(id, expectedInstances, namespace, timeout);
    } else {
      this.delegate = new NoOpInstanceSynchronization();
    }
  }

  static String processConfigurationFile(InputStream configuration, String namespace)
      throws IOException {
    BufferedReader in = new BufferedReader(new InputStreamReader(configuration));
    final int bufferSize = 1024;
    final char[] buffer = new char[bufferSize];
    StringBuilder builder = new StringBuilder();
    int charsRead;
    while ((charsRead = in.read(buffer, 0, buffer.length)) > 0) {
      builder.append(buffer, 0, charsRead);
    }
    configuration.close();
    return builder.toString().replace("${namespace}", namespace);
  }

  @Override
  public void synchronize() throws Exception {
    this.delegate.synchronize();
  }

  private static class NoOpInstanceSynchronization implements InstanceSynchronization {

    @Override
    public void synchronize() {

    }

  }

  private static class JGroupsInstanceSynchronization implements InstanceSynchronization {

    private final String id;
    private final int expectedInstances;
    private final Duration timeout;
    private final JChannel channel;

    private JGroupsInstanceSynchronization(String id, int expectedInstances, String namespace,
        Duration timeout) {
      this.id = id;
      this.expectedInstances = expectedInstances;
      this.timeout = timeout;
      InputStream configuration;
      try {
        if (namespace == null) {
          configuration = PerfTest.class.getResourceAsStream("/jgroups-multicast-perf-test.xml");
        } else {
          configuration = PerfTest.class.getResourceAsStream("/jgroups-k8s-perf-test.xml");
          configuration = new ByteArrayInputStream(
              processConfigurationFile(configuration, namespace).getBytes(StandardCharsets.UTF_8)
          );
        }
        this.channel = new JChannel(configuration);
        configuration.close();
      } catch (Exception e) {
        throw new PerfTestException("Error while configuring instance synchronization",
            e);
      }
    }

    @Override
    public void synchronize() throws Exception {
      long start = System.nanoTime();
      LOGGER.debug("Instance start synchronization...");
      LOGGER.debug("Expected instance count for cluster '{}': {}", id, expectedInstances);
      AtomicInteger instanceCount = new AtomicInteger(0);
      CountDownLatch expectedInstanceCountReachedLatch = new CountDownLatch(1);
      AtomicBoolean expectedInstanceCountReached = new AtomicBoolean(false);

      channel.setReceiver(new Receiver() {
        @Override
        public void receive(Message msg) {

        }

        @Override
        public void viewAccepted(View newView) {
          instanceCount.set(newView.size());
          LOGGER.debug("New cluster view, number of nodes: {} ({})", newView.size(),
              newView.getMembers());
          if (instanceCount.get() == expectedInstances) {
            if (expectedInstanceCountReached.compareAndSet(false, true)) {
              LOGGER.debug("New view has expected number of nodes, starting...");
              expectedInstanceCountReachedLatch.countDown();
            }

          }
        }

        @Override
        public void getState(OutputStream output) {
          try {
            Util.objectToStream(instanceCount.get(), new DataOutputStream(output));
          } catch (Exception e) {
            LOGGER.warn("Error while getting JGroups state: {}", e.getMessage());
          }
        }

        @Override
        public void setState(InputStream input) throws Exception {
          Integer c = Util.objectFromStream(new DataInputStream(input));
          LOGGER.debug("Received state, number of nodes: {}", c);
          if (c == expectedInstances) {
            if (expectedInstanceCountReached.compareAndSet(false, true)) {
              LOGGER.debug("State has expected number of nodes, starting...");
              expectedInstanceCountReached.set(true);
              expectedInstanceCountReachedLatch.countDown();
            }
          }
        }
      });

      try {
        channel.connect(id, null, timeout.toMillis());
      } catch (StateTransferException e) {
        if (!expectedInstanceCountReached.get()) {
          throw e;
        }
      }

      boolean allInstancesJoined = expectedInstanceCountReachedLatch.await(timeout.toMillis(),
          TimeUnit.MILLISECONDS);
      if (!allInstancesJoined) {
        throw new IllegalStateException("Waited " + timeout.getSeconds()
            + " second(s) and expected number of "
            + "PerfTest instances did not join");
      }
      LOGGER.debug("All expected instances started after {} ms", Duration.ofNanos(
          System.nanoTime() - start
      ).toMillis());

      try {
        channel.close();
      } catch (Exception e) {
        LOGGER.info("Error while closing JGroups channel: {}", e.getMessage());
      }
    }

  }

}
