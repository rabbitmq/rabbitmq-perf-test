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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;

public class Producer extends AgentBase implements Runnable, ReturnListener,
        ConfirmListener
{

    private static final Logger LOGGER = LoggerFactory.getLogger(Producer.class);

    public static final String TIMESTAMP_HEADER = "timestamp";
    private final Channel channel;
    private final String  exchangeName;
    private final String  id;
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
        Collections.synchronizedSortedSet(new TreeSet<>());

    private final MulticastSet.CompletionHandler completionHandler;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private final Supplier<String> routingKeyGenerator;

    private final int randomStartDelay;

    private final float rateLimit;

    private final Recovery.RecoveryProcess recoveryProcess;

    public Producer(ProducerParameters parameters) {
        this.channel           = parameters.getChannel();
        this.exchangeName      = parameters.getExchangeName();
        this.id                = parameters.getId();
        this.mandatory         = parameters.getFlags().contains("mandatory");
        this.persistent        = parameters.getFlags().contains("persistent");

        Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> builderProcessor = Function.identity();
        this.txSize            = parameters.getTxSize();
        this.msgLimit          = parameters.getMsgLimit();
        this.messageBodySource = parameters.getMessageBodySource();
        if (parameters.getTsp().isTimestampInHeader()) {
            builderProcessor = builderProcessor.andThen(builder -> builder.headers(Collections.singletonMap(TIMESTAMP_HEADER, parameters.getTsp().getCurrentTime())));
        }
        if (parameters.getMessageProperties() != null && !parameters.getMessageProperties().isEmpty()) {
            builderProcessor = builderProcessorWithMessageProperties(parameters.getMessageProperties(), builderProcessor);
        }
        if (parameters.getConfirm() > 0) {
            this.confirmPool  = new Semaphore((int)parameters.getConfirm());
            this.confirmTimeout = parameters.getConfirmTimeout();
        }
        this.stats = parameters.getStats();
        this.completionHandler = parameters.getCompletionHandler();
        this.propertiesBuilderProcessor = builderProcessor;
        if (parameters.isRandomRoutingKey() || parameters.getRoutingKeyCacheSize() > 0) {
            if (parameters.getRoutingKeyCacheSize() > 0) {
                this.routingKeyGenerator = new CachingRoutingKeyGenerator(parameters.getRoutingKeyCacheSize());
            } else {
                this.routingKeyGenerator = () -> UUID.randomUUID().toString();
            }
        } else {
            this.routingKeyGenerator = () -> this.id;
        }
        this.randomStartDelay = parameters.getRandomStartDelayInSeconds();

        this.rateLimit = parameters.getRateLimit();
        this.recoveryProcess = parameters.getRecoveryProcess();
        this.recoveryProcess.init(this);
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
        if (randomStartDelay > 0) {
            int delay = new Random().nextInt(randomStartDelay) + 1;
            try {
                Thread.sleep(delay * 1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        long now;
        final long startTime;
        startTime = now = System.currentTimeMillis();
        ProducerState state = new ProducerState(this.rateLimit);
        state.setLastStatsTime(startTime);
        state.setMsgCount(0);
        try {
            while (keepGoing(state)) {
                delay(now, state);
                handlePublish(state);
                now = System.currentTimeMillis();
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Error in publisher", e);
            // failing, we don't want to block the whole process, so counting down
            countDown();
            throw e;
        }
        if (state.getMsgCount() >= msgLimit) {
            countDown();
        }
    }

    private boolean keepGoing(AgentState state) {
        return (msgLimit == 0 || state.getMsgCount() < msgLimit) && !Thread.interrupted();
    }

    public Runnable createRunnableForScheduling() {
        final AtomicBoolean initialized = new AtomicBoolean(false);
        // make the producer state thread-safe for what we use in this case
        final ProducerState state = new ProducerState(this.rateLimit) {
            final AtomicInteger messageCount = new AtomicInteger(0);
            @Override
            protected void setMsgCount(int msgCount) {
                messageCount.set(msgCount);
            }
            @Override
            public int getMsgCount() {
                return messageCount.get();
            }

            @Override
            public int incrementMessageCount() {
                return messageCount.incrementAndGet();
            }
        };
        return () -> {
            if (initialized.compareAndSet(false, true)) {
                state.setLastStatsTime(System.currentTimeMillis());
                state.setMsgCount(0);
            }
            try {
                maybeHandlePublish(state);
            } catch (RuntimeException e) {
                // failing, we don't want to block the whole process, so counting down
                countDown();
                throw e;
            }
        };
    }

    public void maybeHandlePublish(AgentState state) {
        if (keepGoing(state)) {
            handlePublish(state);
        } else {
            countDown();
        }
    }

    public void handlePublish(AgentState currentState) {
        if (!this.recoveryProcess.isRecoverying()) {
            try {
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
                dealWithWriteOperation(() -> publish(messageBodySource.create(currentState.getMsgCount())), this.recoveryProcess);

                int messageCount = currentState.incrementMessageCount();

                if (txSize != 0 && messageCount % txSize == 0) {
                    dealWithWriteOperation(() -> channel.txCommit(), this.recoveryProcess);
                }
                stats.handleSend();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException (e);
            }
        } else {
            // The connection is recovering, waiting a bit.
            // The duration is arbitrary: don't want to empty loop
            // too much and don't want to catch too late with recovery
            try {
                LOGGER.debug("Recovery in progress, sleeping for a sec");
                Thread.sleep(1000L);
            } catch (InterruptedException e) { }
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
        channel.basicPublish(exchangeName, routingKeyGenerator.get(),
                             mandatory, false,
                             propertiesBuilder.build(),
                             messageBodyAndContentType.getBody());
    }

    private void countDown() {
        if (completed.compareAndSet(false, true)) {
            completionHandler.countDown();
        }
    }

    @Override
    public void recover(TopologyRecording topologyRecording) { }

    /**
     * Not thread-safe (OK for non-scheduled Producer, as it runs inside the same thread).
     */
    private static class ProducerState implements AgentState {

        private final float rateLimit;
        private long  lastStatsTime;
        private int msgCount = 0;

        protected ProducerState(float rateLimit) {
            this.rateLimit = rateLimit;
        }

        public float getRateLimit() {
            return rateLimit;
        }

        public long getLastStatsTime() {
            return lastStatsTime;
        }

        protected void setLastStatsTime(long lastStatsTime) {
            this.lastStatsTime = lastStatsTime;
        }

        public int getMsgCount() {
            return msgCount;
        }

        protected void setMsgCount(int msgCount) {
            this.msgCount = msgCount;
        }

        public int incrementMessageCount() {
            return ++this.msgCount;
        }

    }

    static class CachingRoutingKeyGenerator implements Supplier<String> {

        private final String [] keys;
        private int count = 0;

        public CachingRoutingKeyGenerator(int cacheSize) {
            if (cacheSize <= 0) {
                throw new IllegalArgumentException(String.valueOf(cacheSize));
            }
            this.keys = new String[cacheSize];
            for (int i = 0; i < cacheSize; i++) {
                this.keys[i] = UUID.randomUUID().toString();
            }
        }

        @Override
        public String get() {
            if (count == keys.length) {
                count = 0;
            }
            return keys[count++ % keys.length];
        }
    }
}
