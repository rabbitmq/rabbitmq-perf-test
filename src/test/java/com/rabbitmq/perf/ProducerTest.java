// Copyright (c) 2017-Present Pivotal Software, Inc.  All rights reserved.
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

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

public class ProducerTest {

    @Mock
    Channel channel;

    @Captor
    private ArgumentCaptor<BasicProperties> propertiesCaptor;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void flagNone() throws Exception {
        flagProducer().run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), nullValue());
        assertThat(props().getPriority(), nullValue());
    }

    @Test
    public void flagPersistent() throws Exception {
        flagProducer("persistent").run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getPriority(), nullValue());
    }

    @Test
    public void flagMandatory() throws Exception {
        flagProducer("mandatory").run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(true), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), nullValue());
        assertThat(props().getPriority(), nullValue());
    }

    @Test
    public void flagPriority() throws Exception {
        flagProducer("priority=10").run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), nullValue());
        assertThat(props().getPriority(), is(10));
    }

    @Test
    public void flagPersistentMandatoryPriority() throws Exception {
        flagProducer("persistent", "mandatory", "priority=10").run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(true), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getPriority(), is(10));
    }

    Producer flagProducer(String... flags) {
        return new Producer(
            channel, "exchange", "id", false,
            asList(flags),
            0, 0.0f, 1,
            -1, 30,
            new TimeSequenceMessageBodySource(new TimestampProvider(false, false), 1000),
            new TimestampProvider(false, false),
            stats(),
            new MulticastSet.CompletionHandler() {

                @Override
                public void waitForCompletion() {
                }

                @Override
                public void countDown() {
                }
            }
        );
    }

    BasicProperties props() {
        return propertiesCaptor.getValue();
    }

    private Stats stats() {
        return new Stats(0) {

            @Override
            protected void report(long now) {

            }
        };
    }
}
