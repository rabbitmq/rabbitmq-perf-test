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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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

    @BeforeEach
    public void init() throws Exception {
        initMocks(this);

        when(cf.newConnection()).thenReturn(c);
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

        verify(cf, times(1 + 1 + 1)).newConnection(); // consumers, producers, configuration (not used)
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

        verify(cf, times(10 + 10 + 1)).newConnection(); // consumers, producers, configuration (not used)
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

        verify(cf, times(2 + 1 + 1)).newConnection(); // consumers, producers, configuration (not used)
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

        verify(cf, times(4 + 2 + 1)).newConnection(); // consumers, producers, configuration (not used)
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

        verify(cf, times(1 + 1 + 1)).newConnection(); // consumers, producers, configuration (not used)
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

        verify(cf, times(1 + 1)).newConnection(); // configuration and producer
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

        verify(cf, times(10 + 1)).newConnection(); // consumers, configuration (not used)
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

        verify(cf, times(1 + 1)).newConnection(); // configuration, producer
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

        verify(cf, times(1 + 1)).newConnection(); // consumer, configuration (not used)
        verify(c, atLeast(1 + 1)).createChannel(); // configuration, consumer, checks
        verify(ch, never()) // shouldn't be called, pre-declared is true
            .queueDeclare(eq(queue), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(1))
            .queueBind(eq(queue), eq("direct"), eq(routingKey));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 1 --queue-pattern-to 100
    @Test
    public void sequenceQueuesDefinition1to100() throws Exception {
        String queuePrefix = "perf-test-";
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(100);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1 + 1)).newConnection(); // configuration, consumer, producer
        verify(c, atLeast(1 + 1 + 1)).createChannel(); // configuration, producer, consumer, and checks
        verify(ch, times(100))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
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

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 100 --queue-pattern-to 500
    @Test
    public void sequenceQueuesDefinition100to500() throws Exception {
        String queuePrefix = "perf-test-";
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(100);
        params.setQueueSequenceTo(500);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1 + 1)).newConnection(); // configuration, consumer, producer
        verify(c, atLeast(1 + 1 + 1)).createChannel(); // configuration, producer, consumer, and checks
        verify(ch, times(401))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(401))
            .queueBind(startsWith(queuePrefix), eq("direct"), routingKeyCaptor.capture());

        assertThat(queueNameCaptor.getAllValues(), allOf(
            iterableWithSize(401),
            hasItems(queuePrefix + "100", queuePrefix + "101", queuePrefix + "499", queuePrefix + "500")
        ));
        assertThat(routingKeyCaptor.getAllValues(), allOf(
            iterableWithSize(401),
            hasItems(queuePrefix + "100", queuePrefix + "101", queuePrefix + "499", queuePrefix + "500")
        ));
    }

    //  --queue-pattern 'perf-test-%d' --queue-pattern-from 502 --queue-pattern-to 5001
    @Test
    public void sequenceQueuesDefinition502to5001() throws Exception {
        String queuePrefix = "perf-test-";
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(502);
        params.setQueueSequenceTo(5001);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        MulticastSet set = getMulticastSet();

        set.run();

        verify(cf, times(1 + 1 + 1)).newConnection(); // configuration, consumer, producer
        verify(c, atLeast(1 + 1 + 1)).createChannel(); // configuration, producer, consumer, and checks
        verify(ch, times(4500))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(4500))
            .queueBind(startsWith(queuePrefix), eq("direct"), routingKeyCaptor.capture());

        assertThat(queueNameCaptor.getAllValues(), allOf(
            iterableWithSize(4500),
            hasItems(queuePrefix + "502", queuePrefix + "503", queuePrefix + "5000", queuePrefix + "5001"),
            not(hasItems(queuePrefix + "501"))
        ));
        assertThat(routingKeyCaptor.getAllValues(), allOf(
            iterableWithSize(4500),
            hasItems(queuePrefix + "502", queuePrefix + "503", queuePrefix + "5000", queuePrefix + "5001"),
            not(hasItems(queuePrefix + "501"))
        ));
    }

    // --queue-pattern 'perf-test-%d' --queue-pattern-from 1 --queue-pattern-to 100 --producers 10 --consumers 0
    @Test
    public void sequenceMoreQueuesThanProducers() throws Exception {
        String queuePrefix = "perf-test-";
        params.setConsumerCount(0);
        params.setProducerCount(10);
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(100);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        // we wait for 1 K messages to be sent
        // hopefully there will be messages of all the producers to assert against all routing key values
        CountDownLatch latchPublishing = new CountDownLatch(1000);
        doAnswer(invocation -> {
            latchPublishing.countDown();
            return null;
        }).when(ch).basicPublish(eq("direct"), routingKeyCaptor.capture(),
            anyBoolean(), eq(false),
            any(), any(byte[].class));

        MulticastSet set = getMulticastSet(new InterruptThreadHandler(latchPublishing));

        set.run();

        verify(cf, times(1 + 0 + 10)).newConnection(); // configuration, consumer, producer
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
        params.setConsumerCount(30);
        params.setProducerCount(15);
        params.setQueuePattern(queuePrefix + "%d");
        params.setQueueSequenceFrom(1);
        params.setQueueSequenceTo(10);

        when(ch.queueDeclare(queueNameCaptor.capture(), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .then(invocation -> new AMQImpl.Queue.DeclareOk(invocation.getArgument(0), 0, 0));

        // we wait for 1 K messages to be sent
        // hopefully there will be messages of all the producers to assert against all routing key values
        CountDownLatch latchPublishing = new CountDownLatch(1000);
        doAnswer(invocation -> {
            latchPublishing.countDown();
            return null;
        }).when(ch).basicPublish(eq("direct"), routingKeyCaptor.capture(),
            anyBoolean(), eq(false),
            any(), any(byte[].class));

        MulticastSet set = getMulticastSet(new InterruptThreadHandler(latchPublishing));

        set.run();

        verify(cf, times(1 + 30 + 15)).newConnection(); // configuration, consumers, producers
        verify(c, atLeast(1 + 30 + 15)).createChannel(); // configuration, producers, consumers, and checks
        verify(ch, times(10))
            .queueDeclare(startsWith(queuePrefix), anyBoolean(), anyBoolean(), anyBoolean(), isNull());
        verify(ch, times(10))
            .queueBind(startsWith(queuePrefix), eq("direct"), startsWith(queuePrefix));
        verify(ch, times(30)).basicConsume(consumerQueue.capture(), anyBoolean(), any());

        assertThat(routingKeyCaptor.getAllValues().stream().distinct().toArray(), allOf(
            arrayWithSize(10),
            arrayContainingInAnyOrder(range(1, 11).mapToObj(i -> queuePrefix + i).toArray())
        ));

        assertThat(routingKeyCaptor.getAllValues().stream().distinct().toArray(), allOf(
            arrayWithSize(10),
            arrayContainingInAnyOrder(range(1, 11).mapToObj(i -> queuePrefix + i).toArray())
        ));

        // the captor received all the queues that have at least one consumer
        // let's count the number of consumers per queue
        Map<String, Integer> queueToConsumerNumber = consumerQueue.getAllValues().stream()
            .collect(toMap(queue -> queue, queue -> 1, (oldValue, newValue) -> ++oldValue));

        // there are consumers on all queues
        assertThat(queueToConsumerNumber.keySet().toArray(), allOf(
            arrayWithSize(10),
            arrayContainingInAnyOrder(range(1, 11).mapToObj(i -> queuePrefix + i).toArray())
        ));

        // there are 3 consumers per queue
        assertThat(queueToConsumerNumber.values().stream().distinct().toArray(), allOf(
            arrayWithSize(1),
            arrayContaining(3)
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

        verify(cf, times(1 + 110 + 0)).newConnection(); // configuration, consumers, producers
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
        return getMulticastSet(new NoOpThreadHandler());
    }

    private MulticastSet getMulticastSet(MulticastSet.ThreadHandler threadHandler) {
        MulticastSet set = new MulticastSet(
            stats, cf, params, singletonList("amqp://localhost")
        );

        set.setThreadHandler(threadHandler);
        return set;
    }

    static class NoOpThreadHandler implements MulticastSet.ThreadHandler {

        @Override
        public void start(Thread thread) {
        }

        @Override
        public void waitForCompletion(Thread thread) {
        }
    }

    static class InterruptThreadHandler implements MulticastSet.ThreadHandler {

        final CountDownLatch[] latches;

        InterruptThreadHandler(CountDownLatch... latches) {
            this.latches = latches;
        }

        @Override
        public void start(Thread thread) {
            thread.start();
        }

        @Override
        public void waitForCompletion(Thread thread) throws InterruptedException {
            for (CountDownLatch latch : latches) {
                latch.await(1, TimeUnit.SECONDS);
            }
            thread.interrupt();
            thread.join(500);
        }
    }
}
