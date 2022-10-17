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

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 *
 */
public class ConsumerSharingThreadsTest {

    @Mock
    ConnectionFactory cf;
    @Mock
    Connection c;
    @Mock
    Channel ch;
    @Mock
    PerformanceMetrics performanceMetrics;
    @Mock
    MulticastSet.ThreadingHandler threadingHandler;
    @Mock
    ExecutorService executorService;
    @Mock
    Future future;

    MulticastParams params;

    AutoCloseable mocks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void init() throws Exception {
        mocks = openMocks(this);

        when(cf.newConnection(anyList(), anyString())).thenReturn(c);
        when(c.createChannel()).thenReturn(ch);
        when(ch.queueDeclare(eq(""), anyBoolean(), anyBoolean(), anyBoolean(), isNull()))
            .thenReturn(new AMQImpl.Queue.DeclareOk("", 0, 0));

        when(threadingHandler.executorService(anyString(), anyInt())).thenReturn(executorService);
        when(executorService.submit(any(Runnable.class))).thenReturn(future);

        params = new MulticastParams();
    }

    @AfterEach
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void noSharing() throws Exception {
        MulticastSet set = getMulticastSet();
        params.setConsumerCount(10);
        set.run();
        verify(threadingHandler, times(1 + 10 + 1 + 1)) // for configuration, for each consumer, for all producers, and one for each producer
            .executorService(anyString(), anyInt());
    }

    @Test public void limitConsumersThreadPools() throws Exception {
        MulticastSet set = getMulticastSet();
        params.setConsumerCount(10);
        params.setConsumersThreadPools(5);
        set.run();
        verify(threadingHandler, times(1 + 5 + 1 + 1)) // for configuration, for each consumer, for all producers, and one for each producer
            .executorService(anyString(), anyInt());
    }

    @Test public void fewerConsumersThanConsumerThreadPools() throws Exception {
        MulticastSet set = getMulticastSet();
        params.setConsumerCount(5);
        params.setConsumersThreadPools(10);
        set.run();
        verify(threadingHandler, times(1 + 5 + 1 + 1)) // for configuration, for each consumer, for all producers, and one for each producer
            .executorService(anyString(), anyInt());
    }

    @Test public void setConsumersThreadPoolsWithManyConsumers() throws Exception {
        MulticastSet set = getMulticastSet();
        params.setConsumerCount(20);
        params.setConsumersThreadPools(6);
        set.run();
        verify(threadingHandler, times(1 + 6 + 1 + 1)) // for configuration, for each consumer, for all producers, and one for each producer
            .executorService(anyString(), anyInt());
    }

    private MulticastSet getMulticastSet() {
        MulticastSet set = new MulticastSet(
            performanceMetrics, cf, params, singletonList("amqp://localhost"), new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() {
            }

            @Override
            public void countDown(String reason) {
            }
        }
        );

        set.setThreadingHandler(threadingHandler);
        return set;
    }
}
