// Copyright (c) 2018-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.Address;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.time.Duration;
import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    @Mock
    Stats stats;

    AutoCloseable mocks;

    static List<String> oneUri() {
        return new ArrayList<>(asList("amqp://host1"));
    }

    static List<String> threeUris() {
        return new ArrayList<>(asList("amqp://host1", "amqp://host2", "amqp://host3"));
    }

    @BeforeEach
    public void init() {
        params = new MulticastParams();
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    public void tearDown() throws Exception {
        mocks.close();
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
    public void nbThreadsForProducerScheduledExecutorServiceOneThreadEvery100Producers() {
        params.setProducerCount(220);
        assertThat(nbThreadsForProducerScheduledExecutorService(params)).isEqualTo(3);
    }

    @Test
    public void nbThreadsForProducerScheduledExecutorServiceOneThreadEvery100ProducersIncludeChannels() {
        params.setProducerCount(30);
        params.setProducerChannelCount(8);
        assertThat(nbThreadsForProducerScheduledExecutorService(params)).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({
        "1,1000,1",
        "200,1000,3",
        "299,1000,3",
        "300,1000,4",
        "200,100,21",
        "2000,1000,21",
        "1000,60000,1"
    })
    void nbThreadsForProducerScheduledExecutorServiceOK(int producerCount, int publishingIntervalMs,
            int expectedThreadCount) {
        params.setProducerCount(producerCount);
        params.setPublishingInterval(Duration.ofMillis(publishingIntervalMs));
        assertThat(nbThreadsForProducerScheduledExecutorService(params))
            .isEqualTo(expectedThreadCount);
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

    @Test
    public void createConnectionAddressesNotUsedWhenNoUriList() throws Exception {
        MulticastSet multicastSet = getMulticastSet(new ArrayList<>());
        multicastSet.createConnection("connection-1");
        assertThat(multicastSet.createConfigurationConnections()).hasSize(1);
        verify(factory, times(1)).newConnection("connection-1");
        verify(factory, times(1)).newConnection("perf-test-configuration-0");
    }

    @Test
    public void createConnectionFromOneUri() throws Exception {
        MulticastSet multicastSet = getMulticastSet(Arrays.asList("amqp://host1:5673"));
        multicastSet.createConnection("connection-1");
        ArgumentCaptor<List<Address>> addresses = addressesArgumentCaptor();
        verify(factory, times(1)).newConnection(addresses.capture(), ArgumentMatchers.eq("connection-1"));
        assertThat(addresses.getValue()).hasSize(1)
                .element(0)
                .hasFieldOrPropertyWithValue("host", "host1")
                .hasFieldOrPropertyWithValue("port", 5673);
    }

    @Test
    public void createConfigurationConnectionFromOneUri() throws Exception {
        MulticastSet multicastSet = getMulticastSet(Arrays.asList("amqp://host1:5673"));
        assertThat(multicastSet.createConfigurationConnections()).hasSize(1);
        ArgumentCaptor<List<Address>> addresses = addressesArgumentCaptor();
        verify(factory, times(1)).newConnection(addresses.capture(), ArgumentMatchers.eq("perf-test-configuration-0"));
        assertThat(addresses.getAllValues()).hasSize(1);
        assertThat(addresses.getValue()).hasSize(1)
                .element(0)
                .hasFieldOrPropertyWithValue("host", "host1")
                .hasFieldOrPropertyWithValue("port", 5673);
    }

    @Test
    public void createConnectionFromSeveralUrisShufflingShouldHappen() throws Exception {
        List<String> uris = IntStream.range(0, 100).mapToObj(i -> "amqp://host" + i).collect(Collectors.toList());
        MulticastSet multicastSet = getMulticastSet(uris);
        multicastSet.createConnection("connection-1");
        ArgumentCaptor<List<Address>> addressesArgument = addressesArgumentCaptor();
        verify(factory, times(1)).newConnection(addressesArgument.capture(), ArgumentMatchers.eq("connection-1"));
        List<Address> addresses = addressesArgument.getValue();
        assertThat(addresses).hasSameSizeAs(uris);

        boolean someDifference = false;
        for (int i = 0; i < uris.size(); i++) {
            URI uri = new URI(uris.get(i));
            if (!uri.getHost().equals(addresses.get(i).getHost())) {
                someDifference = true;
            }
        }
        assertThat(someDifference).as("Addresses should have been shuffled").isTrue();
    }

    @Test
    public void createConfigurationConnectionFromSeveralUrisConnectionShouldBeSpread() throws Exception {
        int hostCount = 5;
        List<String> uris = IntStream.range(0, hostCount).mapToObj(i -> "amqp://host" + i + ":" + (5672 + i)).collect(Collectors.toList());
        MulticastSet multicastSet = getMulticastSet(uris);
        assertThat(multicastSet.createConfigurationConnections()).hasSize(hostCount);
        ArgumentCaptor<List<Address>> addresses = addressesArgumentCaptor();
        verify(factory, times(hostCount)).newConnection(addresses.capture(), ArgumentMatchers.anyString());
        assertThat(addresses.getAllValues()).hasSize(hostCount);
        IntStream.range(0, hostCount).forEach(i -> {
            List<Address> oneItemAddressList = addresses.getAllValues().get(i);
            assertThat(oneItemAddressList).hasSize(1)
                    .element(0)
                    .hasFieldOrPropertyWithValue("host", "host" + i)
                    .hasFieldOrPropertyWithValue("port", 5672 + i);
        });

    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Address>> addressesArgumentCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private MulticastSet getMulticastSet(List<String> uris) {
        MulticastSet set = new MulticastSet(
                stats, factory, params, uris, new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() {
            }

            @Override
            public void countDown(String reason) {
            }
        }
        );

        return set;
    }

}
