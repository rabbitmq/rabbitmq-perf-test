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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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

    @BeforeEach
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
    public void priority() throws Exception {
        flagProducer(singletonMap("priority", 10)).run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), nullValue());
        assertThat(props().getPriority(), is(10));
    }

    @Test
    public void flagPersistentMandatoryPriority() throws Exception {
        flagProducer(singletonMap("priority", 10), "persistent", "mandatory").run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(true), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getPriority(), is(10));
    }

    @Test
    public void noTimestampInHeader() throws Exception {
        flagProducer().run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getHeaders(), nullValue());
    }

    @Test
    public void timestampInHeader() throws Exception {
        Producer producer = new Producer(new ProducerParameters()
            .setChannel(channel).setExchangeName("exchange").setId("id").setRandomRoutingKey(false)
            .setFlags(asList("persistent"))
            .setTxSize(0).setRateLimit(0.0f).setMsgLimit(1)
            .setConfirm(-1).setConfirmTimeout(30)
            .setMessageBodySource(new TimeSequenceMessageBodySource(new TimestampProvider(true, true), 1000))
            .setTsp(new TimestampProvider(true, true))
            .setStats(stats())
            .setMessageProperties(null).setCompletionHandler(completionHandler()).setRoutingKeyCacheSize(0)
            .setRandomStartDelayInSeconds(-1)
            .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
        );

        producer.run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getHeaders(), notNullValue());
        assertThat(props().getHeaders().get(Producer.TIMESTAMP_HEADER), notNullValue());
    }

    @Test
    public void messagePropertiesAll() throws Exception {
        Map<String, Object> messageProperties = new HashMap<String, Object>() {{
            put("contentType", "text/plain");
            put("contentEncoding", "UTF-8");
            put("deliveryMode", 2);
            put("priority", 10);
            put("correlationId", "dummy");
            put("replyTo", "foo");
            put("expiration", "later");
            put("messageId", "bar");
            put("timestamp", "2007-12-03T10:15:30+01:00");
            put("type", "third");
            put("userId", "jdoe");
            put("appId", "sender");
            put("clusterId", "rabbitmq");
        }};

        flagProducer(messageProperties).run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getContentType(), is("text/plain"));
        assertThat(props().getContentEncoding(), is("UTF-8"));
        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getPriority(), is(10));
        assertThat(props().getCorrelationId(), is("dummy"));
        assertThat(props().getReplyTo(), is("foo"));
        assertThat(props().getExpiration(), is("later"));
        assertThat(props().getMessageId(), is("bar"));
        assertThat(props().getTimestamp(), is(Date.from(OffsetDateTime.parse("2007-12-03T10:15:30+01:00").toInstant())));
        assertThat(props().getType(), is("third"));
        assertThat(props().getUserId(), is("jdoe"));
        assertThat(props().getAppId(), is("sender"));
        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getClusterId(), is("rabbitmq"));
    }

    @Test
    public void messagePropertiesOverrideDeliveryContentType() throws Exception {
        Map<String, Object> messageProperties = new HashMap<String, Object>() {{
            put("contentType", "text/plain");
            put("deliveryMode", 1);
        }};

        Producer producer = new Producer(
            new ProducerParameters()
                .setChannel(channel).setExchangeName("exchange").setId("id").setRandomRoutingKey(false)
                .setFlags(asList("persistent"))
                .setTxSize(0).setRateLimit(0.0f).setMsgLimit(1)
                .setConfirm(-1).setConfirmTimeout(30)
                .setMessageBodySource((sequence) -> new MessageBodySource.MessageBodyAndContentType("".getBytes(), "application/json"))
                .setTsp(new TimestampProvider(true, true))
                .setStats(stats())
                .setMessageProperties(messageProperties).setCompletionHandler(completionHandler()).setRoutingKeyCacheSize(0)
                .setRandomStartDelayInSeconds(-1)
                .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
        );

        producer.run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getContentType(), is("text/plain"));
        assertThat(props().getDeliveryMode(), is(1));
    }

    @Test
    public void messagePropertiesAndHeaders() throws Exception {
        Map<String, Object> messageProperties = new HashMap<String, Object>() {{
            put("contentType", "text/plain");
            put("contentEncoding", "UTF-8");
            put("deliveryMode", 2);
            put("header1", "value1");
            put("header2", "value2");
        }};

        flagProducer(messageProperties).run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getContentType(), is("text/plain"));
        assertThat(props().getContentEncoding(), is("UTF-8"));
        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getHeaders(), notNullValue());
        assertThat(props().getHeaders().get("header1"), is("value1"));
        assertThat(props().getHeaders().get("header2"), is("value2"));
    }

    @Test
    public void messagePropertiesAndHeadersKeepOtherHeaders() throws Exception {
        Map<String, Object> messageProperties = new HashMap<String, Object>() {{
            put("contentType", "text/plain");
            put("contentEncoding", "UTF-8");
            put("deliveryMode", 2);
            put("header1", "value1");
            put("header2", "value2");
        }};

        Producer producer = new Producer(
            new ProducerParameters()
                .setChannel(channel).setExchangeName("exchange").setId("id").setRandomRoutingKey(false)
                .setFlags(asList("persistent"))
                .setTxSize(0).setRateLimit(0.0f).setMsgLimit(1)
                .setConfirm(-1).setConfirmTimeout(30)
                .setMessageBodySource(new TimeSequenceMessageBodySource(new TimestampProvider(true, true), 1000))
                .setTsp(new TimestampProvider(true, true))
                .setStats(stats())
                .setMessageProperties(messageProperties).setCompletionHandler(completionHandler()).setRoutingKeyCacheSize(0)
                .setRandomStartDelayInSeconds(-1)
                .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
        );

        producer.run();

        verify(channel).basicPublish(anyString(), anyString(),
            eq(false), eq(false), propertiesCaptor.capture(),
            any(byte[].class)
        );

        assertThat(props().getContentType(), is("text/plain"));
        assertThat(props().getContentEncoding(), is("UTF-8"));
        assertThat(props().getDeliveryMode(), is(2));
        assertThat(props().getHeaders(), notNullValue());
        assertThat(props().getHeaders(), aMapWithSize(3));
        assertThat(props().getHeaders().get("header1"), is("value1"));
        assertThat(props().getHeaders().get("header2"), is("value2"));
    }

    Producer flagProducer(String... flags) {
        return flagProducer(null, flags);
    }

    Producer flagProducer(Map<String, Object> messageProperties, String... flags) {
        return new Producer(
            new ProducerParameters()
                .setChannel(channel).setExchangeName("exchange").setId("id").setRandomRoutingKey(false)
                .setFlags(asList(flags))
                .setTxSize(0).setRateLimit(0.0f).setMsgLimit(1)
                .setConfirm(-1).setConfirmTimeout(30)
                .setMessageBodySource(new TimeSequenceMessageBodySource(new TimestampProvider(false, false), 1000))
                .setTsp(new TimestampProvider(false, false))
                .setStats(stats())
                .setMessageProperties(messageProperties).setCompletionHandler(completionHandler()).setRoutingKeyCacheSize(0)
                .setRandomStartDelayInSeconds(-1)
                .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
        );
    }

    BasicProperties props() {
        return propertiesCaptor.getValue();
    }

    private Stats stats() {
        return new Stats(1000) {

            @Override
            protected void report(long now) {

            }
        };
    }

    private MulticastSet.CompletionHandler completionHandler() {
        return new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() {
            }

            @Override
            public void countDown() {
            }
        };
    }
}
