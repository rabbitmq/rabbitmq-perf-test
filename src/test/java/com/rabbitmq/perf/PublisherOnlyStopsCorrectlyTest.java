// Copyright (c) 2018-2023 Broadcom. All Rights Reserved.
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

import static com.rabbitmq.perf.MockUtils.callback;
import static com.rabbitmq.perf.MockUtils.connectionFactoryThatReturns;
import static com.rabbitmq.perf.MockUtils.proxy;
import static com.rabbitmq.perf.TestUtils.threadFactory;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.AMQImpl;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** */
public class PublisherOnlyStopsCorrectlyTest {

  MulticastParams params;

  PerformanceMetrics performanceMetrics = PerformanceMetrics.NO_OP;

  ExecutorService executorService;

  static Stream<Arguments> publisherOnlyStopsWhenBrokerCrashesArguments() {
    return Stream.of(
        // number of messages before throwing exception, configurator, assertion message
        Arguments.of(
            10,
            (Consumer<MulticastParams>) (params) -> {},
            "Sender should have failed and program should stop"),
        Arguments.of(
            2,
            (Consumer<MulticastParams>)
                (params) -> params.setPublishingInterval(Duration.ofSeconds(1)),
            "Sender should have failed and program should stop"));
  }

  @BeforeEach
  public void init(TestInfo info) {
    params = new MulticastParams();
    executorService = Executors.newSingleThreadExecutor(threadFactory(info));
  }

  @AfterEach
  public void tearDown() {
    executorService.shutdownNow();
  }

  @ParameterizedTest
  @MethodSource("publisherOnlyStopsWhenBrokerCrashesArguments")
  public void publisherOnlyStopsWhenBrokerCrashes(
      int messageTotal, Consumer<MulticastParams> configurator, String message) throws Exception {
    params.setConsumerCount(0);
    params.setProducerCount(1);
    configurator.accept(params);

    AtomicInteger publishedMessages = new AtomicInteger(0);
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "queueDeclare",
                (proxy, method, args) -> new AMQImpl.Queue.DeclareOk(args[0].toString(), 0, 0)),
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  if (publishedMessages.incrementAndGet() > messageTotal) {
                    throw new RuntimeException("Expected exception, simulating broker crash");
                  }
                  return null;
                }));

    Supplier<Connection> connectionSupplier =
        () ->
            proxy(
                Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("isOpen", (proxy, method, args) -> true));

    ConnectionFactory connectionFactory = connectionFactoryThatReturns(connectionSupplier);

    MulticastSet set = getMulticastSet(connectionFactory);

    CountDownLatch latch = new CountDownLatch(1);
    executorService.submit(
        (Callable<Void>)
            () -> {
              set.run();
              latch.countDown();
              return null;
            });
    assertThat(latch.await(10, TimeUnit.SECONDS)).as(message).isTrue();
  }

  private MulticastSet getMulticastSet(ConnectionFactory connectionFactory) {
    MulticastSet set =
        new MulticastSet(
            performanceMetrics,
            connectionFactory,
            params,
            singletonList("amqp://localhost"),
            PerfTest.getCompletionHandler(params, new ConcurrentHashMap<>()));
    set.setThreadingHandler(new MulticastSet.DefaultThreadingHandler());
    return set;
  }
}
