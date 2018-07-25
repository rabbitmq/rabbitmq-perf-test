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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.rabbitmq.perf.MockUtils.callback;
import static com.rabbitmq.perf.MockUtils.connectionFactoryThatReturns;
import static com.rabbitmq.perf.MockUtils.proxy;
import static java.lang.Boolean.valueOf;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.IntStream.range;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TopologyTest {

    @Mock
    ConnectionFactory cf;
    @Mock
    Connection c;
    @Mock
    Channel ch;
    @Mock
    Stats stats;

    MulticastParams params;

    @Captor
    ArgumentCaptor<String> queueNameCaptor;
    @Captor
    ArgumentCaptor<String> routingKeyCaptor;
    @Captor
    ArgumentCaptor<String> consumerQueue;
    @Captor
    private ArgumentCaptor<byte[]> bodyCaptor;

    static Stream<Arguments> messageSizeArguments() {
        return Stream.of(
            Arguments.of(0, 12),
            Arguments.of(4000, 4000)
        );
    }

    static Stream<Arguments> reuseConnectionForExclusiveQueuesWhenMoreConsumersThanQueuesArguments() {
        return Stream.of(
            // exclusive, sequence of queues, queues, consumers, expected number of non-used connections, message
            // first with hard-coded queue names mechanism
            Arguments.of(false, false, 2, 4, 0, "Non-exclusive queues, a connection is open for each consumer"),
            Arguments.of(true, false, 2, 2, 1, "Exclusive queues with same number of queues and consumers, one connection each"),
            Arguments.of(true, false, 5, 7, 6, "Exclusive queues with more consumers than queues, 1 shared connection for all consumers, "
                + "as in this mode they listen on all queues each"),
            // then with sequence of queues mechanism
            Arguments.of(false, true, 5, 10, 0, "Non-exclusive queues, a connection is open for each consumer"),
            Arguments.of(true, true, 5, 5, 0, "Exclusive queues with same number of queues and consumers, one connection each"),
            Arguments.of(true, true, 5, 7, 2, "Exclusive queues with more consumers than queues, connections are re-used across consumers")
        );
    }

    @BeforeEach
    public void init() throws Exception {
        initMocks(this);

        when(cf.newConnection(anyString())).thenReturn(c);
        when(c.createChannel()).thenReturn(ch);

        params = new MulticastParams();
    }

    @Test
    public void defaultParameters()
        throws Exception {
        when(ch.queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk("", 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1 + 1)).newConnection(anyString()); // consumers, producers, configuration (not used)
        verify(c, times(1 + 1 + 1)).createChannel(); // queue configuration, consumer, producer
        verify(ch, times(1))
            .queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(anyString(), eq("direct"), anyString());
    }

    @Test
    public void nProducersAndConsumer()
        throws Exception {
        params.setConsumerCount(10);
        params.setProducerCount(10);

        when(ch.queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk("", 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(10 + 10 + 1)).newConnection(anyString()); // consumers, producers, configuration (not used)
        verify(c, times(10 + 10 + 10)).createChannel(); // queue configuration, consumer, producer
        verify(ch, times(10))
            .queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(10))
            .queueBind(anyString(), eq("direct"), anyString());
    }

    // -x 1 -y 2 -u "throughput-test-1" -a --id "test 1"
    @Test
    public void producers1Consumers2QueueSpecified() throws Exception {
        String queue = "throughput-test-1";
        params.setConsumerCount(2);
        params.setProducerCount(1);
        params.setQueueNames(singletonList(queue));

        when(ch.queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk(queue, 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(2 + 1 + 1)).newConnection(anyString()); // consumers, producers, configuration (not used)
        verify(c, times(2 + 2 + 1)).createChannel(); // queue configuration, consumer, producer
        verify(ch, times(2))
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(2))
            .queueBind(eq(queue), eq("direct"), anyString());
    }

    // -x 2 -y 4 -u "throughput-test-2" -a --id "test 2"
    @Test
    public void producers2Consumers4QueueSpecified() throws Exception {
        String queue = "throughput-test-2";
        params.setConsumerCount(4);
        params.setProducerCount(2);
        params.setQueueNames(singletonList(queue));

        when(ch.queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk(queue, 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(4 + 2 + 1)).newConnection(anyString()); // consumers, producers, configuration (not used)
        verify(c, times(4 + 4 + 2)).createChannel(); // queue configuration, consumer, producer
        verify(ch, times(4))
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(4))
            .queueBind(eq(queue), eq("direct"), anyString());
    }

    // -x 1 -y 2 -u "throughput-test-4" --id "test 4" -s 4000
    @ParameterizedTest
    @MethodSource("messageSizeArguments")
    public void messageIsPublishedWithExpectedMessageSize(int requestedSize, int actualSize)
        throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        when(ch.queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk("", 0, 0));
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(ch).basicPublish(anyString(), anyString(),
            anyBoolean(), eq(false),
            any(), bodyCaptor.capture());

        params.setMinMsgSize(requestedSize);
        MulticastSet set = getMulticastSet(new InterruptThreadHandler(latch));

        set.run();

        assertThat("basicPublish should have been called", latch.await(1, TimeUnit.SECONDS), is(true));

        verify(ch, atLeastOnce())
            .basicPublish(anyString(), anyString(),
                anyBoolean(), eq(false),
                any(), any(byte[].class)
            );

        assertThat(bodyCaptor.getValue().length, is(actualSize));
    }

    // -x 1 -y 2 -u "throughput-test-7" --id "test-7" -f persistent --multi-ack-every 200 -q 500
    @Test
    public void qosIsSetOnTheChannel() throws Exception {
        when(ch.queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk("", 0, 0));

        params.setChannelPrefetch(500);

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1 + 1)).newConnection(anyString()); // consumers, producers, configuration (not used)
        verify(c, times(1 + 1 + 1)).createChannel(); // queue configuration, consumer, producer
        verify(ch, times(1))
            .queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(anyString(), eq("direct"), anyString());
        verify(ch, times(1))
            .basicQos(500, true);
    }

    // -y 0 -p -u "throughput-test-14" -s 1000 -C 1000000 --id "test-14" -f persistent
    @Test
    public void prePopulateQueuePreDeclaredProducerOnlyRun() throws Exception {
        String queue = "throughput-test-14";
        params.setConsumerCount(0);
        params.setProducerCount(1);
        params.setQueueNames(singletonList(queue));
        params.setPredeclared(true);

        when(ch.queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk(queue, 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1)).newConnection(anyString()); // configuration and producer
        verify(c, atLeast(1 + 1)).createChannel(); // configuration, producer, and checks
        verify(ch, never()) // shouldn't be called, pre-declared is true
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(eq(queue), eq("direct"), anyString());
    }

    // -x0 -y10 -p -u "throughput-test-14" --id "test-15"
    @Test
    public void preDeclaredOnlyConsumers() throws Exception {
        String queue = "throughput-test-14";
        params.setConsumerCount(10);
        params.setProducerCount(0);
        params.setQueueNames(singletonList(queue));
        params.setPredeclared(true);

        when(ch.queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk(queue, 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(10 + 1)).newConnection(anyString()); // consumers, configuration (not used)
        verify(c, atLeast(10 + 10)).createChannel(); // configuration, consumers, and checks
        verify(ch, never()) // shouldn't be called, pre-declared is true
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(10))
            .queueBind(eq(queue), eq("direct"), anyString());
    }

    // --producers 1 --consumers 0 --predeclared --routing-key rk --queue q --use-millis
    @Test
    public void differentMachinesProducer() throws Exception {
        String queue = "q";
        String routingKey = "rk";
        params.setConsumerCount(0);
        params.setProducerCount(1);
        params.setQueueNames(singletonList(queue));
        params.setRoutingKey(routingKey);
        params.setPredeclared(true);

        when(ch.queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk(queue, 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1)).newConnection(anyString()); // configuration, producer
        verify(c, atLeast(1 + 1)).createChannel(); // configuration, producer, checks
        verify(ch, never()) // shouldn't be called, pre-declared is true
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(eq(queue), eq("direct"), eq(routingKey));
    }

    // --producers 0 --consumers 1 --predeclared --routing-key rk --queue q --use-millis
    @Test
    public void differentMachinesConsumer() throws Exception {
        String queue = "q";
        String routingKey = "rk";
        params.setConsumerCount(1);
        params.setProducerCount(0);
        params.setQueueNames(singletonList(queue));
        params.setRoutingKey(routingKey);
        params.setPredeclared(true);

        when(ch.queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk(queue, 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1)).newConnection(anyString()); // consumer, configuration (not used)
        verify(c, atLeast(1 + 1)).createChannel(); // configuration, consumer, checks
        verify(ch, never()) // shouldn't be called, pre-declared is true
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(eq(queue), eq("direct"), eq(routingKey));
    }

    @ParameterizedTest
    @ValueSource(strings = { "true", "false" })
    public void exclusiveQueue(String exclusive) throws Exception {
        params.setExclusive(valueOf(exclusive));
        when(ch.queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk("", 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1 + 1)).newConnection(anyString()); // consumers, producers, configuration (not used)
        verify(c, times(1 + 1 + 1)).createChannel(); // queue configuration, consumer, producer
        verify(ch, times(1))
            .queueDeclare(eq(""), anyBoolean(), eq(valueOf(exclusive)), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(anyString(), eq("direct"), anyString());
    }

    @Test
    public void connectionIsNotClosedWithExclusiveQueuesAndSeveralChannels() throws Exception {
        params.setExclusive(true);
        params.setConsumerChannelCount(10);

        Channel channel = proxy(Channel.class,
            callback("queueDeclare", (proxy, method, args) -> new AMQImpl.Queue.DeclareOk(args[0].toString(), 0, 0))
        );

        AtomicInteger closedConnections = new AtomicInteger(0);
        Supplier<Connection> connectionSupplier = () -> proxy(Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("isOpen", (proxy, method, args) -> true),
            callback("close", (proxy, method, args) -> {
                closedConnections.incrementAndGet();
                return null;
            }));

        ConnectionFactory connectionFactory = connectionFactoryThatReturns(connectionSupplier);

        MulticastSet set = getMulticastSet(connectionFactory);

        set.run();

        assertThat("Consumer connection shouldn't be closed several times",
            closedConnections.get(), is((1 + 1) + 1 + 1)); // configuration x 2, consumer, producer
    }

    @ParameterizedTest
    @MethodSource("reuseConnectionForExclusiveQueuesWhenMoreConsumersThanQueuesArguments")
    void reuseConnectionForExclusiveQueuesWhenMoreConsumersThanQueues(
        boolean exclusive, boolean sequenceOfQueues, int queues, int consumers, int expectedUnusedConnections, String message) throws Exception {
        params.setExclusive(exclusive);
        String queuePrefix = "perf-test-";
        if (sequenceOfQueues) {
            params.setQueuePattern(queuePrefix + "%d");
            params.setQueueSequenceFrom(1);
            params.setQueueSequenceTo(queues);
        } else {
            params.setQueueNames(range(0, queues).mapToObj(i -> queuePrefix + i).collect(toList()));
        }
        params.setConsumerCount(consumers);

        Channel channel = proxy(Channel.class,
            callback("queueDeclare", (proxy, method, args) -> new AMQImpl.Queue.DeclareOk(args[0].toString(), 0, 0))
        );

        AtomicInteger unusedConnections = new AtomicInteger(0);
        // we want to return different instances because it matters when using connection caching
        Supplier<Connection> connectionSupplier = () -> proxy(Connection.class,
            callback("createChannel", (proxy, method, args) -> channel),
            callback("isOpen", (proxy, method, args) -> true),
            callback("close", (proxy, method, args) -> {
                // un-used connections are closed in a specific manner (to ease testing)
                // they can be un-used because some connections are re-used when there are more
                // consumers than queues and queues are exclusive
                if (args != null && args.length == 3) {
                    unusedConnections.incrementAndGet();
                }
                return null;
            })
        );

        ConnectionFactory connectionFactory = connectionFactoryThatReturns(connectionSupplier);

        MulticastSet set = getMulticastSet(connectionFactory);

        set.run();

        assertThat(message, unusedConnections.get(), is(expectedUnusedConnections));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 1 --queue-pattern-to 100
    @ParameterizedTest
    @ValueSource(strings = { "true", "false" })
    public void sequenceQueuesDefinition1to100(String exclusive) throws Exception {
        params.setExclusive(valueOf(exclusive));
        String queuePrefix = "perf-test-";
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(100);
        params.setConsumerCount(100);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 100 + 1)).newConnection(anyString()); // configuration, consumers, producer
        verify(c, atLeast(1 + 1 + 1)).createChannel(); // configuration, producer, consumer, and checks
        verify(ch, times(100))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), eq(valueOf(exclusive)), anyBoolean(), isNull());
        verify(ch, times(100))
            .queueBind(startsWith(queuePrefix), eq("direct"), routingKeyCaptor.capture());

        assertThat(queueNameCaptor.getAllValues(), allOf(
            iterableWithSize(100),
            hasItems(queuePrefix + "1", queuePrefix + "2", queuePrefix + "100")
        ));
        assertThat(routingKeyCaptor.getAllValues(), allOf(
            iterableWithSize(100),
            hasItems(queuePrefix + "1", queuePrefix + "2", queuePrefix + "100")
        ));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 10 --queue-pattern-to 50
    @ParameterizedTest
    @ValueSource(strings = { "true", "false" })
    public void sequenceQueuesDefinition10to50(String exclusive) throws Exception {
        params.setExclusive(valueOf(exclusive));
        String queuePrefix = "perf-test-";
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(10);
        params.setQueueSequenceTo(50);
        params.setConsumerCount(41);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 41 + 1)).newConnection(anyString()); // configuration, consumers, producer
        verify(c, atLeast(1 + 1 + 1)).createChannel(); // configuration, producer, consumer, and checks
        verify(ch, times(41))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), eq(valueOf(exclusive)), anyBoolean(), isNull());
        verify(ch, times(41))
            .queueBind(startsWith(queuePrefix), eq("direct"), routingKeyCaptor.capture());

        assertThat(queueNameCaptor.getAllValues(), allOf(
            iterableWithSize(41),
            hasItems(queuePrefix + "10", queuePrefix + "11", queuePrefix + "49", queuePrefix + "50")
        ));
        assertThat(routingKeyCaptor.getAllValues(), allOf(
            iterableWithSize(41),
            hasItems(queuePrefix + "10", queuePrefix + "11", queuePrefix + "49", queuePrefix + "50")
        ));
    }

    //  --queue-pattern 'perf-test-%d' --queue-pattern-from 52 --queue-pattern-to 501
    @ParameterizedTest
    @ValueSource(strings = { "true", "false" })
    public void sequenceQueuesDefinition502to5001(String exclusive) throws Exception {
        params.setExclusive(valueOf(exclusive));
        String queuePrefix = "perf-test-";
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(52);
        params.setQueueSequenceTo(501);
        params.setConsumerCount(450);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 450 + 1)).newConnection(anyString()); // configuration, consumers, producer
        verify(c, atLeast(1 + 1 + 1)).createChannel(); // configuration, producer, consumer, and checks
        verify(ch, times(450))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), eq(valueOf(exclusive)), anyBoolean(), isNull());
        verify(ch, times(450))
            .queueBind(startsWith(queuePrefix), eq("direct"), routingKeyCaptor.capture());

        assertThat(queueNameCaptor.getAllValues(), allOf(
            iterableWithSize(450),
            hasItems(queuePrefix + "52", queuePrefix + "53", queuePrefix + "500", queuePrefix + "501"),
            not(hasItems(queuePrefix + "51"))
        ));
        assertThat(routingKeyCaptor.getAllValues(), allOf(
            iterableWithSize(450),
            hasItems(queuePrefix + "52", queuePrefix + "53", queuePrefix + "500", queuePrefix + "501"),
            not(hasItems(queuePrefix + "51"))
        ));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 1 --queue-pattern-to 100 --producers 10 --consumers 0
    @Test
    public void sequenceMoreQueuesThanProducers() throws Exception {
        String queuePrefix = "perf-test-";
        int producerCount = 10;
        params.setConsumerCount(0);
        params.setProducerCount(producerCount);
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(100);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        // once all producers have published messages (producerCount routing keys in the set),
        // we open the latch so MulticastSet.run can end
        Set<String> routingKeys = new HashSet<>();
        CountDownLatch latchPublishing = new CountDownLatch(1);
        doAnswer(invocation -> {
            routingKeys.add(invocation.getArgument(1));
            if (routingKeys.size() == producerCount) {
                latchPublishing.countDown();
            }
            return null;
        }).when(ch).basicPublish(eq("direct"), routingKeyCaptor.capture(),
            anyBoolean(), eq(false),
            any(), any(byte[].class));

        MulticastSet set = getMulticastSet(new MulticastSet.DefaultThreadingHandler(), latchPublishing);

        set.run();

        verify(cf, times(1 + 0 + 10)).newConnection(anyString()); // configuration, consumer, producer
        verify(c, atLeast(1 + 10)).createChannel(); // configuration, producer, and checks
        verify(ch, times(100))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(100))
            .queueBind(startsWith(queuePrefix), eq("direct"), startsWith(queuePrefix));
        verify(ch, never()).basicConsume(anyString(), anyBoolean(), any());

        assertThat(routingKeyCaptor.getAllValues().stream().distinct().toArray(), allOf(
            arrayWithSize(10),
            arrayContainingInAnyOrder(range(1, 11).mapToObj(i -> queuePrefix + i).toArray())
        ));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 1 --queue-pattern-to 10 --producers 15 --consumers 30
    @Test
    public void sequenceProducersAndConsumersSpread() throws Exception {
        String queuePrefix = "perf-test-";
        int queueCount = 3;
        params.setConsumerCount(queueCount * 6);
        params.setProducerCount(queueCount * 3);
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(queueCount);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        // once messages have been to all queues (queueCount routing keys in the set),
        // we open the latch so MulticastSet.run can end
        Set<String> routingKeys = new HashSet<>();
        CountDownLatch latchPublishing = new CountDownLatch(1);
        doAnswer(invocation -> {
            routingKeys.add(invocation.getArgument(1));
            if (routingKeys.size() == queueCount) {
                latchPublishing.countDown();
            }
            return null;
        }).when(ch).basicPublish(eq("direct"), routingKeyCaptor.capture(),
            anyBoolean(), eq(false),
            any(), any(byte[].class));

        MulticastSet set = getMulticastSet(new MulticastSet.DefaultThreadingHandler());

        set.run();

        assertTrue(
            latchPublishing.await(20, TimeUnit.SECONDS),
            () -> format("Only %d / %d routing keys have been published to", routingKeys.size(), queueCount)
        );

        verify(cf, times(1 + queueCount * 6 + queueCount * 3)).newConnection(anyString()); // configuration, consumers, producers
        verify(c, atLeast(1 + queueCount * 6 + queueCount * 3)).createChannel(); // configuration, producers, consumers, and checks
        verify(ch, times(queueCount))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(queueCount))
            .queueBind(startsWith(queuePrefix), eq("direct"), startsWith(queuePrefix));
        verify(ch, times(queueCount * 6)).basicConsume(consumerQueue.capture(), anyBoolean(), any());

        assertThat(routingKeyCaptor.getAllValues().stream().distinct().toArray(), allOf(
            arrayWithSize(queueCount),
            arrayContainingInAnyOrder(range(1, queueCount + 1).mapToObj(i -> queuePrefix + i).toArray())
        ));

        assertThat(routingKeyCaptor.getAllValues().stream().distinct().toArray(), allOf(
            arrayWithSize(queueCount),
            arrayContainingInAnyOrder(range(1, queueCount + 1).mapToObj(i -> queuePrefix + i).toArray())
        ));

        // the captor received all the queues that have at least one consumer
        // let's count the number of consumers per queue
        Map<String, Integer> queueToConsumerNumber = consumerQueue.getAllValues().stream()
            .collect(toMap(queue -> queue, queue -> 1, (oldValue, newValue) -> ++oldValue));

        // there are consumers on all queues
        assertThat(queueToConsumerNumber.keySet().toArray(), allOf(
            arrayWithSize(queueCount),
            arrayContainingInAnyOrder(range(1, queueCount + 1).mapToObj(i -> queuePrefix + i).toArray())
        ));

        // there are 3 consumers per queue
        assertThat(queueToConsumerNumber.values().stream().distinct().toArray(), allOf(
            arrayWithSize(1),
            arrayContaining(6)
        ));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 101 --queue-pattern-to 110 --producers 0 --consumers 110
    @Test
    public void sequenceConsumersSpread() throws Exception {
        String queuePrefix = "perf-test-";
        params.setConsumerCount(110);
        params.setProducerCount(0);
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(101);
        params.setQueueSequenceTo(110);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        // stopping when all consumers are registered
        CountDownLatch latch = new CountDownLatch(110);
        doAnswer(invocation -> {
            latch.countDown();
            return UUID.randomUUID().toString();
        }).when(ch).basicConsume(consumerQueue.capture(), anyBoolean(), any());

        MulticastSet set = getMulticastSet(new InterruptThreadHandler(latch));

        set.run();

        verify(cf, times(1 + 110 + 0)).newConnection(anyString()); // configuration, consumers, producers
        verify(c, atLeast(1 + 110 + 0)).createChannel(); // configuration, producers, consumers, and checks
        verify(ch, times(10))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(10))
            .queueBind(startsWith(queuePrefix), eq("direct"), startsWith(queuePrefix));
        verify(ch, times(110)).basicConsume(anyString(), anyBoolean(), any());

        // the captor received all the queues that have at least one consumer
        // let's count the number of consumers per queue
        Map<String, Integer> queueToConsumerNumber = consumerQueue.getAllValues().stream()
            .collect(toMap(queue -> queue, queue -> 1, (oldValue, newValue) -> ++oldValue));

        // there are consumers on all queues
        assertThat(queueToConsumerNumber.keySet().toArray(), allOf(
            arrayWithSize(10),
            arrayContainingInAnyOrder(range(101, 111).mapToObj(i -> queuePrefix + i).toArray())
        ));

        // there are 11 consumers per queue
        assertThat(queueToConsumerNumber.values().stream().distinct().toArray(), allOf(
            arrayWithSize(1),
            arrayContaining(11)
        ));
    }

    private MulticastSet getMulticastSet() {
        NoOpThreadingHandler noOpThreadingHandler = new NoOpThreadingHandler();
        return getMulticastSet(noOpThreadingHandler, cf);
    }

    private MulticastSet getMulticastSet(MulticastSet.ThreadingHandler threadingHandler) {
        return getMulticastSet(threadingHandler, cf);
    }

    private MulticastSet getMulticastSet(ConnectionFactory connectionFactory) {
        return getMulticastSet(new NoOpThreadingHandler(), connectionFactory);
    }

    private MulticastSet getMulticastSet(MulticastSet.ThreadingHandler threadingHandler, ConnectionFactory connectionFactory) {
        MulticastSet set = new MulticastSet(
            stats, connectionFactory, params, singletonList("amqp://localhost"), new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() {
            }

            @Override
            public void countDown() {
            }
        }
        );

        set.setThreadingHandler(threadingHandler);
        return set;
    }

    private MulticastSet getMulticastSet(MulticastSet.ThreadingHandler threadingHandler, CountDownLatch completionLatch) {
        MulticastSet set = new MulticastSet(
            stats, cf, params, singletonList("amqp://localhost"), new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() throws InterruptedException {
                completionLatch.await(10, TimeUnit.SECONDS);
            }

            @Override
            public void countDown() {
            }
        }
        );

        set.setThreadingHandler(threadingHandler);
        return set;
    }

    static class NoOpThreadingHandler implements MulticastSet.ThreadingHandler {

        final ExecutorService executorService = mock(ExecutorService.class);
        final ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);

        @SuppressWarnings("unchecked")
        public NoOpThreadingHandler() {
            Future future = mock(Future.class);
            when(executorService.submit(any(Runnable.class))).thenReturn(future);
        }

        @Override
        public ExecutorService executorService(String name, int nbThreads) {
            return executorService;
        }

        @Override
        public ScheduledExecutorService scheduledExecutorService(String name, int nbThreads) {
            return scheduledExecutorService;
        }

        @Override
        public void shutdown() {
        }
    }

    static class InterruptThreadHandler implements MulticastSet.ThreadingHandler {

        final CountDownLatch[] latches;
        final ExecutorService backingExecutorService = Executors.newCachedThreadPool();
        final ExecutorService executorService = mock(ExecutorService.class);
        final ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);

        InterruptThreadHandler(CountDownLatch... latches) {
            this.latches = latches;
            Future future = mock(Future.class);
            try {
                when(future.get()).then(invocation -> {
                    for (CountDownLatch latch : latches) {
                        latch.await(1, TimeUnit.SECONDS);
                    }
                    return null;
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
            when(executorService.submit(any(Runnable.class))).thenAnswer(invocation -> {
                backingExecutorService.submit((Runnable) invocation.getArguments()[0]);
                return future;
            });
        }

        @Override
        public ExecutorService executorService(String name, int nbThreads) {
            return executorService;
        }

        @Override
        public ScheduledExecutorService scheduledExecutorService(String name, int nbThreads) {
            return scheduledExecutorService;
        }

        @Override
        public void shutdown() {
            backingExecutorService.shutdown();
        }
    }
}
