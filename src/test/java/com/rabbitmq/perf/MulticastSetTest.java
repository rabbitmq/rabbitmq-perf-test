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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.rabbitmq.perf.MulticastSet.nbThreadsForConsumer;
import static com.rabbitmq.perf.MulticastSet.nbThreadsForProducerScheduledExecutorService;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 *
 */
public class MulticastSetTest {

    MulticastParams params;

    @BeforeEach
    public void init() {
        params = new MulticastParams();
    }

    @Test
    public void nbThreadsForConsumerShouldBeEqualsToChannelCount() {
        params.setConsumerChannelCount(1);
        assertThat(nbThreadsForConsumer(params), is(1));
        params.setConsumerChannelCount(2);
        assertThat(nbThreadsForConsumer(params), is(2));
    }

    @Test
    public void nbThreadsForConsumerShouldBeEqualsToDefaultConsumerWorkServiceThreadCount() {
        params.setConsumerChannelCount(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE);
        assertThat(nbThreadsForConsumer(params), is(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE));
    }

    @Test
    public void nbThreadsForConsumerShouldNotBeMoreThanDefaultConsumerWorkServiceThreadCount() {
        params.setConsumerChannelCount(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE * 2);
        assertThat(nbThreadsForConsumer(params), is(MulticastSet.DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE));
    }

    @Test public void nbThreadsForProducerScheduledExecutorServiceDefaultIsOne() {
        assertThat(nbThreadsForProducerScheduledExecutorService(params), is(1));
    }

    @Test public void nbThreadsForProducerScheduledExecutorServiceOneThreadEvery50Producers() {
        params.setProducerCount(120);
        assertThat(nbThreadsForProducerScheduledExecutorService(params), is(3));
    }

    @Test public void nbThreadsForProducerScheduledExecutorServiceOneThreadEvery50ProducersIncludeChannels() {
        params.setProducerCount(30);
        params.setProducerChannelCount(4);
        assertThat(nbThreadsForProducerScheduledExecutorService(params), is(3));
    }

    @Test public void nbThreadsForProducerScheduledExecutorServiceUseParameterValueWhenSpecified() {
        params.setProducerSchedulerThreadCount(7);
        assertThat(nbThreadsForProducerScheduledExecutorService(params), is(7));
    }

}
