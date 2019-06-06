// Copyright (c) 2007-2019 Pivotal Software, Inc.  All rights reserved.
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
import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

public class Consumer extends AgentBase implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Consumer.class);

    private static final AckNackOperation ACK_OPERATION =
            (ch, envelope, multiple) -> ch.basicAck(envelope.getDeliveryTag(), multiple);

    private static final AckNackOperation NACK_OPERATION =
            (ch, envelope, multiple) -> ch.basicNack(envelope.getDeliveryTag(), multiple, true);

    private volatile ConsumerImpl       q;
    private final Channel               channel;
    private final String                id;
    private final int                   txSize;
    private final boolean               autoAck;
    private final int                   multiAckEvery;
    private final Stats                 stats;
    private final int                   msgLimit;
    private final Map<String, String>   consumerTagBranchMap = Collections.synchronizedMap(new HashMap<String, String>());
    private final ConsumerLatency       consumerLatency;
    private final BiFunction<BasicProperties, byte[], Long> timestampExtractor;
    private final TimestampProvider     timestampProvider;
    private final MulticastSet.CompletionHandler completionHandler;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private final AtomicReference<List<String>> queueNames = new AtomicReference<>();
    private final AtomicLong queueNamesVersion = new AtomicLong(0);
    private final List<String> initialQueueNames; // to keep original names of server-generated queue names (after recovery)

    private final ConsumerState state;

    private final Recovery.RecoveryProcess recoveryProcess;

    private final ExecutorService executorService;

    private final boolean polling;

    private final int pollingInterval;

    private final AckNackOperation ackNackOperation;

    public Consumer(ConsumerParameters parameters) {
        this.channel           = parameters.getChannel();
        this.id                = parameters.getId();
        this.txSize            = parameters.getTxSize();
        this.autoAck           = parameters.isAutoAck();
        this.multiAckEvery     = parameters.getMultiAckEvery();
        this.stats             = parameters.getStats();
        this.msgLimit          = parameters.getMsgLimit();
        this.timestampProvider = parameters.getTimestampProvider();
        this.completionHandler = parameters.getCompletionHandler();
        this.executorService   = parameters.getExecutorService();
        this.polling           = parameters.isPolling();
        this.pollingInterval   = parameters.getPollingInterval();

        this.queueNames.set(new ArrayList<>(parameters.getQueueNames()));
        this.initialQueueNames = new ArrayList<>(parameters.getQueueNames());

        int consumerLatencyInMicroSeconds = parameters.getConsumerLatencyInMicroSeconds();
        if (consumerLatencyInMicroSeconds <= 0) {
            this.consumerLatency = new NoWaitConsumerLatency();
        } else if (consumerLatencyInMicroSeconds >= 1000) {
            this.consumerLatency = new ThreadSleepConsumerLatency(consumerLatencyInMicroSeconds / 1000);
        } else {
            this.consumerLatency = new BusyWaitConsumerLatency(consumerLatencyInMicroSeconds * 1000);
        }

        if (timestampProvider.isTimestampInHeader()) {
            this.timestampExtractor = (properties, body) -> {
                    Object timestamp = properties.getHeaders().get(Producer.TIMESTAMP_HEADER);
                    return timestamp == null ? Long.MAX_VALUE : (Long) timestamp;
            };
        } else {
            this.timestampExtractor = (properties, body) -> {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(body));
                try {
                    d.readInt(); // read sequence number
                    return d.readLong();
                } catch (IOException e) {
                    throw new RuntimeException("Error while extracting timestamp from body");
                }

            };
        }

        if (parameters.isNack()) {
            this.ackNackOperation = NACK_OPERATION;
        } else {
            this.ackNackOperation = ACK_OPERATION;
        }


        this.state = new ConsumerState(parameters.getRateLimit());
        this.recoveryProcess = parameters.getRecoveryProcess();
        this.recoveryProcess.init(this);
    }

    public void run() {
        if (this.polling) {
            startBasicGetConsumer();
        } else {
            registerAsynchronousConsumer();
        }
    }

    private void startBasicGetConsumer() {
        this.executorService.execute(() -> {
            ConsumerImpl delegate = new ConsumerImpl(channel);
            final boolean shouldPause = this.pollingInterval > 0;
            long queueNamesVersion = this.queueNamesVersion.get();
            List<String> queues = this.queueNames.get();
            Channel ch = this.channel;
            Connection connection = this.channel.getConnection();
            while (!completed.get() && !Thread.interrupted()) {
                // queue name can change between recoveries, we refresh only if necessary
                if (queueNamesVersion != this.queueNamesVersion.get()) {
                    queues = this.queueNames.get();
                    queueNamesVersion = this.queueNamesVersion.get();
                }
                for (String queue : queues) {
                    if (!this.recoveryProcess.isRecoverying()) {
                        try {
                            GetResponse response = ch.basicGet(queue, autoAck);
                            if (response != null) {
                                delegate.handleMessage(response.getEnvelope(), response.getProps(), response.getBody(), ch);
                            }
                        } catch (IOException e) {
                            LOGGER.debug("Basic.get error on queue {}: {}", queue, e.getMessage());
                            try {
                                ch = connection.createChannel();
                            } catch (Exception ex) {
                                LOGGER.debug("Error while trying to create a channel: {}", queue, e.getMessage());
                            }
                        } catch (AlreadyClosedException e) {
                            LOGGER.debug("Tried to basic.get from a closed connection");
                        }
                        if (shouldPause) {
                            try {
                                Thread.sleep(this.pollingInterval);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else {
                        // The connection is recovering, waiting a bit.
                        // The duration is arbitrary: don't want to empty loop
                        // too much and don't want to catch too late with recovery
                        try {
                            LOGGER.debug("Recovery in progress, sleeping for a sec");
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
    }

    private void registerAsynchronousConsumer() {
        try {
            q = new ConsumerImpl(channel);
            for (String qName : queueNames.get()) {
                String tag = channel.basicConsume(qName, autoAck, q);
                consumerTagBranchMap.put(tag, qName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ShutdownSignalException e) {
            throw new RuntimeException(e);
        }
    }

    private class ConsumerImpl extends DefaultConsumer {

        private final boolean rateLimitation;

        private ConsumerImpl(Channel channel) {
            super(channel);
            state.setLastStatsTime(System.currentTimeMillis());
            state.setMsgCount(0);
            this.rateLimitation = state.getRateLimit() > 0.0f;
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
            this.handleMessage(envelope, properties, body, channel);
        }

        void handleMessage(Envelope envelope, BasicProperties properties, byte[] body, Channel ch) throws IOException {
            int currentMessageCount = state.incrementMessageCount();
            if (msgLimit == 0 || currentMessageCount <= msgLimit) {
                long messageTimestamp = timestampExtractor.apply(properties, body);
                long nowTimestamp = timestampProvider.getCurrentTime();
                long diff_time = timestampProvider.getDifference(nowTimestamp, messageTimestamp);

                stats.handleRecv(id.equals(envelope.getRoutingKey()) ? diff_time : 0L);

                if (consumerLatency.simulateLatency()) {
                    ackIfNecessary(envelope, currentMessageCount, ch);
                    commitTransactionIfNecessary(currentMessageCount, ch);

                    long now = System.currentTimeMillis();
                    if (this.rateLimitation) {
                        // if rate is limited, we need to reset stats every second
                        // otherwise pausing to throttle rate will be based on the whole history
                        // which is broken when rate varies
                        // as consumer does not choose the rate at which messages arrive,
                        // we can consider the rate is always subject to change,
                        // so we'd better off always resetting the stats
                        if (now - state.getLastStatsTime() > 1000) {
                            state.setLastStatsTime(now);
                            state.setMsgCount(0);
                        }
                        delay(now, state);
                    }
                }
            }
            if (msgLimit != 0 && currentMessageCount >= msgLimit) { // NB: not quite the inverse of above
                countDown();
            }
        }

        private void ackIfNecessary(Envelope envelope, int currentMessageCount, final Channel ch) throws IOException {
            if (!autoAck) {
                dealWithWriteOperation(() -> {
                    if (multiAckEvery == 0) {
                        ackNackOperation.apply(ch, envelope, false);
                    } else if (currentMessageCount % multiAckEvery == 0) {
                        ackNackOperation.apply(ch, envelope, true);
                    }
                }, recoveryProcess);
            }
        }

        private void commitTransactionIfNecessary(int currentMessageCount, final Channel ch) throws IOException {
            if (txSize != 0 && currentMessageCount % txSize == 0) {
                dealWithWriteOperation(() -> ch.txCommit(), recoveryProcess);
            }
        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
            LOGGER.debug(
                "Consumer received shutdown signal, recovery process enabled? {}, condition to trigger connection recovery? {}",
                recoveryProcess.isEnabled(), isConnectionRecoveryTriggered(sig)
            );
            if (!recoveryProcess.isEnabled()) {
                LOGGER.debug("Counting down for consumer");
                countDown();
            }
        }

        @Override
        public void handleCancel(String consumerTag) throws IOException {
            System.out.printf("Consumer cancelled by broker for tag: %s", consumerTag);
            if (consumerTagBranchMap.containsKey(consumerTag)) {
                String qName = consumerTagBranchMap.get(consumerTag);
                System.out.printf("Re-consuming. Queue: %s for Tag: %s", qName, consumerTag);
                channel.basicConsume(qName, autoAck, q);
            } else {
                System.out.printf("Could not find queue for consumer tag: %s", consumerTag);
            }
        }
    }

    private void countDown() {
        if (completed.compareAndSet(false, true)) {
            completionHandler.countDown();
        }
    }

    @Override
    public void recover(TopologyRecording topologyRecording) {
        if (this.polling) {
            // we get the "latest" names of the queue (useful only when there are server-generated name for recovered queues)
            List<String> queues = new ArrayList<>(this.initialQueueNames.size());
            for (String queue : initialQueueNames) {
                queues.add(topologyRecording.queue(queue).name());
            }
            this.queueNames.set(queues);
            this.queueNamesVersion.incrementAndGet();
        } else {
            for (Map.Entry<String, String> entry : consumerTagBranchMap.entrySet()) {
                TopologyRecording.RecordedQueue queue = topologyRecording.queue(entry.getValue());
                try {
                    channel.basicConsume(queue.name(), autoAck, entry.getKey(), q);
                } catch (IOException e) {
                    LOGGER.warn(
                            "Error while recovering consumer {} on queue {} on connection {}",
                            entry.getKey(), queue.name(), channel.getConnection().getClientProvidedName(), e
                    );
                }
            }
        }
    }

    private static class ConsumerState implements AgentState {

        private final float rateLimit;
        private volatile long  lastStatsTime;
        private final AtomicInteger msgCount = new AtomicInteger(0);

        protected ConsumerState(float rateLimit) {
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
            return msgCount.get();
        }

        protected void setMsgCount(int msgCount) {
            this.msgCount.set(msgCount);
        }

        public int incrementMessageCount() {
            return this.msgCount.incrementAndGet();
        }

    }

    private interface ConsumerLatency {

        /**
         *
         * @return true if normal completion, false if not
         */
        boolean simulateLatency();

    }

    private static class NoWaitConsumerLatency implements ConsumerLatency {

        @Override
        public boolean simulateLatency() {
            return true;
        }

    }

    private static class ThreadSleepConsumerLatency implements ConsumerLatency {

        private final int waitTime;

        private ThreadSleepConsumerLatency(int waitTime) {
            this.waitTime = waitTime;
        }

        @Override
        public boolean simulateLatency() {
            try {
                Thread.sleep(waitTime);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // from https://stackoverflow.com/a/11499351
    private static class BusyWaitConsumerLatency implements ConsumerLatency {

        private final long delay;

        private BusyWaitConsumerLatency(long delay) {
            this.delay = delay;
        }

        @Override
        public boolean simulateLatency() {
            long start = System.nanoTime();
            while(System.nanoTime() - start < delay);
            return true;
        }
    }

    @FunctionalInterface
    private interface AckNackOperation {

        void apply(Channel channel, Envelope envelope, boolean multiple) throws IOException;

    }

}
