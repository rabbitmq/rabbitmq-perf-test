// Copyright (c) 2018 VMware, Inc. or its affiliates.  All rights reserved.
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

import static com.rabbitmq.perf.MockUtils.*;
import static com.rabbitmq.perf.TestUtils.threadFactory;
import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static java.lang.String.format;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.anyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rabbitmq.client.*;
import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.perf.PerfTest.EXIT_WHEN;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageCountTimeLimitAndPublishingIntervalRateTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(MessageCountTimeLimitAndPublishingIntervalRateTest.class);
  private static final Duration SECONDS_1 = Duration.ofSeconds(1);
  private static final Duration SECONDS_3 = Duration.ofSeconds(3);
  private static final Duration SECONDS_5 = Duration.ofSeconds(5);
  static Set<String> THREADS = new LinkedHashSet<>();
  MulticastSet.CompletionHandler completionHandler;
  PerformanceMetrics performanceMetrics = PerformanceMetrics.NO_OP;
  MulticastParams params;
  AtomicInteger agentStartedCount = new AtomicInteger(0);
  ExecutorService executorService;
  MulticastSet.ThreadingHandler th;
  volatile AtomicBoolean testIsDone;
  volatile Duration testDurationInMs;
  CountDownLatch runStartedLatch;
  ConcurrentMap<String, Integer> shutdownReasons;

  static Stream<Arguments> producerCountArguments() {
    return Stream.of(
        Arguments.of(1, 1), Arguments.of(3, 1), Arguments.of(1, 3), Arguments.of(2, 3));
  }

  static Stream<Arguments> consumerCountArguments() {
    return Stream.of(
        Arguments.of(1, 1), Arguments.of(3, 1), Arguments.of(1, 3), Arguments.of(2, 3));
  }

  static Arguments[] consumerActivityArguments() {
    List<Arguments> consumerArguments = consumerCountArguments().collect(Collectors.toList());
    List<Arguments> result = new ArrayList<>();
    Stream.of("idle", "empty")
        .forEach(
            exitWhen -> {
              for (Arguments consumerArgument : consumerArguments) {
                result.add(
                    Arguments.arguments(
                        exitWhen, consumerArgument.get()[0], consumerArgument.get()[1]));
              }
            });
    return result.toArray(new Arguments[0]);
  }

  static Stream<Arguments> pollingWithBasicGetArguments() {
    return Stream.of(
        Arguments.of(1, 1), Arguments.of(3, 1), Arguments.of(1, 3), Arguments.of(2, 3));
  }

  @BeforeEach
  public void init(TestInfo info) {
    LOGGER.info("Starting {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
    Set<Thread> threads = Thread.getAllStackTraces().keySet();
    if (THREADS.isEmpty()) {
      for (Thread thread : threads) {
        THREADS.add(thread.getId() + " " + thread.getName());
      }
    } else {
      for (Thread thread : threads) {
        String key = thread.getId() + " " + thread.getName();
        if (THREADS.add(key)) {
          LOGGER.warn("Thread not present in the previous test: {}", key);
        }
      }
    }
    LOGGER.info("Existing threads: {}", THREADS);

    testIsDone = new AtomicBoolean(false);
    executorService = Executors.newCachedThreadPool(threadFactory(info));
    th = new MulticastSet.DefaultThreadingHandler();
    testDurationInMs = ofSeconds(-1);
    params = new MulticastParams();
    params.setPredeclared(true);
    agentStartedCount.set(0);
    params.setStartListener((id, type) -> agentStartedCount.incrementAndGet());
    runStartedLatch = new CountDownLatch(1);
    shutdownReasons = new ConcurrentHashMap<>();
    LOGGER.info(
        "Done initializing {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
  }

  @AfterEach
  public void tearDown(TestInfo info) throws InterruptedException {
    LOGGER.info("Tearing down {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
    th.shutdown();
    List<Runnable> runnables = executorService.shutdownNow();
    if (runnables.size() > 0) {
      LOGGER.warn("Some tasks are still waiting execution: {}", runnables);
    }
    boolean allTerminated = executorService.awaitTermination(5, SECONDS);
    if (!allTerminated) {
      LOGGER.warn("All tasks couldn't finish in time");
    }
    if (!testIsDone.get()) {
      LOGGER.warn("PerfTest run not done");
    }
    LOGGER.info(
        "Done tearing down {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
  }

  @Test
  public void noLimit() throws Exception {
    countsAndTimeLimit(0, 0, 0);

    int nbMessages = 10;
    CountDownLatch publishedLatch = new CountDownLatch(nbMessages);
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedLatch.countDown();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    AtomicInteger connectionCloseCalls = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    assertTrue(
        publishedLatch.await(10, SECONDS),
        () ->
            format(
                "Only %d / %d messages have been published",
                publishedLatch.getCount(), nbMessages));

    assertThat(testIsDone.get()).isFalse();
    // only the configuration connection has been closed
    // so the test is still running in the background
    assertThat(connectionCloseCalls.get()).isEqualTo(1);
    completionHandler.countDown("");
  }

  // --time 3
  @Test
  public void timeLimit() throws InterruptedException {
    countsAndTimeLimit(0, 0, 3);

    Channel channel =
        proxy(
            Channel.class,
            callback("basicPublish", (proxy, method, args) -> null),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    AtomicInteger closeCount = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> closeCount.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    waitAtMost(15, () -> testIsDone.get());

    assertThat(testDurationInMs).isGreaterThanOrEqualTo(SECONDS_3);
    assertThat(closeCount.get())
        .isEqualTo(4); // the configuration connection is actually closed twice
    assertThat(shutdownReasons).hasSize(1).containsKey(MulticastSet.STOP_REASON_REACHED_TIME_LIMIT);
  }

  // -y 1 --pmessages 10 -x n -X m
  @ParameterizedTest
  @MethodSource("producerCountArguments")
  public void producerCount(int producersCount, int channelsCount) throws Exception {
    int messagesCount = 100;
    countsAndTimeLimit(messagesCount, 0, 0);
    params.setProducerCount(producersCount);
    params.setProducerChannelCount(channelsCount);

    int messagesTotal = producersCount * channelsCount * messagesCount;
    CountDownLatch publishedLatch = new CountDownLatch(messagesTotal);
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedLatch.countDown();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    assertTrue(
        publishedLatch.await(60, SECONDS),
        () ->
            format(
                "Only %d / %d messages have been published",
                publishedLatch.getCount(), messagesTotal));
    waitAtMost(20, () -> testIsDone.get());
    assertThat(shutdownReasons)
        .hasSize(1)
        .containsKey(Producer.STOP_REASON_PRODUCER_MESSAGE_LIMIT)
        .containsValue(producersCount * channelsCount);

    assertThat(agentStartedCount).hasValue(producersCount * channelsCount + 1);
  }

  @ParameterizedTest
  @CsvSource({"PT2S", "PT0S"})
  void consumerStartDelayIsEnforced(Duration consumerStartDelay) throws Exception {
    countsAndTimeLimit(0, 0, (int) consumerStartDelay.plus(SECONDS_1).getSeconds());
    params.setConsumerStartDelay(consumerStartDelay);
    params.setQueueNames(asList("queue"));

    CountDownLatch consumersLatch = new CountDownLatch(1);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);
    AtomicLong basicConsumeTime = new AtomicLong();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  basicConsumeTime.set(System.nanoTime());
                  consumersLatch.countDown();
                  return consumerTagCounter.getAndIncrement() + "";
                }));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    long start = System.nanoTime();
    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(5, SECONDS))
        .as("consumer should have been registered by now")
        .isTrue();

    assertThat(ofNanos(basicConsumeTime.get() - start))
        .isCloseTo(consumerStartDelay, ofMillis(500));

    waitAtMost(20, () -> testIsDone.get());
    assertThat(shutdownReasons).hasSize(1).containsKey(MulticastSet.STOP_REASON_REACHED_TIME_LIMIT);
  }

  // --cmessages 10 -y n -Y m
  @ParameterizedTest
  @MethodSource("consumerCountArguments")
  public void consumerCount(int consumersCount, int channelsCount) throws Exception {
    int messagesCount = consumersCount * channelsCount;
    countsAndTimeLimit(0, messagesCount, 0);
    params.setConsumerCount(consumersCount);
    params.setConsumerChannelCount(channelsCount);
    params.setQueueNames(asList("queue"));

    CountDownLatch consumersLatch = new CountDownLatch(consumersCount * channelsCount);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);
    List<Consumer> consumers = new CopyOnWriteArrayList<>();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  consumers.add((Consumer) args[3]);
                  consumersLatch.countDown();
                  return consumerTagCounter.getAndIncrement() + "";
                }));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(5, SECONDS))
        .as(consumersCount * channelsCount + " consumer(s) should have been registered by now")
        .isTrue();

    waitAtMost(20, () -> consumers.size() == (consumersCount * channelsCount));

    Collection<Future<?>> sendTasks = new ArrayList<>(consumers.size());
    for (Consumer consumer : consumers) {
      Collection<Future<?>> tasks = sendMessagesToConsumer(messagesCount, consumer);
      sendTasks.addAll(tasks);
    }

    for (Future<?> latch : sendTasks) {
      latch.get(10, SECONDS);
    }

    waitAtMost(20, () -> testIsDone.get());
    assertThat(shutdownReasons)
        .hasSize(1)
        .containsKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT)
        .containsValue(consumersCount * channelsCount);

    assertThat(agentStartedCount).hasValue(consumersCount * channelsCount + 1);
  }

  @ParameterizedTest
  @MethodSource("consumerActivityArguments")
  public void consumerActivity(String exitWhen, int consumersCount, int channelsCount)
      throws Exception {
    int messagesCount = consumersCount * channelsCount;
    params.setExitWhen(EXIT_WHEN.valueOf(exitWhen.toUpperCase(Locale.ENGLISH)));
    params.setConsumerCount(consumersCount);
    params.setConsumerChannelCount(channelsCount);
    params.setQueueNames(asList("queue"));

    CountDownLatch consumersLatch = new CountDownLatch(consumersCount * channelsCount);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);
    List<Consumer> consumers = new CopyOnWriteArrayList<>();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  consumers.add((Consumer) args[3]);
                  consumersLatch.countDown();
                  return consumerTagCounter.getAndIncrement() + "";
                }),
            callback(
                "queueDeclarePassive",
                (proxy, method, args) ->
                    new DeclareOk.Builder().queue("test").messageCount(0).build()));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(5, SECONDS))
        .as(consumersCount * channelsCount + " consumer(s) should have been registered by now")
        .isTrue();

    waitAtMost(20, () -> consumers.size() == (consumersCount * channelsCount));

    Collection<Future<?>> sendTasks = new ArrayList<>(consumers.size());
    for (Consumer consumer : consumers) {
      Collection<Future<?>> tasks = sendMessagesToConsumer(messagesCount, consumer);
      sendTasks.addAll(tasks);
    }

    for (Future<?> latch : sendTasks) {
      latch.get(10, SECONDS);
    }

    waitAtMost(20, () -> testIsDone.get());
    assertThat(shutdownReasons)
        .hasSize(1)
        .hasKeySatisfying(
            anyOf(
                isKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_IDLE),
                isKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_QUEUE_EMPTY)))
        .containsValue(consumersCount * channelsCount);
  }

  static Condition<String> isKey(String value) {
    return new Condition<>(key -> key.equals(value), "'%s' is a key", value);
  }

  @Test
  void shouldAckOneLastTimeWhenQueueIsEmpty() throws Exception {
    int messagesCount = 15;
    params.setExitWhen(EXIT_WHEN.EMPTY);
    params.setQueueNames(asList("queue"));
    params.setMultiAckEvery(10);

    CountDownLatch consumersLatch = new CountDownLatch(1);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);
    AtomicReference<Consumer> consumer = new AtomicReference<>();
    AtomicInteger ackCount = new AtomicInteger();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  consumer.set((Consumer) args[3]);
                  consumersLatch.countDown();
                  return consumerTagCounter.getAndIncrement() + "";
                }),
            callback(
                "queueDeclarePassive",
                (proxy, method, args) ->
                    new DeclareOk.Builder().queue("test").messageCount(0).build()),
            callback(
                "basicAck",
                (proxy, method, args) -> {
                  ackCount.incrementAndGet();
                  return null;
                }));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(5, SECONDS))
        .as("consumer should have been registered by now")
        .isTrue();

    waitAtMost(20, () -> consumer.get() != null);

    sendMessagesToConsumerSync(messagesCount, consumer.get()).get(10, SECONDS);

    waitAtMost(20, () -> testIsDone.get());
    assertThat(shutdownReasons)
        .hasSize(1)
        .hasKeySatisfying(
            anyOf(
                isKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_IDLE),
                isKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_QUEUE_EMPTY)))
        .containsValue(1);

    assertThat(ackCount).hasValue(1 + 1);
  }

  // --time 5 -x 1 --pmessages 10 -y 1 --cmessages 10
  @Test
  public void timeLimitTakesPrecedenceOverCounts() throws Exception {
    int nbMessages = 10;
    countsAndTimeLimit(nbMessages, nbMessages, 5);
    params.setQueueNames(asList("queue"));

    CountDownLatch publishedLatch = new CountDownLatch(nbMessages);

    CountDownLatch consumersLatch = new CountDownLatch(1);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);
    AtomicReference<Consumer> consumer = new AtomicReference<>();

    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedLatch.countDown();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L),
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  consumer.set((Consumer) args[3]);
                  String ctag = consumerTagCounter.getAndIncrement() + "";
                  consumersLatch.countDown();
                  return ctag;
                }));

    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("isOpen", (proxy, method, args) -> true));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(10, SECONDS))
        .as("1 consumer should have been registered by now")
        .isTrue();
    assertThat(consumer.get()).isNotNull();
    sendMessagesToConsumer(nbMessages / 2, consumer.get());

    assertTrue(
        publishedLatch.await(10, SECONDS),
        () ->
            format(
                "Only %d / %d messages have been published",
                publishedLatch.getCount(), nbMessages));

    assertThat(testIsDone.get()).isFalse();

    waitAtMost(20, () -> testIsDone.get());
    assertThat(testDurationInMs).isGreaterThanOrEqualTo(SECONDS_5);
    assertThat(shutdownReasons)
        .hasSize(2) // time limit + publisher message limit, but no consumer message limit
        .containsKeys(
            MulticastSet.STOP_REASON_REACHED_TIME_LIMIT,
            Producer.STOP_REASON_PRODUCER_MESSAGE_LIMIT);
  }

  // -x 0 -y 1
  @Test
  public void consumerOnlyDoesNotStop() throws Exception {
    countsAndTimeLimit(0, 0, 0);
    params.setQueueNames(asList("queue"));
    params.setProducerCount(0);
    params.setConsumerCount(1);

    CountDownLatch consumersLatch = new CountDownLatch(1);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);

    List<Consumer> consumers = new CopyOnWriteArrayList<>();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  consumers.add((Consumer) args[3]);
                  consumersLatch.countDown();
                  return consumerTagCounter.getAndIncrement() + "";
                }));

    AtomicInteger closeCount = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> closeCount.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(20, SECONDS))
        .as("1 consumer should have been registered by now")
        .isTrue();
    assertThat(consumers).hasSize(1);

    assertThat(testIsDone.get()).isFalse();
    // only the configuration connection has been closed
    // so the test is still running in the background
    assertThat(closeCount.get()).isEqualTo(1);
    completionHandler.countDown("");
    waitAtMost(20, () -> testIsDone.get());
  }

  // -x 0 -y 1
  @Test
  public void producerOnlyDoesNotStop() throws Exception {
    countsAndTimeLimit(0, 0, 0);
    params.setProducerCount(1);
    params.setConsumerCount(0);
    params.setProducerRateLimit(100);

    int nbMessages = 100;
    CountDownLatch publishedLatch = new CountDownLatch(nbMessages);

    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedLatch.countDown();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    AtomicInteger closeCount = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> closeCount.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    assertTrue(
        publishedLatch.await(20, SECONDS),
        () ->
            format(
                "Only %d / %d messages have been published",
                publishedLatch.getCount(), nbMessages));
    assertThat(testIsDone.get()).isFalse();
    // only the configuration connection has been closed
    // so the test is still running in the background
    assertThat(closeCount.get()).isEqualTo(1);
    completionHandler.countDown("");
    waitAtMost(20, () -> testIsDone.get());
  }

  @Test
  public void publishingRateLimit() throws InterruptedException {
    countsAndTimeLimit(0, 0, 8);
    params.setProducerRateLimit(100);
    params.setProducerCount(3);

    AtomicInteger publishedMessageCount = new AtomicInteger();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedMessageCount.incrementAndGet();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    waitAtMost(30, () -> testIsDone.get());
    assertThat(publishedMessageCount.get())
        .isGreaterThan(0)
        .isLessThan(3 * 100 * 8 * 2); // not too many messages)
    assertThat(testDurationInMs).isGreaterThan(SECONDS_5);
    assertThat(shutdownReasons).hasSize(1).containsKey(MulticastSet.STOP_REASON_REACHED_TIME_LIMIT);
  }

  @Test
  public void producersThreadsNotCreatedWhenRateIsZero() throws Exception {
    countsAndTimeLimit(0, 0, 8);
    params.setProducerRateLimit(0);
    params.setProducerCount(10);

    AtomicInteger publishedMessageCount = new AtomicInteger();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedMessageCount.incrementAndGet();
                  return null;
                }));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    waitAtMost(30, () -> testIsDone.get());
    assertThat(publishedMessageCount).hasValue(0);
  }

  @Test
  public void publishingInterval() throws InterruptedException {
    int producerCount = 3;
    countsAndTimeLimit(0, 0, 6);
    params.setPublishingInterval(ofSeconds(1));
    params.setProducerCount(producerCount);

    AtomicInteger publishedMessageCount = new AtomicInteger();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedMessageCount.incrementAndGet();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    waitAtMost(10, () -> testIsDone.get());
    assertThat(publishedMessageCount.get())
        .isGreaterThanOrEqualTo(3 * 2) // 3 publishers should publish at least a couple of times
        .isLessThan(3 * 2 * 8); //  but they don't publish too much
    assertThat(testDurationInMs).isGreaterThan(SECONDS_5);
    assertThat(shutdownReasons).hasSize(1).containsKey(MulticastSet.STOP_REASON_REACHED_TIME_LIMIT);
    assertThat(agentStartedCount).hasValue(producerCount + 1);
  }

  @Test
  public void smallPublishingRateFallsBackToPublishingInterval() throws InterruptedException {
    countsAndTimeLimit(0, 0, 6);
    params.setProducerRateLimit(5);
    params.setProducerCount(3);

    AtomicInteger publishedMessageCount = new AtomicInteger();
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicPublish",
                (proxy, method, args) -> {
                  publishedMessageCount.incrementAndGet();
                  return null;
                }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    waitAtMost(10, () -> testIsDone.get());
    assertThat(publishedMessageCount.get())
        .isGreaterThanOrEqualTo(3 * 2 * 5) // 3 publishers should publish at least a couple of times
        .isLessThan(3 * 2 * 5 * 8); //  but they don't publish too much
    assertThat(testDurationInMs).isGreaterThan(SECONDS_5);
    assertThat(shutdownReasons).hasSize(1).containsKey(MulticastSet.STOP_REASON_REACHED_TIME_LIMIT);
  }

  @Test
  public void shutdownCalledIfShutdownTimeoutIsGreatherThanZero() throws Exception {
    countsAndTimeLimit(0, 0, 0);

    Channel channel =
        proxy(
            Channel.class,
            callback("basicPublish", (proxy, method, args) -> null),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    AtomicInteger connectionCloseCalls = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    completionHandler.countDown("");
    waitAtMost(10, () -> testIsDone.get());
    assertThat(connectionCloseCalls.get()).isEqualTo(4); // configuration connection is closed twice
  }

  @Test
  public void shutdownNotCalledIfShutdownTimeoutIsZeroOrLess() throws Exception {
    countsAndTimeLimit(0, 0, 0);
    params.setShutdownTimeout(-1);

    Channel channel =
        proxy(
            Channel.class,
            callback("basicPublish", (proxy, method, args) -> null),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    AtomicInteger connectionCloseCalls = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    completionHandler.countDown("");
    waitAtMost(10, () -> testIsDone.get());
    assertThat(connectionCloseCalls.get())
        .isEqualTo(1); // configuration connection is closed after configuration is done
  }

  @Test
  public void shutdownNotCompletedIfTimeoutIsReached() throws Exception {
    countsAndTimeLimit(0, 0, 0);
    params.setShutdownTimeout(1);

    Channel channel =
        proxy(
            Channel.class,
            callback("basicPublish", (proxy, method, args) -> null),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L));

    AtomicInteger connectionCloseCalls = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback(
                "close",
                (proxy, method, args) -> {
                  connectionCloseCalls.incrementAndGet();
                  // the first call is to close the configuration connection at the beginning of the
                  // run,
                  // so we simulate a timeout when closing a connection during the final shutdown
                  if (connectionCloseCalls.get() == 2) {
                    try {
                      Thread.sleep(5000);
                    } catch (InterruptedException e) {
                      // the interrupt flag is cleared after an InterruptedException
                      Thread.currentThread().interrupt();
                    }
                  }
                  return null;
                }));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

    run(multicastSet);

    waitForRunToStart();

    completionHandler.countDown("");
    waitAtMost(10, () -> testIsDone.get());
    assertThat(connectionCloseCalls.get()).isEqualTo(2);
  }

  @ParameterizedTest
  @MethodSource("pollingWithBasicGetArguments")
  public void pollingWithBasicGet(int consumersCount, int channelsCount) throws Exception {
    int messagesCount = consumersCount * channelsCount * 10;
    countsAndTimeLimit(0, messagesCount, 0);
    params.setConsumerCount(consumersCount);
    params.setConsumerChannelCount(channelsCount);
    params.setQueueNames(asList("queue"));
    params.setPolling(true);
    params.setPollingInterval(10);

    AtomicInteger countBasicGottenMessages = new AtomicInteger(0);
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicGet",
                (proxy, method, args) -> {
                  GetResponse response =
                      new GetResponse(new Envelope(1, false, "", ""), null, new byte[20], 1);
                  countBasicGottenMessages.incrementAndGet();
                  return response;
                }));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    waitAtMost(20, () -> testIsDone.get());
    assertThat(countBasicGottenMessages).hasValueGreaterThanOrEqualTo(messagesCount);
    assertThat(shutdownReasons)
        .hasSize(1)
        .containsKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT)
        .containsValue(consumersCount * channelsCount);
    assertThat(agentStartedCount).hasValue(consumersCount * channelsCount + 1);
  }

  // -x 0 -y 1 --polling --polling-interval 10
  @Test
  public void pollingOnlyDoesNotStop() throws Exception {
    countsAndTimeLimit(0, 0, 0);
    params.setQueueNames(asList("queue"));
    params.setProducerCount(0);
    params.setConsumerCount(1);
    params.setPolling(true);
    params.setPollingInterval(10);

    AtomicInteger countBasicGottenMessages = new AtomicInteger(0);
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicGet",
                (proxy, method, args) -> {
                  GetResponse response =
                      new GetResponse(new Envelope(1, false, "", ""), null, new byte[20], 1);
                  countBasicGottenMessages.incrementAndGet();
                  return response;
                }));

    AtomicInteger closeCount = new AtomicInteger(0);
    Connection connection =
        proxy(
            Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> closeCount.incrementAndGet()));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    waitAtMost(20, () -> countBasicGottenMessages.get() > 10);

    assertThat(testIsDone.get()).isFalse();
    // only the configuration connection has been closed
    // so the test is still running in the background
    assertThat(closeCount.get()).isEqualTo(1);
    completionHandler.countDown("");
    waitAtMost(20, () -> testIsDone.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "true"})
  public void ackNack(String nackParameter) throws Exception {
    boolean nack = Boolean.valueOf(nackParameter);
    int messagesCount = 100;
    countsAndTimeLimit(0, messagesCount, 0);
    params.setConsumerCount(1);
    params.setProducerCount(0);
    params.setQueueNames(asList("queue"));
    params.setNack(nack);

    CountDownLatch consumersLatch = new CountDownLatch(1);
    AtomicInteger consumerTagCounter = new AtomicInteger(0);
    List<Consumer> consumers = new CopyOnWriteArrayList<>();
    AtomicInteger acks = new AtomicInteger(0);
    AtomicInteger nacks = new AtomicInteger(0);
    Channel channel =
        proxy(
            Channel.class,
            callback(
                "basicConsume",
                (proxy, method, args) -> {
                  consumers.add((Consumer) args[3]);
                  consumersLatch.countDown();
                  return consumerTagCounter.getAndIncrement() + "";
                }),
            callback(
                "basicAck",
                (proxy, method, args) -> {
                  acks.incrementAndGet();
                  return null;
                }),
            callback(
                "basicNack",
                (proxy, method, args) -> {
                  nacks.incrementAndGet();
                  return null;
                }));

    Connection connection =
        proxy(Connection.class, callback("createChannel", (proxy, method, args) -> channel));

    MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
    run(multicastSet);

    waitForRunToStart();

    assertThat(consumersLatch.await(5, SECONDS))
        .as("consumer should have been registered by now")
        .isTrue();

    waitAtMost(20, () -> consumers.size() == 1);

    Collection<Future<?>> sendTasks = new ArrayList<>(consumers.size());
    for (Consumer consumer : consumers) {
      Collection<Future<?>> tasks = sendMessagesToConsumer(messagesCount, consumer);
      sendTasks.addAll(tasks);
    }

    for (Future<?> latch : sendTasks) {
      latch.get(10, SECONDS);
    }

    waitAtMost(20, () -> testIsDone.get());

    if (nack) {
      assertThat(acks).hasValue(0);
      assertThat(nacks).hasValue(messagesCount);
    } else {
      assertThat(acks).hasValue(messagesCount);
      assertThat(nacks).hasValue(0);
    }
    assertThat(shutdownReasons)
        .hasSize(1)
        .containsKey(com.rabbitmq.perf.Consumer.STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT)
        .containsValue(1);
  }

  private Collection<Future<?>> sendMessagesToConsumer(int messagesCount, Consumer consumer) {
    final Collection<Future<?>> tasks = new ArrayList<>(messagesCount);
    AtomicLong deliveryTag = new AtomicLong(0);
    IntStream.range(0, messagesCount)
        .forEach(
            i -> {
              Future<?> task =
                  executorService.submit(
                      () -> {
                        try {
                          consumer.handleDelivery(
                              "",
                              new Envelope(deliveryTag.incrementAndGet(), false, "", ""),
                              null,
                              new byte[20]);
                        } catch (Exception e) {
                          e.printStackTrace();
                        }
                      });
              tasks.add(task);
            });
    return tasks;
  }

  private Future<?> sendMessagesToConsumerSync(int messagesCount, Consumer consumer) {
    AtomicLong deliveryTag = new AtomicLong(0);
    return executorService.submit(
        () -> {
          IntStream.range(0, messagesCount)
              .forEach(
                  i -> {
                    try {
                      consumer.handleDelivery(
                          "",
                          new Envelope(deliveryTag.incrementAndGet(), false, "", ""),
                          null,
                          new byte[20]);
                    } catch (Exception e) {
                      e.printStackTrace();
                    }
                  });
        });
  }

  private void countsAndTimeLimit(int pmc, int cmc, int time) {
    params.setProducerMsgCount(pmc);
    params.setConsumerMsgCount(cmc);
    params.setTimeLimit(time);
  }

  private MulticastSet getMulticastSet(ConnectionFactory connectionFactory) {
    return getMulticastSet(
        connectionFactory, PerfTest.getCompletionHandler(params, shutdownReasons));
  }

  void waitForRunToStart() throws InterruptedException {
    assertTrue(runStartedLatch.await(20, SECONDS), "Run should have started by now");
  }

  private MulticastSet getMulticastSet(
      ConnectionFactory connectionFactory, MulticastSet.CompletionHandler completionHandler) {
    MulticastSet.CompletionHandler completionHandlerWrapper =
        new MulticastSet.CompletionHandler() {

          @Override
          public void waitForCompletion() throws InterruptedException {
            runStartedLatch.countDown();
            completionHandler.waitForCompletion();
          }

          @Override
          public void countDown(String reason) {
            completionHandler.countDown(reason);
          }
        };
    MulticastSet set =
        new MulticastSet(
            performanceMetrics,
            connectionFactory,
            params,
            singletonList("amqp://localhost"),
            completionHandlerWrapper);
    set.setThreadingHandler(th);
    this.completionHandler = completionHandlerWrapper;
    return set;
  }

  private void run(MulticastSet multicastSet) {
    executorService.submit(
        () -> {
          try {
            long start = System.nanoTime();
            multicastSet.run();
            testDurationInMs = Duration.ofNanos(System.nanoTime() - start);
            testIsDone.set(true);
          } catch (InterruptedException e) {
            // one of the tests stops the execution, no need to be noisy
            LOGGER.warn("Run has been interrupted");
          } catch (Exception e) {
            if (e.getCause() instanceof InterruptedException) {
              LOGGER.warn("Run has been interrupted");
            } else {
              LOGGER.warn("Error during run", e);
            }
          }
        });
  }
}
