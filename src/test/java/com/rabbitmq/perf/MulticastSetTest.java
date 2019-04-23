// Copyright (c) 2018-2019 Pivotal Software, Inc.  All rights reserved.
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

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.rabbitmq.perf.MulticastSet.*;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 *
 */
public class MulticastSetTest {

    MulticastParams params;

    @Mock
    ConnectionFactory factory;
    @Mock
    Connection connection;

    static Collection<String> oneUri() {
        return new ArrayList<>(asList("amqp://host1"));
    }

    static Collection<String> threeUris() {
        return new ArrayList<>(asList("amqp://host1", "amqp://host2", "amqp://host3"));
    }

    @BeforeEach
    public void init() {
        params = new MulticastParams();
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void nbThreadsForConsumerShouldBeEqualsToChannelCount() {
        params.setConsumerChannelCount(1);
        assertThat(nbThreadsForConsumer(params)).isEqualTo(1);
        params.setConsumerChannelCount(2);
        assertThat(nbThreadsForConsumer(params)).isEqualTo(2);
    }

    @Test
    public void nbThreadsForConsumerShouldBeEqualsToDefaultConsumerWorkServiceThreadCount() {
        params.setConsumerChannelCount(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE);
        assertThat(nbThreadsForConsumer(params)).isEqualTo(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE);
    }

    @Test
    public void nbThreadsForConsumerShouldNotBeMoreThanDefaultConsumerWorkServiceThreadCount() {
        params.setConsumerChannelCount(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE * 2);
        assertThat(nbThreadsForConsumer(params)).isEqualTo(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE);
    }

    @Test
    public void nbThreadsForProducerScheduledExecutorServiceDefaultIsOne() {
        assertThat(nbThreadsForProducerScheduledExecutorService(params)).isEqualTo(1);
    }

    @Test
    public void nbThreadsForProducerScheduledExecutorServiceOneThreadEvery50Producers() {
        params.setProducerCount(120);
        assertThat(nbThreadsForProducerScheduledExecutorService(params)).isEqualTo(3);
    }

    @Test
    public void nbThreadsForProducerScheduledExecutorServiceOneThreadEvery50ProducersIncludeChannels() {
        params.setProducerCount(30);
        params.setProducerChannelCount(4);
        assertThat(nbThreadsForProducerScheduledExecutorService(params)).isEqualTo(3);
    }

    @Test
    public void nbThreadsForProducerScheduledExecutorServiceUseParameterValueWhenSpecified() {
        params.setProducerSchedulerThreadCount(7);
        assertThat(nbThreadsForProducerScheduledExecutorService(params)).isEqualTo(7);
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldReturnTrueWhenNoTimeout() throws Exception {
        Collection<String> uris = oneUri();
        assertThat(waitUntilBrokerAvailableIfNecessary(-1, -1, uris, factory)).isTrue();
        assertThat(uris).containsAll(uris);
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldReturnTrueWhenConnectionSucceeds() throws Exception {
        when(factory.newConnection("perf-test-test")).thenReturn(connection);
        Collection<String> uris = oneUri();
        assertThat(waitUntilBrokerAvailableIfNecessary(1, 1, uris, factory)).isTrue();
        assertThat(uris).containsAll(uris);
        verify(factory, times(1)).newConnection("perf-test-test");
        verify(connection, times(1)).close();
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldRetryAndReturnTrueWhenConnectionFailsThenSucceeds() throws Exception {
        when(factory.newConnection("perf-test-test"))
                .thenThrow(IOException.class)
                .thenReturn(connection);
        Collection<String> uris = oneUri();
        assertThat(waitUntilBrokerAvailableIfNecessary(5, 1, uris, factory)).isTrue();
        assertThat(uris).containsAll(uris);
        verify(factory, times(2)).newConnection("perf-test-test");
        verify(connection, times(1)).close();
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldReturnFalseWhenTimesOutBecauseOfConnectionFailures() throws Exception {
        when(factory.newConnection("perf-test-test"))
                .thenThrow(IOException.class);
        assertThat(waitUntilBrokerAvailableIfNecessary(1, 1, oneUri(), factory)).isFalse();
        verify(factory, atLeastOnce()).newConnection("perf-test-test");
        verify(connection, never()).close();
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldTestAllBrokers() throws Exception {
        when(factory.newConnection("perf-test-test")).thenReturn(connection);
        Collection<String> uris = threeUris();
        assertThat(waitUntilBrokerAvailableIfNecessary(1, 3, uris, factory)).isTrue();
        assertThat(uris).containsAll(threeUris());
        verify(factory, times(3)).newConnection("perf-test-test");
        verify(connection, times(3)).close();
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldTimeoutIfNotAllBrokersAreAvailable() throws Exception {
        AtomicReference<String> currentUri = new AtomicReference<>();
        doAnswer(invocation -> {
            currentUri.set(invocation.getArgument(0).toString());
            return null;
        }).when(factory).setUri(anyString());
        when(factory.newConnection("perf-test-test")).thenAnswer(invocation -> {
            if (currentUri.get().contains("host2")) {
                throw new IOException("simulating down broker");
            }
            return connection;
        });
        assertThat(waitUntilBrokerAvailableIfNecessary(1, 3, threeUris(), factory)).isFalse();
        verify(factory, atLeast(3)).newConnection("perf-test-test");
        verify(connection, times(2)).close();
    }

    @Test
    public void waitUntilBrokerAvailableIfNecessaryShouldReturnTrueOnceEnoughBrokersAreUp() throws Exception {
        StopWatch watch = new StopWatch();
        try {
            int startUpTimeInSeconds = 2;
            AtomicReference<String> currentUri = new AtomicReference<>();
            doAnswer(invocation -> {
                if (!watch.isStarted()) {
                    watch.start();
                }
                currentUri.set(invocation.getArgument(0).toString());
                return null;
            }).when(factory).setUri(anyString());
            when(factory.newConnection("perf-test-test")).thenAnswer(invocation -> {
                // brokers are not available at the beginning
                if (watch.getTime(TimeUnit.SECONDS) < startUpTimeInSeconds) {
                    throw new IOException("simulating down broker");
                }
                // host2 is never available
                if (currentUri.get().contains("host2")) {
                    throw new IOException("simulating down broker");
                }
                return connection;
            });
            Collection<String> uris = threeUris();
            assertThat(waitUntilBrokerAvailableIfNecessary(startUpTimeInSeconds * 2, 2, uris, factory)).isTrue();
            assertThat(uris).hasSize(2).contains("amqp://host1", "amqp://host3").doesNotContain("amqp://host2");
            verify(factory, atLeast(3)).newConnection("perf-test-test");
            verify(connection, times(2)).close();
        } finally {
            watch.stop();
        }
    }

}
