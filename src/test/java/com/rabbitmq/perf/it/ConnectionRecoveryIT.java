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

package com.rabbitmq.perf.it;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.nio.NioParams;
import com.rabbitmq.perf.MulticastParams;
import com.rabbitmq.perf.MulticastSet;
import com.rabbitmq.perf.NamedThreadFactory;
import com.rabbitmq.perf.Stats;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertFalse;

/**
 *
 */
public class ConnectionRecoveryIT {

    static final Logger LOGGER = LoggerFactory.getLogger(ConnectionRecoveryIT.class);

    static final List<String> URIS = Collections.singletonList("amqp://localhost");

    static final int RATE = 10;

    MulticastParams params;

    ExecutorService executorService;

    ConnectionFactory cf;

    AtomicBoolean testIsDone;
    CountDownLatch testLatch;

    AtomicInteger msgPublished, msgConsumed;

    Stats stats = new Stats(1000, false, new CompositeMeterRegistry(), "") {

        @Override
        protected void report(long now) {
            msgPublished.set(sendCountTotal);
            msgConsumed.set(recvCountTotal);
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
            arguments.add(Arguments.of(configurer, namedConsumer("NIO", (Consumer<ConnectionFactory>) cf -> {
                cf.useNio();
            })));
        }

        return arguments.toArray(new Arguments[0]);
    }

    static List<Consumer<MulticastParams>> multicastParamsConfigurers() {
        return asList(
            namedConsumer("one server-named queue", empty()),
            namedConsumer("several queues", severalQueues()),
            namedConsumer("queue sequence", queueSequence()),
            namedConsumer("one server-named queue, exclusive", exclusive()),
            namedConsumer("queue sequence, exclusive", queueSequence().andThen(exclusive()))
        );
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
    public void init() {
        executorService = Executors.newCachedThreadPool();
        params = new MulticastParams();
        params.setProducerCount(1);
        params.setConsumerCount(1);
        params.setProducerRateLimit(RATE);
        cf = new ConnectionFactory();
        cf.setNetworkRecoveryInterval(2000);
        cf.setTopologyRecoveryEnabled(false);
        testIsDone = new AtomicBoolean(false);
        testLatch = new CountDownLatch(1);
        msgConsumed = new AtomicInteger(0);
        msgPublished = new AtomicInteger(0);
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
        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount * RATE));
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilTrue(testIsDone);
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldStopWhenConnectionRecoveryIsOffAndConnectionsAreKilledAndUsingPublisingInterval(Consumer<MulticastParams> configurer,
        Consumer<ConnectionFactory> cfConfigurer, TestInfo info)
        throws Exception {
        cf.setAutomaticRecoveryEnabled(false);
        configurer.accept(params);
        cfConfigurer.accept(cf);
        params.setPublishingInterval(1);
        int producerConsumerCount = params.getProducerCount();

        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount));
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilTrue(testIsDone);
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldRecoverWhenConnectionsAreKilled(Consumer<MulticastParams> configurer, Consumer<ConnectionFactory> cfConfigurer, TestInfo info)
        throws Exception {
        configurer.accept(params);
        cfConfigurer.accept(cf);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount * RATE));
        int messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(2 * messageCountBeforeClosing));
        assertFalse(testIsDone.get());
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldRecoverWhenConnectionsAreKilledAndUsingPublisingInterval(Consumer<MulticastParams> configurer, Consumer<ConnectionFactory> cfConfigurer,
        TestInfo info) throws Exception {
        params.setPublishingInterval(1);
        configurer.accept(params);
        cfConfigurer.accept(cf);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount));
        int messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(20, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(2 * messageCountBeforeClosing));
        assertFalse(testIsDone.get());
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
        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1, info));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount * RATE));
        int messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(2 * messageCountBeforeClosing));
        assertFalse(testIsDone.get());
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

    private MulticastSet.CompletionHandler latchCompletionHandler(int count, TestInfo info) {
        return new LatchCompletionHandler(new CountDownLatch(count), info);
    }

    private static class LatchCompletionHandler implements MulticastSet.CompletionHandler {

        final CountDownLatch latch;

        final String name;

        private LatchCompletionHandler(CountDownLatch latch, TestInfo info) {
            this.latch = latch;
            this.name = info.getDisplayName();
        }

        @Override
        public void waitForCompletion() {
            LOGGER.info("Waiting completion for test [{}]", name);
            try {
                latch.await();
            } catch (InterruptedException e) {
                LOGGER.info("Completion waiting has been interrupted for test [{}]", name);
            }
        }

        @Override
        public void countDown() {
            latch.countDown();
        }
    }
}
