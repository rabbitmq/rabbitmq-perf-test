// Copyright (c) 2018-Present Pivotal Software, Inc.  All rights reserved.
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

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.rabbitmq.perf.MockUtils.callback;
import static com.rabbitmq.perf.MockUtils.connectionFactoryThatReturns;
import static com.rabbitmq.perf.MockUtils.proxy;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class MessageCountTimeLimitAndPublishingIntervalRateTest {

    @Mock
    ConnectionFactory cf;
    @Mock
    Connection c;
    @Mock
    Channel ch;

    Stats stats = new Stats(1000) {

        @Override
        protected void report(long now) {
        }
    };

    MulticastParams params;

    ExecutorService executorService;

    MulticastSet.ThreadingHandler th;

    AtomicBoolean testIsDone;

    volatile long testDurationInMs;

    static Stream<Arguments> producerCountArguments() {
        return Stream.of(
            Arguments.of(1, 1),
            Arguments.of(3, 1),
            Arguments.of(1, 3),
            Arguments.of(2, 3)
        );
    }

    static Stream<Arguments> consumerCountArguments() {
        return Stream.of(
            Arguments.of(1, 1),
            Arguments.of(3, 1),
            Arguments.of(1, 3),
            Arguments.of(2, 3)
        );
    }

    @BeforeAll
    public static void beforeAllTests() {
        Awaitility.setDefaultPollInterval(200, TimeUnit.MILLISECONDS);
    }

    @AfterAll
    public static void afterAllTests() {
        Awaitility.reset();
    }

    @BeforeEach
    public void init() throws Exception {
        initMocks(this);

        when(cf.newConnection(anyString())).thenReturn(c);
        when(c.createChannel()).thenReturn(ch);

        testIsDone = new AtomicBoolean(false);
        executorService = Executors.newCachedThreadPool();
        th = new MulticastSet.DefaultThreadingHandler();
        testDurationInMs = -1;
        params = new MulticastParams();
        params.setPredeclared(true);
    }

    @AfterEach
    public void tearDown() {
        executorService.shutdownNow();
        th.shutdown();
    }

    @Test
    public void noLimit() throws Exception {
        countsAndTimeLimit(0, 0, 0);

        int nbMessages = 10;
        CountDownLatch publishedLatch = new CountDownLatch(nbMessages);
        Channel channel = proxy(Channel.class,
            callback("basicPublish", (proxy, method, args) -> {
                publishedLatch.countDown();
                return null;
            })
        );

        AtomicInteger connectionCloseCalls = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        assertTrue(
            publishedLatch.await(10, TimeUnit.SECONDS),
            () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), nbMessages)
        );

        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        assertThat(connectionCloseCalls.get(), is(1));
    }

    // --time 5
    @Test
    public void timeLimit() {
        countsAndTimeLimit(0, 0, 3);
        MulticastSet multicastSet = getMulticastSet();

        run(multicastSet);

        waitAtMost(15, TimeUnit.SECONDS).untilTrue(testIsDone);
        assertThat(testDurationInMs, greaterThanOrEqualTo(3000L));
    }

    // -y 1 --pmessages 10 -x n -X m
    @ParameterizedTest
    @MethodSource("producerCountArguments")
    public void producerCount(int producersCount, int channelsCount) throws Exception {
        int messagesCount = producersCount * channelsCount;
        countsAndTimeLimit(messagesCount, 0, 0);
        params.setProducerCount(producersCount);
        params.setProducerChannelCount(channelsCount);

        int messagesTotal = producersCount * channelsCount * messagesCount;
        CountDownLatch publishedLatch = new CountDownLatch(messagesTotal);
        Channel channel = proxy(Channel.class,
            callback("basicPublish", (proxy, method, args) -> {
                publishedLatch.countDown();
                return null;
            })
        );

        Connection connection = proxy(Connection.class,
            callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        assertTrue(
            publishedLatch.await(60, TimeUnit.SECONDS),
            () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), messagesTotal)
        );
        waitAtMost(20, TimeUnit.SECONDS).untilTrue(testIsDone);
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
        ArgumentCaptor<Consumer> consumerArgumentCaptor = ArgumentCaptor.forClass(Consumer.class);
        doAnswer(invocation -> {
            consumersLatch.countDown();
            return consumerTagCounter.getAndIncrement() + "";
        }).when(ch).basicConsume(anyString(), anyBoolean(), consumerArgumentCaptor.capture());

        MulticastSet multicastSet = getMulticastSet();
        run(multicastSet);

        assertThat(consumersCount * channelsCount + " consumer(s) should have been registered by now",
            consumersLatch.await(5, TimeUnit.SECONDS), is(true));

        waitAtMost(20, TimeUnit.SECONDS).until(() -> consumerArgumentCaptor.getAllValues(), hasSize(consumersCount * channelsCount));

        for (Consumer consumer : consumerArgumentCaptor.getAllValues()) {
            sendMessagesToConsumer(messagesCount, consumer);
        }

        waitAtMost(20, TimeUnit.SECONDS).untilTrue(testIsDone);
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

        Channel channel = proxy(Channel.class,
            callback("basicPublish", (proxy, method, args) -> {
                publishedLatch.countDown();
                return null;
            }),
            callback("basicConsume", (proxy, method, args) -> {
                consumer.set((Consumer) args[2]);
                String ctag = consumerTagCounter.getAndIncrement() + "";
                consumersLatch.countDown();
                return ctag;
            })
        );

        Connection connection = proxy(Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("isOpen", (proxy, method, args) -> true));

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        assertThat("1 consumer should have been registered by now",
            consumersLatch.await(10, TimeUnit.SECONDS), is(true));
        assertThat(consumer.get(), notNullValue());
        sendMessagesToConsumer(nbMessages / 2, consumer.get());

        assertTrue(
            publishedLatch.await(10, TimeUnit.SECONDS),
            () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), nbMessages)
        );

        assertThat(testIsDone.get(), is(false));

        waitAtMost(20, TimeUnit.SECONDS).untilTrue(testIsDone);
        assertThat(testDurationInMs, greaterThanOrEqualTo(5000L));
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
        ArgumentCaptor<Consumer> consumerArgumentCaptor = ArgumentCaptor.forClass(Consumer.class);
        doAnswer(invocation -> {
            consumersLatch.countDown();
            return consumerTagCounter.getAndIncrement() + "";
        }).when(ch).basicConsume(anyString(), anyBoolean(), consumerArgumentCaptor.capture());

        MulticastSet multicastSet = getMulticastSet();
        run(multicastSet);

        assertThat("1 consumer should have been registered by now",
            consumersLatch.await(20, TimeUnit.SECONDS), is(true));
        assertThat(consumerArgumentCaptor.getValue(), notNullValue());

        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        verify(c, times(1)).close();
    }

    // -x 0 -y 1
    @Test
    public void producerOnlyDoesNotStop() throws Exception {
        countsAndTimeLimit(0, 0, 0);
        params.setProducerCount(1);
        params.setConsumerCount(0);

        int nbMessages = 100;
        CountDownLatch publishedLatch = new CountDownLatch(nbMessages);
        doAnswer(invocation -> {
            publishedLatch.countDown();
            return null;
        }).when(ch).basicPublish(anyString(), anyString(),
            anyBoolean(), eq(false),
            any(), any());

        MulticastSet multicastSet = getMulticastSet();
        run(multicastSet);

        assertTrue(
            publishedLatch.await(20, TimeUnit.SECONDS),
            () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), nbMessages)
        );
        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        verify(c, times(1)).close();
    }

    @Test
    public void publishingRateLimit() throws Exception {
        countsAndTimeLimit(0, 0, 8);
        params.setProducerRateLimit(10);
        params.setProducerCount(3);

        AtomicInteger publishedMessageCount = new AtomicInteger();
        doAnswer(invocation -> {
            publishedMessageCount.incrementAndGet();
            return null;
        }).when(ch).basicPublish(anyString(), anyString(),
            anyBoolean(), eq(false),
            any(), any());

        MulticastSet multicastSet = getMulticastSet();
        run(multicastSet);

        waitAtMost(15, TimeUnit.SECONDS).untilTrue(testIsDone);
        assertThat(publishedMessageCount.get(), allOf(
            greaterThan(3 * 10 * 3), // 3 producers at 10 m/s for about 2 seconds at least
            lessThan(3 * 10 * 8 * 2) // not too many messages though
        ));
        assertThat(testDurationInMs, greaterThan(5000L));
    }

    @Test
    public void publishingInterval() throws Exception {
        countsAndTimeLimit(0, 0, 6);
        params.setPublishingInterval(2);
        params.setProducerCount(3);

        AtomicInteger publishedMessageCount = new AtomicInteger();
        Channel channel = proxy(Channel.class,
            callback("basicPublish", (proxy, method, args) -> {
                publishedMessageCount.incrementAndGet();
                return null;
            }),
            callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        Connection connection = proxy(Connection.class,
            callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitAtMost(10, TimeUnit.SECONDS).untilTrue(testIsDone);
        assertThat(publishedMessageCount.get(), allOf(
            greaterThanOrEqualTo(3 * 2),  // 3 publishers should publish at least a couple of times
            lessThan(3 * 2 * 4) //  but they don't publish
        ));
        assertThat(testDurationInMs, greaterThan(5000L));
    }

    private void sendMessagesToConsumer(int messagesCount, Consumer consumer) {
        IntStream.range(0, messagesCount).forEach(i -> {
            executorService.submit(() -> {
                try {
                    consumer.handleDelivery(
                        "",
                        new Envelope(1, false, "", ""),
                        null,
                        new byte[20]
                    );
                } catch (IOException e) {
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

    private MulticastSet getMulticastSet() {
        return getMulticastSet(cf);
    }

    private MulticastSet getMulticastSet(ConnectionFactory connectionFactory) {
        MulticastSet set = new MulticastSet(
            stats, connectionFactory, params, singletonList("amqp://localhost"),
            PerfTest.getCompletionHandler(params)
        );
        set.setThreadingHandler(th);
        return set;
    }

    private void run(MulticastSet multicastSet) {
        executorService.submit(() -> {
            try {
                long start = System.nanoTime();
                multicastSet.run();
                testDurationInMs = (System.nanoTime() - start) / 1_000_000;
                testIsDone.set(true);
            } catch (InterruptedException e) {
                // one of the tests stops the execution, no need to be noisy
                throw new RuntimeException(e);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
