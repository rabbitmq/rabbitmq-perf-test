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

package com.rabbitmq.perf.it;

import static com.rabbitmq.perf.TestUtils.threadFactory;
import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static com.rabbitmq.perf.it.Utils.latchCompletionHandler;
import static com.rabbitmq.perf.it.Utils.queueName;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.perf.MulticastParams;
import com.rabbitmq.perf.MulticastSet;
import com.rabbitmq.perf.Stats;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiscellaneousIT {

  static final String URI = "amqp://localhost";
  static final List<String> URIS = Collections.singletonList(URI);
  private static final Logger LOGGER = LoggerFactory.getLogger(MiscellaneousIT.class);
  MulticastParams params;

  ExecutorService executorService;

  ConnectionFactory cf;

  AtomicBoolean testIsDone;
  CountDownLatch testLatch;

  Stats stats =
      new Stats(1000, false, new CompositeMeterRegistry(), "") {
        @Override
        protected void report(long now) {}
      };

  @BeforeEach
  public void init(TestInfo info) {
    executorService = Executors.newCachedThreadPool(threadFactory(info));
    params = new MulticastParams();
    cf = new ConnectionFactory();
    testIsDone = new AtomicBoolean(false);
    testLatch = new CountDownLatch(1);
  }

  @AfterEach
  public void tearDown() throws InterruptedException {
    LOGGER.info("Shutting down test executor");
    executorService.shutdownNow();
    if (!testLatch.await(10, TimeUnit.SECONDS)) {
      LOGGER.warn(
          "PerfTest run didn't shut down properly, run logs may show up during other tests");
    }
  }

  @Test
  void consumerShouldAckWhenRateLimitationIsEnabled(TestInfo info) throws Exception {
    int messageCount = 1000;
    String queue = queueName(info);
    // creating and loading the queue
    ConnectionFactory connectionFactory = new ConnectionFactory();
    try (Connection c = connectionFactory.newConnection()) {
      Channel ch = c.createChannel();
      ch.queueDeclare(queue, true, false, false, null);
      ch.confirmSelect();
      for (int i = 0; i < messageCount; i++) {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(acc);
        long time = System.nanoTime();
        d.writeInt(i);
        d.writeLong(time);
        d.flush();
        acc.flush();
        ch.basicPublish("", queue, null, acc.toByteArray());
      }
      ch.waitForConfirmsOrDie(10_000);
    }

    // using the pre-defined queue
    params.setQueueNames(Collections.singletonList(queue));
    params.setRoutingKey(queue);
    params.setPredeclared(true);
    // 1 consumer only
    params.setProducerCount(0);
    params.setConsumerCount(1);
    params.setConsumerMsgCount(messageCount);
    params.setConsumerRateLimit(300);
    params.setAutoAck(false);
    params.setMultiAckEvery(10);
    params.setConsumerPrefetch(10);

    try {
      MulticastSet set =
          new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1, info));
      run(set);

      waitAtMost(180, () -> testIsDone.get());
    } finally {
      try (Connection c = connectionFactory.newConnection()) {
        Channel ch = c.createChannel();
        ch.queueDelete(queue);
      }
    }
  }

  private void run(MulticastSet multicastSet) {
    executorService.submit(
        () -> {
          try {
            multicastSet.run();
            testIsDone.set(true);
            testLatch.countDown();
          } catch (InterruptedException e) {
            // one of the tests stops the execution, no need to be noisy
            throw new RuntimeException(e);
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
  }
}
