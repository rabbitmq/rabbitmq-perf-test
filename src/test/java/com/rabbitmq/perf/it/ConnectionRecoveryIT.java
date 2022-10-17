// Copyright (c) 2018-2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.nio.NioParams;
import com.rabbitmq.perf.MulticastParams;
import com.rabbitmq.perf.MulticastSet;
import com.rabbitmq.perf.NamedThreadFactory;
import com.rabbitmq.perf.PerformanceMetrics;
import com.rabbitmq.perf.PerformanceMetricsAdapter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.rabbitmq.perf.TestUtils.threadFactory;
import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static com.rabbitmq.perf.it.Utils.latchCompletionHandler;
import static com.rabbitmq.perf.it.Utils.queueName;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;


/**
 *
 */
public class ConnectionRecoveryIT {

    static final Logger LOGGER = LoggerFactory.getLogger(ConnectionRecoveryIT.class);

    static final String URI = "amqp://localhost";

    static final List<String> URIS = Collections.singletonList(URI);

    static final int RATE = 11;

    MulticastParams params;

    ExecutorService executorService;

    ConnectionFactory cf;

    AtomicBoolean testIsDone;
    CountDownLatch testLatch;

    AtomicLong msgPublished, msgConsumed;

  PerformanceMetrics performanceMetrics = new PerformanceMetricsAdapter() {
    @Override
    public void published() {
      msgPublished.incrementAndGet();
    }

    @Override
    public void received(long latency) {
      msgConsumed.incrementAndGet();
    }

    @Override
    public Duration interval() {
      return Duration.ofSeconds(1);
    }
  };

    static Stream<Arguments> configurationArguments() {
        return Stream.of(blockingIoAndNio(multicastParamsConfigurers()));
    }

    static Arguments[] blockingIoAndNio(List<Consumer<MulticastParams>> multicastParamsConfigurers) {
        List<Arguments> arguments = new ArrayList<>();
        for (Consumer<MulticastParams> configurer : multicastParamsConfigurers) {
            arguments.add(Arguments.of(configurer, namedConsumer("blocking IO", (Consumer<ConnectionFactory>) cf -> {
            })));
            arguments.add(Arguments.of(configurer, namedConsumer("NIO", (Consumer<ConnectionFactory>) cf -> cf.useNio())));
        }

        return arguments.toArray(new Arguments[0]);
    }

    static List<Consumer<MulticastParams>> multicastParamsConfigurers() {
        List<Consumer<MulticastParams>> parameters = new ArrayList<>();
        for (Consumer<MulticastParams> queuesVariation : queuesVariations()) {
            parameters.add(queuesVariation);
            parameters.add(namedConsumer("polling - " + queuesVariation, params -> {
                queuesVariation.accept(params);
                params.setPolling(true);
                params.setPollingInterval(10);
            }));
        }
        return parameters;
    }

    static List<Consumer<MulticastParams>> queuesVariations() {
        return asList(
                namedConsumer("one server-named queue", empty()),
                namedConsumer("several queues", severalQueues()),
                namedConsumer("queue sequence", queueSequence()),
                namedConsumer("one server-named queue, exclusive", exclusive()),
                namedConsumer("queue sequence, exclusive", queueSequence().andThen(exclusive()))
        );
    }

    static Stream<Arguments> configurationArgumentsForSeveralUris() {
        return Stream.of(
                namedConsumer("one server-named queue", empty()),
                namedConsumer("several queues", severalQueues()),
                namedConsumer("queue sequence", queueSequence()))
                .map(configurer -> Arguments.of(configurer));
    }

    static Consumer<MulticastParams> empty() {
        return p -> {
        };
    }

    static Consumer<MulticastParams> severalQueues() {
        return p -> {
            String suffix = valueOf(System.currentTimeMillis());
            p.setQueueNames(range(1, 5).mapToObj(i -> format("perf-test-%s-%d", suffix, i)).collect(toList()));
        };
    }

    static Consumer<MulticastParams> exclusive() {
        return p -> p.setExclusive(true);
    }

    static Consumer<MulticastParams> queueSequence() {
        return p -> {
            p.setProducerCount(4);
            p.setConsumerCount(4);
            p.setQueuePattern("perf-test-sequence-" + System.currentTimeMillis() + "-%d");
            p.setQueueSequenceFrom(1);
            p.setQueueSequenceTo(4);
        };
    }

    static <V> Consumer<V> namedConsumer(String name, Consumer<V> consumer) {
        return new Consumer<V>() {

            @Override
            public void accept(V obj) {
                consumer.accept(obj);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    @BeforeEach
    public void init(TestInfo info) {
        executorService = Executors.newCachedThreadPool(threadFactory(info));
        params = new MulticastParams();
        params.setProducerCount(1);
        params.setConsumerCount(1);
        params.setProducerRateLimit(RATE);
        cf = new ConnectionFactory();
        cf.setNetworkRecoveryInterval(2000);
        cf.setTopologyRecoveryEnabled(false);
        testIsDone = new AtomicBoolean(false);
        testLatch = new CountDownLatch(1);
        msgConsumed = new AtomicLong(0);
        msgPublished = new AtomicLong(0);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        LOGGER.info("Shutting down test executor");
        executorService.shutdownNow();
        if (!testLatch.await(10, TimeUnit.SECONDS)) {
            LOGGER.warn("PerfTest run didn't shut down properly, run logs may show up during other tests");
        }
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldStopWhenConnectionRecoveryIsOffAndConnectionsAreKilled(Consumer<MulticastParams> configurer, Consumer<ConnectionFactory> cfConfigurer,
                                                                             TestInfo info) throws Exception {
        cf.setAutomaticRecoveryEnabled(false);
        configurer.accept(params);
        cfConfigurer.accept(cf);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
        closeAllConnections();
        waitAtMost(10, () -> testIsDone.get());
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldStopWhenConnectionRecoveryIsOffAndConnectionsAreKilledAndUsingPublishingInterval(Consumer<MulticastParams> configurer,
                                                                                                       Consumer<ConnectionFactory> cfConfigurer, TestInfo info)
            throws Exception {
        cf.setAutomaticRecoveryEnabled(false);
        configurer.accept(params);
        cfConfigurer.accept(cf);
        params.setPublishingInterval(Duration.ofSeconds(1));
        int producerConsumerCount = params.getProducerCount();

        MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount);
        closeAllConnections();
        waitAtMost(10, () -> testIsDone.get());
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldRecoverWhenConnectionsAreKilled(Consumer<MulticastParams> configurer, Consumer<ConnectionFactory> cfConfigurer, TestInfo info)
            throws Exception {
        configurer.accept(params);
        cfConfigurer.accept(cf);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
        long messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(10, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
        assertThat(testIsDone.get()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldRecoverWhenConnectionsAreKilledAndUsingPublishingInterval(Consumer<MulticastParams> configurer, Consumer<ConnectionFactory> cfConfigurer,
                                                                                TestInfo info) throws Exception {
        params.setPublishingInterval(Duration.ofSeconds(1));
        configurer.accept(params);
        cfConfigurer.accept(cf);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount);
        long messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(20, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
        assertThat(testIsDone.get()).isFalse();
    }

    @Test
    public void shouldRecoverWithNio(TestInfo info) throws Exception {
        params.setQueueNames(Arrays.asList("one", "two", "three"));
        params.setProducerCount(10);
        params.setConsumerCount(10);
        cf.useNio();
        cf.setNioParams(new NioParams()
                .setNbIoThreads(10)
                // see PerfTest#configureNioIfRequested
                .setNioExecutor(new ThreadPoolExecutor(
                        10, params.getProducerCount() + params.getConsumerCount() + 5,
                        30L, TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        new NamedThreadFactory("perf-test-nio-")
                ))
        );
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
        long messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(10, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
        assertThat(testIsDone.get()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("configurationArgumentsForSeveralUris")
    public void shouldRecoverWhenConnectionsAreKilledAndUsingSeveralUris(Consumer<MulticastParams> configurer, TestInfo info)
            throws Exception {
        configurer.accept(params);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(
                performanceMetrics, cf, params, "",
                IntStream.range(0, 3).mapToObj(ignored -> URI).collect(toList()),
                latchCompletionHandler(1, info)
        );
        run(set);
        waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
        long messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(10, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
        assertThat(testIsDone.get()).isFalse();
    }

    @ValueSource(booleans = {false, true})
    @ParameterizedTest
    public void shouldRecoverWithPreDeclared(boolean polling, TestInfo info) throws Exception {
        int queueCount = 5;
        String queuePattern = "perf-test-" + info.getTestMethod().get().getName() + "-%d";
        ConnectionFactory connectionFactory = new ConnectionFactory();
        try (Connection c = connectionFactory.newConnection()) {
            Channel ch = c.createChannel();
            for (int i = 1; i <= queueCount; i++) {
                ch.queueDeclare(String.format(queuePattern, i), false, false, false, null);
            }
        }

        params.setQueuePattern(queuePattern);
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(queueCount);
        params.setConsumerCount(queueCount);
        params.setProducerCount(queueCount);
        params.setPredeclared(true);
        params.setPolling(polling);

        try {
            int producerConsumerCount = params.getProducerCount();
            MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
            run(set);
            waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
            long messageCountBeforeClosing = msgConsumed.get();
            closeAllConnections();
            waitAtMost(10, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
            assertThat(testIsDone.get()).isFalse();
        } finally {
            try (Connection c = connectionFactory.newConnection()) {
                Channel ch = c.createChannel();
                for (int i = 1; i <= queueCount; i++) {
                    ch.queueDelete(String.format(queuePattern, i));
                }
            }
        }

    }

    @Test
    void durableServerNamedQueueShouldBeReusedIfStillThere(TestInfo info) throws Exception {
        params.setFlags(Arrays.asList("persistent"));
        params.setAutoDelete(false);
        params.setExclusive(false);
        params.setQueueNames(Collections.emptyList());

        List<String> queuesBeforeTest = Host.listQueues();
        MulticastSet.CompletionHandler completionHandler = latchCompletionHandler(1, info);
        String createdQueue = null;
        try {
            int producerConsumerCount = params.getProducerCount();
            MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, completionHandler);
            run(set);
            waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
            List<String> queuesDuringTest = Host.listQueues();
            assertThat(queuesDuringTest).hasSize(queuesBeforeTest.size() + 1);
            queuesDuringTest.removeAll(queuesBeforeTest);
            assertThat(queuesDuringTest).hasSize(1);
            createdQueue = queuesDuringTest.get(0);
            long messageCountBeforeClosing = msgConsumed.get();
            closeAllConnections();
            waitAtMost(10, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
            assertThat(Host.listQueues()).hasSize(queuesBeforeTest.size() + 1);
            completionHandler.countDown("stopped in test");
            waitAtMost(10, () -> testIsDone.get());
        } finally {
            if (createdQueue != null) {
                try (Connection c = new ConnectionFactory().newConnection()) {
                    c.createChannel().queueDelete(createdQueue);
                }
            }
        }
    }

    @Test
    void recreateBindingEvenOnPreDeclaredDurableQueue(TestInfo info) throws Exception {
        String queue = queueName(info);
        ConnectionFactory connectionFactory = new ConnectionFactory();
        try (Connection c = connectionFactory.newConnection()) {
            Channel ch = c.createChannel();
            ch.queueDeclare(queue, true, false, false, null);
            ch.exchangeDelete("direct");
        }

        params.setQueueNames(Collections.singletonList(queue));
        params.setPredeclared(true);

        int producerConsumerCount = params.getProducerCount();
        try {
            MulticastSet set = new MulticastSet(performanceMetrics, cf, params, "", URIS, latchCompletionHandler(1, info));
            run(set);
            waitAtMost(10, () -> msgConsumed.get() >= 3 * producerConsumerCount * RATE);
            long messageCountBeforeClosing = msgConsumed.get();
            Host.stopBrokerApp();
            Thread.sleep(2000);
            Host.startBrokerApp();
            waitAtMost(30, () -> msgConsumed.get() >= 2 * messageCountBeforeClosing);
            assertThat(testIsDone.get()).isFalse();
        } finally {
            try (Connection c = connectionFactory.newConnection()) {
                Channel ch = c.createChannel();
                ch.queueDelete(queue);
            }
        }
    }

    void closeAllConnections() throws IOException {
        List<Host.ConnectionInfo> connectionInfos = new ArrayList<>(Host.listConnections());
        Collections.shuffle(connectionInfos);
        Host.closeAllConnections(connectionInfos);
    }

    private void run(MulticastSet multicastSet) {
        executorService.submit(() -> {
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
