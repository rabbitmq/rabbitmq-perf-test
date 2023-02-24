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
package com.rabbitmq.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.AMQImpl;
import com.rabbitmq.client.impl.AMQImpl.Channel.Close;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** */
public class ConnectionRecoveryTest {

  private static final String QUEUE = "test-queue";
  @Mock ConnectionFactory cf;
  @Mock AutorecoveringConnection c;
  @Mock Channel ch;
  @Mock PerformanceMetrics performanceMetrics;
  @Mock MulticastSet.ThreadingHandler threadingHandler;
  @Mock ExecutorService executorService;
  @Mock ScheduledExecutorService scheduledExecutorService;
  @Mock Future future;
  MulticastParams params;
  AutoCloseable mocks;

  @BeforeEach
  @SuppressWarnings("unchecked")
  public void init() throws Exception {
    mocks = openMocks(this);

    when(cf.newConnection(anyList(), anyString())).thenReturn(c);
    when(c.createChannel()).thenReturn(ch);
    when(c.getClientProvidedName()).thenReturn("consumer-connection");
    DeclareOk declareOk = new AMQImpl.Queue.DeclareOk(QUEUE, 0, 0);
    when(ch.queueDeclare(eq(QUEUE), anyBoolean(), anyBoolean(), anyBoolean(), anyMap()))
        .thenReturn(declareOk);
    when(ch.queueDeclare(eq(QUEUE), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
        .thenReturn(declareOk);
    when(ch.getConnection()).thenReturn(c);
    when(ch.queueDeclarePassive(QUEUE))
        .thenThrow(
            new IOException(
                new ShutdownSignalException(
                    false, false, new Close(AMQP.NOT_FOUND, "", 0, 0), null)))
        .thenReturn(declareOk);

    when(threadingHandler.executorService(anyString(), anyInt())).thenReturn(executorService);
    when(threadingHandler.scheduledExecutorService(anyString(), anyInt()))
        .thenReturn(scheduledExecutorService);
    when(executorService.submit(any(Runnable.class))).thenReturn(future);

    params = new MulticastParams();
    params.setQueueNames(Collections.singletonList(QUEUE));
    params.setAutoDelete(false);
    params.setExclusive(false);
    params.setFlags(Collections.singletonList("persistent"));
    params.setCluster(true);
  }

  @AfterEach
  public void tearDown() throws Exception {
    mocks.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  void subscriptionShouldBeScheduledIfQueueDoesNotExist() throws Exception {
    // for https://github.com/rabbitmq/rabbitmq-perf-test/issues/330
    List<RecoveryListener> recoveryListeners = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              recoveryListeners.add(invocation.getArgument(0, RecoveryListener.class));
              return null;
            })
        .when(c)
        .addRecoveryListener(any(RecoveryListener.class));
    List<ShutdownListener> shutdownListeners = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              shutdownListeners.add(invocation.getArgument(0, ShutdownListener.class));
              return null;
            })
        .when(c)
        .addShutdownListener(any(ShutdownListener.class));
    CountDownLatch completionLatch = new CountDownLatch(1);
    CountDownLatch runStartedLatch = new CountDownLatch(1);
    new Thread(
            () -> {
              try {
                MulticastSet set = getMulticastSet(runStartedLatch, completionLatch);
                set.run();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .start();

    assertThat(runStartedLatch.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(recoveryListeners)
        .hasSize(5); // configuration x 3 (cluster) + consumer + producer connections
    assertThat(shutdownListeners).hasSize(2); // consumer + producer connections

    // initial subscription
    verify(ch, times(1))
        .basicConsume(anyString(), anyBoolean(), nullable(Map.class), any(Consumer.class));
    // subscription after recovery, not called yet
    verify(ch, never())
        .basicConsume(
            anyString(),
            anyBoolean(),
            anyString(),
            anyBoolean(),
            anyBoolean(),
            nullable(Map.class),
            any(Consumer.class));

    // synchronously call the scheduled subscription
    doAnswer(
            invocation -> {
              Callable<?> action = invocation.getArgument(0);
              action.call();
              return null;
            })
        .when(scheduledExecutorService)
        .schedule(any(Callable.class), anyLong(), any(TimeUnit.class));

    RecoveryListener consumerRecoveryListener = recoveryListeners.get(3);
    ShutdownListener consumerShutdownListener = shutdownListeners.get(1);

    // mimic connection recovery
    consumerShutdownListener.shutdownCompleted(
        new ShutdownSignalException(false, true, null, null));
    consumerRecoveryListener.handleRecoveryStarted(null);
    consumerRecoveryListener.handleRecovery(null);

    // first check fails, second check says the queue exists
    verify(ch, times(2)).queueDeclarePassive(QUEUE);
    // initial subscription, still the same
    verify(ch, times(1))
        .basicConsume(anyString(), anyBoolean(), nullable(Map.class), any(Consumer.class));
    // subscription after recovery
    verify(ch, times(1))
        .basicConsume(
            anyString(),
            anyBoolean(),
            nullable(String.class),
            anyBoolean(),
            anyBoolean(),
            nullable(Map.class),
            any(Consumer.class));
  }

  private MulticastSet getMulticastSet(
      CountDownLatch runStartedLatch, CountDownLatch completionLatch) {
    MulticastSet set =
        new MulticastSet(
            performanceMetrics,
            cf,
            params,
            Arrays.asList("amqp://localhost", "amqp://localhost", "amqp://localhost"),
            new MulticastSet.CompletionHandler() {

              @Override
              public void waitForCompletion() throws InterruptedException {
                runStartedLatch.countDown();
                completionLatch.await(10, TimeUnit.SECONDS);
              }

              @Override
              public void countDown(String reason) {}
            });
    set.setThreadingHandler(threadingHandler);
    return set;
  }
}
