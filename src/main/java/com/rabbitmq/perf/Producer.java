// Copyright (c) 2007-Present Pivotal Software, Inc.  All rights reserved.
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

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.ReturnListener;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import static java.util.stream.Collectors.toMap;

public class Producer extends ProducerConsumerBase implements Runnable, ReturnListener,
        ConfirmListener
{

    public static final String TIMESTAMP_HEADER = "timestamp";
    private final Channel channel;
    private final String  exchangeName;
    private final String  id;
    private final boolean randomRoutingKey;
    private final boolean mandatory;
    private final boolean persistent;
    private final int     txSize;
    private final int     msgLimit;

    private final Stats   stats;

    private final MessageBodySource messageBodySource;

    private final Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> propertiesBuilderProcessor;
    private Semaphore confirmPool;
    private int confirmTimeout;
    private final SortedSet<Long> unconfirmedSet =
        Collections.synchronizedSortedSet(new TreeSet<Long>());

    private final MulticastSet.CompletionHandler completionHandler;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public Producer(Channel channel, String exchangeName, String id, boolean randomRoutingKey,
                    List<?> flags, int txSize,
                    float rateLimit, int msgLimit,
                    long confirm, int confirmTimeout,
                    MessageBodySource messageBodySource,
                    TimestampProvider tsp, Stats stats, Map<String, Object> messageProperties,
                    MulticastSet.CompletionHandler completionHandler) {
        this.channel           = channel;
        this.exchangeName      = exchangeName;
        this.id                = id;
        this.randomRoutingKey  = randomRoutingKey;
        this.mandatory         = flags.contains("mandatory");
        this.persistent        = flags.contains("persistent");

        Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> builderProcessor = Function.identity();
        this.txSize            = txSize;
        this.rateLimit         = rateLimit;
        this.msgLimit          = msgLimit;
        this.messageBodySource = messageBodySource;
        if (tsp.isTimestampInHeader()) {
            builderProcessor = builderProcessor.andThen(builder -> builder.headers(Collections.singletonMap(TIMESTAMP_HEADER, tsp.getCurrentTime())));
        }
        if (messageProperties != null && !messageProperties.isEmpty()) {
            builderProcessor = builderProcessorWithMessageProperties(messageProperties, builderProcessor);
        }
        if (confirm > 0) {
            this.confirmPool  = new Semaphore((int)confirm);
            this.confirmTimeout = confirmTimeout;
        }
        this.stats = stats;
        this.completionHandler = completionHandler;
        this.propertiesBuilderProcessor = builderProcessor;
    }

    private Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> builderProcessorWithMessageProperties(
            Map<String, Object> messageProperties,
            Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> builderProcessor) {
        if (messageProperties.containsKey("contentType")) {
            String value = messageProperties.get("contentType").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.contentType(value));
        }
        if (messageProperties.containsKey("contentEncoding")) {
            String value = messageProperties.get("contentEncoding").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.contentEncoding(value));
        }
        if (messageProperties.containsKey("deliveryMode")) {
            Integer value = ((Number) messageProperties.get("deliveryMode")).intValue();
            builderProcessor = builderProcessor.andThen(builder -> builder.deliveryMode(value));
        }
        if (messageProperties.containsKey("priority")) {
            Integer value = ((Number) messageProperties.get("priority")).intValue();
            builderProcessor = builderProcessor.andThen(builder -> builder.priority(value));
        }
        if (messageProperties.containsKey("correlationId")) {
            String value = messageProperties.get("correlationId").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.correlationId(value));
        }
        if (messageProperties.containsKey("replyTo")) {
            String value = messageProperties.get("replyTo").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.replyTo(value));
        }
        if (messageProperties.containsKey("expiration")) {
            String value = messageProperties.get("expiration").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.expiration(value));
        }
        if (messageProperties.containsKey("messageId")) {
            String value = messageProperties.get("messageId").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.messageId(value));
        }
        if (messageProperties.containsKey("timestamp")) {
            String value = messageProperties.get("timestamp").toString();
            Date timestamp = Date.from(OffsetDateTime.parse(value).toInstant());
            builderProcessor = builderProcessor.andThen(builder -> builder.timestamp(timestamp));
        }
        if (messageProperties.containsKey("type")) {
            String value = messageProperties.get("type").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.type(value));
        }
        if (messageProperties.containsKey("userId")) {
            String value = messageProperties.get("userId").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.userId(value));
        }
        if (messageProperties.containsKey("appId")) {
            String value = messageProperties.get("appId").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.appId(value));
        }
        if (messageProperties.containsKey("clusterId")) {
            String value = messageProperties.get("clusterId").toString();
            builderProcessor = builderProcessor.andThen(builder -> builder.clusterId(value));
        }

        final Map<String, Object> headers = messageProperties.entrySet().stream()
            .filter(entry -> !isPropertyKey(entry.getKey()))
            .collect(toMap(e -> e.getKey(), e -> e.getValue()));

        if (!headers.isEmpty()) {
            builderProcessor = builderProcessor.andThen(builder -> {
                // we merge if there are already some headers
                AMQP.BasicProperties properties = builder.build();
                Map<String, Object> existingHeaders = properties.getHeaders();
                if (existingHeaders != null && !existingHeaders.isEmpty()) {
                    Map<String, Object> newHeaders = new HashMap<>();
                    newHeaders.putAll(existingHeaders);
                    newHeaders.putAll(headers);
                    builder = builder.headers(newHeaders);
                } else {
                    builder = builder.headers(headers);
                }
                return builder;
            });
        }

        return builderProcessor;
    }

    private static final Collection<String> MESSAGE_PROPERTIES_KEYS = Arrays.asList(
        "contentType",
        "contentEncoding",
        "headers",
        "deliveryMode",
        "priority",
        "correlationId",
        "replyTo",
        "expiration",
        "messageId",
        "timestamp",
        "type",
        "userId",
        "appId",
        "clusterId"
    );

    private boolean isPropertyKey(String key) {
        return MESSAGE_PROPERTIES_KEYS.contains(key);
    }

    public void handleReturn(int replyCode,
                             String replyText,
                             String exchange,
                             String routingKey,
                             AMQP.BasicProperties properties,
                             byte[] body) {
        stats.handleReturn();
    }

    public void handleAck(long seqNo, boolean multiple) {
        handleAckNack(seqNo, multiple, false);
    }

    public void handleNack(long seqNo, boolean multiple) {
        handleAckNack(seqNo, multiple, true);
    }

    private void handleAckNack(long seqNo, boolean multiple,
                               boolean nack) {
        int numConfirms = 0;
        if (multiple) {
            SortedSet<Long> confirmed = unconfirmedSet.headSet(seqNo + 1);
            numConfirms += confirmed.size();
            confirmed.clear();
        } else {
            unconfirmedSet.remove(seqNo);
            numConfirms = 1;
        }
        if (nack) {
            stats.handleNack(numConfirms);
        } else {
            stats.handleConfirm(numConfirms);
        }

        if (confirmPool != null) {
            for (int i = 0; i < numConfirms; ++i) {
                confirmPool.release();
            }
        }

    }

    public void run() {
        long now;
        final long startTime;
        startTime = now = System.currentTimeMillis();
        lastStatsTime = startTime;
        msgCount = 0;
        int totalMsgCount = 0;

        try {

            while ((msgLimit == 0 || msgCount < msgLimit) && !Thread.interrupted()) {
                delay(now);
                if (confirmPool != null) {
                    if (confirmTimeout < 0) {
                        confirmPool.acquire();
                    } else {
                        boolean acquired = confirmPool.tryAcquire(confirmTimeout, TimeUnit.SECONDS);
                        if (!acquired) {
                            // waiting for too long, broker may be gone, stopping thread
                            throw new RuntimeException("Waiting for publisher confirms for too long");
                        }
                    }

                }
                publish(messageBodySource.create(totalMsgCount));
                totalMsgCount++;
                msgCount++;

                if (txSize != 0 && totalMsgCount % txSize == 0) {
                    channel.txCommit();
                }
                stats.handleSend();
                now = System.currentTimeMillis();
            }
            if (msgCount >= msgLimit) {
                countDown();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException (e);
        }
    }

    private void publish(MessageBodySource.MessageBodyAndContentType messageBodyAndContentType)
        throws IOException {

        AMQP.BasicProperties.Builder propertiesBuilder = new AMQP.BasicProperties.Builder();
        if (persistent) {
            propertiesBuilder.deliveryMode(2);
        }

        if (messageBodyAndContentType.getContentType() != null) {
            propertiesBuilder.contentType(messageBodyAndContentType.getContentType());
        }

        propertiesBuilder = this.propertiesBuilderProcessor.apply(propertiesBuilder);

        unconfirmedSet.add(channel.getNextPublishSeqNo());
        channel.basicPublish(exchangeName, randomRoutingKey ? UUID.randomUUID().toString() : id,
                             mandatory, false,
                             propertiesBuilder.build(),
                             messageBodyAndContentType.getBody());
    }

    private void countDown() {
        if (completed.compareAndSet(false, true)) {
            completionHandler.countDown();
        }
    }

}
