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
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.rabbitmq.perf.MockUtils.callback;
import static com.rabbitmq.perf.MockUtils.proxy;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class MessageSizeTest {

    MulticastParams params;

    static Stream<Arguments> messageSizeArguments() {
        return Stream.of(
                Arguments.of(0, 12),
                Arguments.of(4000, 4000)
        );
    }

    @BeforeEach
    public void init() {
        params = new MulticastParams();
    }

    // -x 1 -y 2 -u "throughput-test-4" --id "test 4" -s 4000
    @ParameterizedTest
    @MethodSource("messageSizeArguments")
    public void messageIsPublishedWithExpectedMessageSize(int requestedSize, int actualSize, TestInfo testInfo)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger publishCount = new AtomicInteger(0);
        AtomicReference<byte[]> body = new AtomicReference<>();
        Channel channel = proxy(Channel.class,
                callback("queueDeclare", (proxy, method, args) -> new AMQImpl.Queue.DeclareOk("", 0, 0)),
                callback("basicPublish", (proxy, method, args) -> {
                    body.set((byte[]) args[5]);
                    publishCount.incrementAndGet();
                    latch.countDown();
                    return null;
                })
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> null)
        );

        params.setMinMsgSize(requestedSize);
        params.setConsumerCount(0);
        params.setProducerCount(1);
        MulticastSet set = getMulticastSet(MockUtils.connectionFactoryThatReturns(connection), latch, testInfo);

        set.run();

        assertThat(publishCount.get(), greaterThanOrEqualTo(1));
        assertThat(body.get().length, is(actualSize));
    }

    private MulticastSet getMulticastSet(ConnectionFactory cf, CountDownLatch completionLatch, TestInfo info) {
        MulticastSet set = new MulticastSet(
                new NoOpStats(), cf, params, singletonList("amqp://localhost"), new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() throws InterruptedException {
                completionLatch.await(10, TimeUnit.SECONDS);
            }

            @Override
            public void countDown() {
            }
        }
        );

        set.setThreadingHandler(new MulticastSet.DefaultThreadingHandler(TestUtils.name(info)));
        return set;
    }

}
