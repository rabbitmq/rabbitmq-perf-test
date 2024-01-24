// Copyright (c) 2017-2023 Broadcom. All Rights Reserved.
// The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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

import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ProducerTest {

  @Mock Channel channel;

  @Captor private ArgumentCaptor<BasicProperties> propertiesCaptor;

  @Captor private ArgumentCaptor<byte[]> bodyCaptor;

  AutoCloseable mocks;

  @BeforeEach
  public void init() {
    mocks = MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  public void tearDown() throws Exception {
    mocks.close();
  }

  @Test
  public void flagNone() throws Exception {
    flagProducer().run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getDeliveryMode()).isNull();
    assertThat(props().getPriority()).isNull();
  }

  @Test
  public void flagPersistent() throws Exception {
    flagProducer("persistent").run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getDeliveryMode()).isEqualTo(2);
    assertThat(props().getPriority()).isNull();
  }

  @Test
  public void flagMandatory() throws Exception {
    flagProducer("mandatory").run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(true),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getDeliveryMode()).isNull();
    assertThat(props().getPriority()).isNull();
  }

  @Test
  public void priority() throws Exception {
    flagProducer(singletonMap("priority", 10)).run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getDeliveryMode()).isNull();
    assertThat(props().getPriority()).isEqualTo(10);
  }

  @Test
  public void flagPersistentMandatoryPriority() throws Exception {
    flagProducer(singletonMap("priority", 10), "persistent", "mandatory").run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(true),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getDeliveryMode()).isEqualTo(2);
    assertThat(props().getPriority()).isEqualTo(10);
  }

  @Test
  public void noTimestampInHeader() throws Exception {
    flagProducer().run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getHeaders()).isNull();
  }

  @Test
  public void timestampInHeader() throws Exception {
    Producer producer =
        new Producer(
            new ProducerParameters()
                .setChannel(channel)
                .setExchangeName("exchange")
                .setRoutingKey("id")
                .setRandomRoutingKey(false)
                .setFlags(asList("persistent"))
                .setTxSize(0)
                .setMsgLimit(1)
                .setConfirm(-1)
                .setConfirmTimeout(30)
                .setMessageBodySource(
                    new TimeSequenceMessageBodySource(new TimestampProvider(true, true), 1000))
                .setTsp(new TimestampProvider(true, true))
                .setPerformanceMetrics(performanceMetrics())
                .setMessageProperties(null)
                .setCompletionHandler(completionHandler())
                .setRoutingKeyCacheSize(0)
                .setRandomStartDelayInSeconds(-1)
                .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
                .setRateIndicator(new FixedValueIndicator<>(-1.0f)));

    producer.run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getHeaders()).isNotNull();
    assertThat(props().getHeaders().get(Producer.TIMESTAMP_HEADER)).isNotNull();
  }

  @Test
  public void messagePropertiesAll() throws Exception {
    Map<String, Object> messageProperties =
        new HashMap<String, Object>() {
          {
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
          }
        };

    flagProducer(messageProperties).run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getContentType()).isEqualTo("text/plain");
    assertThat(props().getContentEncoding()).isEqualTo("UTF-8");
    assertThat(props().getDeliveryMode()).isEqualTo(2);
    assertThat(props().getPriority()).isEqualTo(10);
    assertThat(props().getCorrelationId()).isEqualTo("dummy");
    assertThat(props().getReplyTo()).isEqualTo("foo");
    assertThat(props().getExpiration()).isEqualTo("later");
    assertThat(props().getMessageId()).isEqualTo("bar");
    assertThat(props().getTimestamp())
        .isEqualTo(Date.from(OffsetDateTime.parse("2007-12-03T10:15:30+01:00").toInstant()));
    assertThat(props().getType()).isEqualTo("third");
    assertThat(props().getUserId()).isEqualTo("jdoe");
    assertThat(props().getAppId()).isEqualTo("sender");
    assertThat(props().getDeliveryMode()).isEqualTo(2);
    assertThat(props().getClusterId()).isEqualTo("rabbitmq");
  }

  @Test
  public void messagePropertiesOverrideDeliveryContentType() throws Exception {
    Map<String, Object> messageProperties =
        new HashMap<String, Object>() {
          {
            put("contentType", "text/plain");
            put("deliveryMode", 1);
          }
        };

    Producer producer =
        new Producer(
            new ProducerParameters()
                .setChannel(channel)
                .setExchangeName("exchange")
                .setRoutingKey("id")
                .setRandomRoutingKey(false)
                .setFlags(asList("persistent"))
                .setTxSize(0)
                .setMsgLimit(1)
                .setConfirm(-1)
                .setConfirmTimeout(30)
                .setMessageBodySource(
                    (sequence) ->
                        new MessageBodySource.MessageEnvelope(
                            "".getBytes(), "application/json", 0L))
                .setTsp(new TimestampProvider(true, true))
                .setPerformanceMetrics(performanceMetrics())
                .setMessageProperties(messageProperties)
                .setCompletionHandler(completionHandler())
                .setRoutingKeyCacheSize(0)
                .setRandomStartDelayInSeconds(-1)
                .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
                .setRateIndicator(new FixedValueIndicator<>(-1.0f)));

    producer.run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getContentType()).isEqualTo("text/plain");
    assertThat(props().getDeliveryMode()).isEqualTo(1);
  }

  @Test
  public void messagePropertiesAndHeaders() throws Exception {
    Map<String, Object> messageProperties =
        new HashMap<String, Object>() {
          {
            put("contentType", "text/plain");
            put("contentEncoding", "UTF-8");
            put("deliveryMode", 2);
            put("header1", "value1");
            put("header2", "value2");
          }
        };

    flagProducer(messageProperties).run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getContentType()).isEqualTo("text/plain");
    assertThat(props().getContentEncoding()).isEqualTo("UTF-8");
    assertThat(props().getDeliveryMode()).isEqualTo(2);
    assertThat(props().getHeaders()).isNotNull();
    assertThat(props().getHeaders().get("header1")).isEqualTo("value1");
    assertThat(props().getHeaders().get("header2")).isEqualTo("value2");
  }

  @Test
  public void messagePropertiesAndHeadersKeepOtherHeaders() throws Exception {
    Map<String, Object> messageProperties =
        new HashMap<String, Object>() {
          {
            put("contentType", "text/plain");
            put("contentEncoding", "UTF-8");
            put("deliveryMode", 2);
            put("header1", "value1");
            put("header2", "value2");
          }
        };

    Producer producer =
        new Producer(
            new ProducerParameters()
                .setChannel(channel)
                .setExchangeName("exchange")
                .setRoutingKey("id")
                .setRandomRoutingKey(false)
                .setFlags(asList("persistent"))
                .setTxSize(0)
                .setMsgLimit(1)
                .setConfirm(-1)
                .setConfirmTimeout(30)
                .setMessageBodySource(
                    new TimeSequenceMessageBodySource(new TimestampProvider(true, true), 1000))
                .setTsp(new TimestampProvider(true, true))
                .setPerformanceMetrics(performanceMetrics())
                .setMessageProperties(messageProperties)
                .setCompletionHandler(completionHandler())
                .setRoutingKeyCacheSize(0)
                .setRandomStartDelayInSeconds(-1)
                .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
                .setRateIndicator(new FixedValueIndicator<>(-1.0f)));

    producer.run();

    verify(channel)
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            propertiesCaptor.capture(),
            any(byte[].class));

    assertThat(props().getContentType()).isEqualTo("text/plain");
    assertThat(props().getContentEncoding()).isEqualTo("UTF-8");
    assertThat(props().getDeliveryMode()).isEqualTo(2);
    assertThat(props().getHeaders()).isNotNull();
    assertThat(props().getHeaders()).hasSize(3);
    assertThat(props().getHeaders().get("header1")).isEqualTo("value1");
    assertThat(props().getHeaders().get("header2")).isEqualTo("value2");
  }

  @Test
  public void noMessageSize() throws Exception {
    new Producer(
            parameters()
                .setMessageBodySource(
                    new TimeSequenceMessageBodySource(new TimestampProvider(false, false), 0))
                .setMsgLimit(10))
        .run();

    verify(channel, times(10))
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            any(BasicProperties.class),
            bodyCaptor.capture());

    bodyCaptor.getAllValues().forEach(body -> assertThat(body).hasSize(12));
  }

  @Test
  public void fixedMessageSize() throws Exception {
    new Producer(
            parameters()
                .setMessageBodySource(
                    new TimeSequenceMessageBodySource(new TimestampProvider(false, false), 500))
                .setMsgLimit(10))
        .run();

    verify(channel, times(10))
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            any(BasicProperties.class),
            bodyCaptor.capture());

    bodyCaptor.getAllValues().forEach(body -> assertThat(body).hasSize(500));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void variableMessageSize() throws Exception {
    ValueIndicator<Integer> valueIndicator = mock(ValueIndicator.class);

    List<Integer> sizes = asList(50, 100, 200);
    AtomicInteger callCount = new AtomicInteger(0);
    when(valueIndicator.values()).thenReturn(sizes);
    when(valueIndicator.isVariable()).thenReturn(true);
    when(valueIndicator.getValue())
        .then(invocation -> sizes.get(callCount.getAndIncrement() % sizes.size()));

    new Producer(
            parameters()
                .setMessageBodySource(
                    new TimeSequenceMessageBodySource(
                        new TimestampProvider(false, false), valueIndicator))
                .setMsgLimit(10))
        .run();

    verify(channel, times(10))
        .basicPublish(
            anyString(),
            anyString(),
            eq(false),
            eq(false),
            any(BasicProperties.class),
            bodyCaptor.capture());

    assertThat(bodyCaptor.getAllValues().stream().mapToInt(body -> body.length).distinct())
        .hasSameSizeAs(sizes)
        .containsExactlyInAnyOrder(sizes.toArray(new Integer[] {}));
  }

  Producer flagProducer(String... flags) {
    return flagProducer(null, flags);
  }

  Producer flagProducer(Map<String, Object> messageProperties, String... flags) {
    return new Producer(
        parameters().setFlags(asList(flags)).setMessageProperties(messageProperties));
  }

  @Test
  public void resetConfirmPoolAfterRecovery() throws Exception {
    Recovery.RecoveryProcess recoveryProcess = mock(Recovery.RecoveryProcess.class);
    when(recoveryProcess.isEnabled()).thenReturn(true);
    when(recoveryProcess.isRecoverying()).thenReturn(false);
    ProducerParameters parameters = parameters();
    parameters
        .setConfirm(10)
        .setConfirmTimeout(3)
        .setMsgLimit(0)
        .setRecoveryProcess(recoveryProcess);
    AtomicLong publishSequence = new AtomicLong(0);
    when(channel.getNextPublishSeqNo()).thenAnswer(invocation -> publishSequence.incrementAndGet());
    Producer producer = new Producer(parameters);

    AtomicBoolean failedWithPublisherConfirmTimeout = new AtomicBoolean(false);
    new Thread(
            () -> {
              try {
                producer.run();
              } catch (PerfTestException e) {
                failedWithPublisherConfirmTimeout.set(
                    e.getMessage().contains("publisher confirms"));
              }
            })
        .start();

    waitAtMost(5, () -> publishSequence.get() == 10);
    // 10 messages published, the producer is waiting for confirms, we send them
    producer.handleAck(publishSequence.get(), true);
    waitAtMost(5, () -> publishSequence.get() == 20);
    // 10 more messages published, the producer is waiting for confirms, we simulate recovery
    producer.recover(null);
    // the producer resets the confirm pool and moves on to publishing
    waitAtMost(5, () -> publishSequence.get() == 30);
    // 10 more messages published, the producer is waiting for confirms, we send them
    producer.handleAck(publishSequence.get(), true);
    // the producer publishes again, it will wait for confirm but they will never come
    // we wait until it reaches the confirm timeout and crashes
    waitAtMost(10, () -> failedWithPublisherConfirmTimeout.get());
  }

  ProducerParameters parameters() {
    return new ProducerParameters()
        .setChannel(channel)
        .setExchangeName("exchange")
        .setRoutingKey("id")
        .setRandomRoutingKey(false)
        .setFlags(new ArrayList<>())
        .setTxSize(0)
        .setMsgLimit(1)
        .setConfirm(-1)
        .setConfirmTimeout(30)
        .setMessageBodySource(
            new TimeSequenceMessageBodySource(new TimestampProvider(false, false), 1000))
        .setTsp(new TimestampProvider(false, false))
        .setPerformanceMetrics(performanceMetrics())
        .setCompletionHandler(completionHandler())
        .setRoutingKeyCacheSize(0)
        .setRandomStartDelayInSeconds(-1)
        .setRecoveryProcess(Recovery.NO_OP_RECOVERY_PROCESS)
        .setRateIndicator(new FixedValueIndicator<>(-1.0f));
  }

  BasicProperties props() {
    return propertiesCaptor.getValue();
  }

  private PerformanceMetrics performanceMetrics() {
    return PerformanceMetrics.NO_OP;
  }

  private MulticastSet.CompletionHandler completionHandler() {
    return new MulticastSet.CompletionHandler() {

      @Override
      public void waitForCompletion() {}

      @Override
      public void countDown(String reason) {}
    };
  }
}
