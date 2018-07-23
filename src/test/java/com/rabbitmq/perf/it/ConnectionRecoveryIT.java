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
import com.rabbitmq.perf.MulticastParams;
import com.rabbitmq.perf.MulticastSet;
import com.rabbitmq.perf.Stats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertFalse;

/**
 *
 */
public class ConnectionRecoveryIT {

    static final List<String> URIS = Collections.singletonList("amqp://localhost");

    static final int RATE = 10;

    MulticastParams params;

    ExecutorService executorService;

    ConnectionFactory cf;

    AtomicBoolean testIsDone;

    AtomicInteger msgPublished, msgConsumed;

    Stats stats = new Stats(1000) {

        @Override
        protected void report(long now) {
            msgPublished.set(sendCountTotal);
            msgConsumed.set(recvCountTotal);
        }
    };

    static Stream<Arguments> configurationArguments() {
        return Stream.of(
            //            Arguments.of((Consumer<MulticastParams>) p -> {
            //            })
            Arguments.of((Consumer<MulticastParams>) p -> {
                p.setProducerCount(4);
                p.setConsumerCount(4);
                p.setQueuePattern("perf-test-" + System.currentTimeMillis() + "-%d");
                p.setQueueSequenceFrom(1);
                p.setQueueSequenceTo(4);
            }, 4)
        );
    }

    @BeforeEach
    public void init() {
        executorService = Executors.newCachedThreadPool();
        params = new MulticastParams();
        params.setProducerCount(1);
        params.setConsumerCount(1);
        params.setProducerRateLimit(RATE);
        cf = new ConnectionFactory();
        testIsDone = new AtomicBoolean(false);
        msgConsumed = new AtomicInteger(0);
        msgPublished = new AtomicInteger(0);
    }

    @AfterEach
    public void tearDown() {
        executorService.shutdownNow();
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldStopWhenConnectionRecoveryIsOffAndConnectionsAreKilled(Consumer<MulticastParams> configurer) throws Exception {
        cf.setAutomaticRecoveryEnabled(false);
        configurer.accept(params);
        int producerConsumerCount = params.getProducerCount();

        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount * RATE));
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilTrue(testIsDone);
    }

    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldStopWhenConnectionRecoveryIsOffAndConnectionsAreKilledAndUsingPublisingInterval(Consumer<MulticastParams> configurer) throws Exception {
        cf.setAutomaticRecoveryEnabled(false);
        configurer.accept(params);
        params.setPublishingInterval(1);
        int producerConsumerCount = params.getProducerCount();

        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount));
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilTrue(testIsDone);
    }

    @Disabled
    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldRecoverWhenConnectionsAreKilled(Consumer<MulticastParams> configurer) throws Exception {
        configurer.accept(params);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount * RATE));
        int messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(2 * messageCountBeforeClosing));
        assertFalse(testIsDone.get());
    }

    @Disabled
    @ParameterizedTest
    @MethodSource("configurationArguments")
    public void shouldRecoverWhenConnectionsAreKilledAndUsingPublisingInterval(Consumer<MulticastParams> configurer) throws Exception {
        params.setPublishingInterval(1);
        configurer.accept(params);
        int producerConsumerCount = params.getProducerCount();
        MulticastSet set = new MulticastSet(stats, cf, params, "", URIS, latchCompletionHandler(1));
        run(set);
        waitAtMost(10, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(3 * producerConsumerCount));
        int messageCountBeforeClosing = msgConsumed.get();
        closeAllConnections();
        waitAtMost(20, TimeUnit.SECONDS).untilAtomic(msgConsumed, greaterThanOrEqualTo(2 * messageCountBeforeClosing));
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
            } catch (InterruptedException e) {
                // one of the tests stops the execution, no need to be noisy
                throw new RuntimeException(e);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private MulticastSet.CompletionHandler latchCompletionHandler(int count) {
        return new LatchCompletionHandler(new CountDownLatch(count));
    }

    private static class LatchCompletionHandler implements MulticastSet.CompletionHandler {

        final CountDownLatch latch;

        private LatchCompletionHandler(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void waitForCompletion() throws InterruptedException {
            latch.await();
        }

        @Override
        public void countDown() {
            latch.countDown();
        }
    }
}
