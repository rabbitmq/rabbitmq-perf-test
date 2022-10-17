// Copyright (c) 2007-2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import static com.rabbitmq.perf.RateLimiterUtils.*;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.*;
import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.rabbitmq.perf.PerfTest.EXIT_WHEN;
import com.rabbitmq.perf.StartListener.Type;
import com.rabbitmq.perf.TopologyRecording.RecordedQueue;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
            (ch, envelope, multiple, requeue) -> ch.basicAck(envelope.getDeliveryTag(), multiple);

    private static final AckNackOperation NACK_OPERATION =
            (ch, envelope, multiple, requeue) -> ch.basicNack(envelope.getDeliveryTag(), multiple, requeue);
    static final String STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT = "Consumer reached message limit";
    static final String STOP_REASON_CONSUMER_IDLE = "Consumer is idle for more than 1 second";
    static final String STOP_REASON_CONSUMER_QUEUE_EMPTY = "Consumer queue(s) empty";

    private volatile ConsumerImpl       q;
    private final Channel               channel;
    private final String                id;
    private final int                   txSize;
    private final boolean               autoAck;
    private final int                   multiAckEvery;
    private final boolean               requeue;
    private final PerformanceMetrics    performanceMetrics;
    private final int                   msgLimit;
    private final Map<String, String>   consumerTagBranchMap = Collections.synchronizedMap(new HashMap<>());
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

    private final Map<String, Object> consumerArguments;

    private final EXIT_WHEN exitWhen;

    private volatile long lastDeliveryTag, lastAckedDeliveryTag;

    private final ScheduledExecutorService topologyRecoveryScheduledExecutorService;

    private final AtomicLong epochMessageCount = new AtomicLong(0);

    private final Runnable rateLimiterCallback;
    private final boolean rateLimitation;

    public Consumer(ConsumerParameters parameters) {
        super(parameters.getStartListener());
        this.channel           = parameters.getChannel();
        this.id                = parameters.getId();
        this.txSize            = parameters.getTxSize();
        this.autoAck           = parameters.isAutoAck();
        this.multiAckEvery     = parameters.getMultiAckEvery();
        this.requeue           = parameters.isRequeue();
        this.performanceMetrics = parameters.getPerformanceMetrics();
        this.msgLimit          = parameters.getMsgLimit();
        this.timestampProvider = parameters.getTimestampProvider();
        this.completionHandler = parameters.getCompletionHandler();
        this.executorService   = parameters.getExecutorService();
        this.polling           = parameters.isPolling();
        this.pollingInterval   = parameters.getPollingInterval();
        this.consumerArguments = parameters.getConsumerArguments();
        this.exitWhen          = parameters.getExitWhen();
        this.topologyRecoveryScheduledExecutorService = parameters.getTopologyRecoveryScheduledExecutorService();

        this.queueNames.set(new ArrayList<>(parameters.getQueueNames()));
        this.initialQueueNames = new ArrayList<>(parameters.getQueueNames());

        if(parameters.getConsumerLatenciesIndicator().isVariable()) {
            this.consumerLatency = new VariableConsumerLatency(parameters.getConsumerLatenciesIndicator());
        } else {
            long consumerLatencyInMicroSeconds = parameters.getConsumerLatenciesIndicator().getValue();
            if (consumerLatencyInMicroSeconds <= 0) {
                this.consumerLatency = new NoWaitConsumerLatency();
            } else if (consumerLatencyInMicroSeconds >= 1000) {
                this.consumerLatency = new ThreadSleepConsumerLatency(parameters.getConsumerLatenciesIndicator());
            } else {
                this.consumerLatency = new BusyWaitConsumerLatency(parameters.getConsumerLatenciesIndicator());
            }
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


        this.rateLimitation = parameters.getRateLimit() > 0;
        if (this.rateLimitation) {
            RateLimiter rateLimiter = RateLimiter.create(parameters.getRateLimit());
            this.rateLimiterCallback = () -> rateLimiter.acquire(1);
        } else {
            this.rateLimiterCallback = () -> { };
        }
        this.state = new ConsumerState(timestampProvider);
        this.recoveryProcess = parameters.getRecoveryProcess();
        this.recoveryProcess.init(this);
    }

    @Override
    protected Type type() {
        return Type.CONSUMER;
    }

    public void run() {
        epochMessageCount.set(0);
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
            started();
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
        started();
        try {
            q = new ConsumerImpl(channel);
            for (String qName : queueNames.get()) {
                String tag = channel.basicConsume(qName, autoAck, this.consumerArguments, q);
                consumerTagBranchMap.put(tag, qName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ShutdownSignalException e) {
            throw new RuntimeException(e);
        }
    }

    private class ConsumerImpl extends DefaultConsumer {

        private final AtomicLong receivedMessageCount = new AtomicLong(0);

        private ConsumerImpl(Channel channel) {
            super(channel);
            state.setLastStatsTime(System.nanoTime());
            state.setMsgCount(0);
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
            this.handleMessage(envelope, properties, body, channel);
        }

        void handleMessage(Envelope envelope, BasicProperties properties, byte[] body, Channel ch) throws IOException {
            receivedMessageCount.incrementAndGet();
            epochMessageCount.incrementAndGet();
            state.incrementMessageCount();
            long nowTimestamp = timestampProvider.getCurrentTime();
            state.setLastActivityTimestamp(nowTimestamp);
            if (msgLimit == 0 || receivedMessageCount.get() <= msgLimit) {
                long messageTimestamp = timestampExtractor.apply(properties, body);
                long diff_time = timestampProvider.getDifference(nowTimestamp, messageTimestamp);

                performanceMetrics.received(id.equals(envelope.getRoutingKey()) ? diff_time : 0L);

                if (consumerLatency.simulateLatency()) {
                    ackIfNecessary(envelope, epochMessageCount.get(), ch);
                    commitTransactionIfNecessary(epochMessageCount.get(), ch);
                    lastDeliveryTag = envelope.getDeliveryTag();

                    long now = System.nanoTime();
                    if (rateLimitation) {
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
                        rateLimiterCallback.run();
                    }
                }
            }
            if (msgLimit != 0 && receivedMessageCount.get() >= msgLimit) { // NB: not quite the inverse of above
                countDown(STOP_REASON_CONSUMER_REACHED_MESSAGE_LIMIT);
            }
        }

        private void ackIfNecessary(Envelope envelope, long currentMessageCount, final Channel ch) throws IOException {
            if (ackEnabled()) {
                dealWithWriteOperation(() -> {
                    if (multiAckEvery == 0) {
                        ackNackOperation.apply(ch, envelope, false, requeue);
                        lastAckedDeliveryTag = envelope.getDeliveryTag();
                    } else if (currentMessageCount % multiAckEvery == 0) {
                        ackNackOperation.apply(ch, envelope, true, requeue);
                        lastAckedDeliveryTag = envelope.getDeliveryTag();
                    }
                }, recoveryProcess);
            }
        }

        private void commitTransactionIfNecessary(long currentMessageCount, final Channel ch) throws IOException {
            if (transactionEnabled() && currentMessageCount % txSize == 0) {
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
                countDown("Consumer shut down");
            }
        }

        @Override
        public void handleCancel(String consumerTag) throws IOException {
            System.out.printf("Consumer cancelled by broker for tag: %s\n", consumerTag);
            epochMessageCount.set(0);
            if (consumerTagBranchMap.containsKey(consumerTag)) {
                String qName = consumerTagBranchMap.get(consumerTag);
                TopologyRecording topologyRecording = topologyRecording();
                RecordedQueue queueRecord = topologyRecording.queue(qName);
                consumeOrScheduleConsume(queueRecord, topologyRecording, consumerTag, qName);
            } else {
                System.out.printf("Could not find queue for consumer tag: %s\n", consumerTag);
            }
        }
    }

    private boolean ackEnabled() {
        return !autoAck;
    }

    private boolean transactionEnabled() {
        return txSize != 0;
    }

    private void countDown(String reason) {
        if (completed.compareAndSet(false, true)) {
            completionHandler.countDown(reason);
        }
    }

    @Override
    public void recover(TopologyRecording topologyRecording) {
        epochMessageCount.set(0);
        if (this.polling) {
            // we get the "latest" names of the queue (useful only when there are server-generated name for recovered queues)
            List<String> queues = new ArrayList<>(this.initialQueueNames.size());
            for (String queue : initialQueueNames) {
                queues.add(queueName(topologyRecording, queue));
            }
            this.queueNames.set(queues);
            this.queueNamesVersion.incrementAndGet();
        } else {
            for (Map.Entry<String, String> entry : consumerTagBranchMap.entrySet()) {
                String queueName = queueName(topologyRecording, entry.getValue());
                String consumerTag = entry.getKey();
                LOGGER.debug("Recovering consumer, starting consuming on {}", queueName);
                try {
                    TopologyRecording.RecordedQueue queueRecord = topologyRecording.queue(entry.getValue());
                    consumeOrScheduleConsume(queueRecord, topologyRecording, consumerTag, queueName);
                } catch (Exception e) {
                    LOGGER.warn(
                            "Error while recovering consumer {} on queue {} on connection {}",
                            entry.getKey(), queueName, channel.getConnection().getClientProvidedName(), e
                    );
                }
            }
        }
    }

    private void consumeOrScheduleConsume(RecordedQueue queueRecord,
                                          TopologyRecording topologyRecording,
                                          String consumerTag,
                                          String queueName) throws IOException {
        LOGGER.debug("Checking if queue {} exists before subscribing", queueName);
        boolean queueExists = Utils.exists(channel.getConnection(), ch -> ch.queueDeclarePassive(queueName));
        if (queueMayBeDown(queueRecord, topologyRecording)) {
            // If the queue is on a cluster node that is down, basic.consume will fail with a 404.
            // This will close the channel and we can't afford it, so we check if the queue exists with a different channel,
            // and postpone the subscription if the queue does not exist
            if (queueExists) {
                LOGGER.debug("Queue {} does exist, subscribing", queueName);
                channel.basicConsume(queueName, autoAck, consumerTag, false, false, this.consumerArguments, q);
            } else {
                LOGGER.debug("Queue {} does not exist, it is likely unavailable, trying to re-create it though, "
                    + "and scheduling subscription.", queueName);
                topologyRecording.recoverQueueAndBindings(channel.getConnection(), queueRecord);
                Duration schedulingPeriod = Duration.ofSeconds(5);
                int maxRetry = (int) (Duration.ofMinutes(10).getSeconds() / schedulingPeriod.getSeconds());
                AtomicInteger retryCount = new AtomicInteger(0);
                AtomicReference<Callable<Void>> resubscriptionReference = new AtomicReference<>();
                Callable<Void> resubscription = () -> {
                    LOGGER.debug("Scheduled re-subscription for {}...", queueName);
                    if (Utils.exists(channel.getConnection(), ch -> ch.queueDeclarePassive(queueName))) {
                        LOGGER.debug("Queue {} exists, re-subscribing", queueName);
                        channel.basicConsume(queueName, autoAck, consumerTag, false, false, consumerArguments, q);
                    } else if (retryCount.incrementAndGet() <= maxRetry){
                        LOGGER.debug("Queue {} does not exist, scheduling re-subscription", queueName);
                        this.topologyRecoveryScheduledExecutorService.schedule(
                            resubscriptionReference.get(), schedulingPeriod.getSeconds(), TimeUnit.SECONDS
                        );
                    } else {
                       LOGGER.debug("Max subscription retry count reached {} for queue {}",
                           retryCount.get(), queueName);
                    }
                    return null;
                };
                resubscriptionReference.set(resubscription);
                this.topologyRecoveryScheduledExecutorService.schedule(
                    resubscription, schedulingPeriod.getSeconds(), TimeUnit.SECONDS
                );
            }
        } else {
            if (!queueExists) {
                // the queue seems to have been deleted, re-creating it with its bindings
                LOGGER.debug(
                    "Queue {} does not exist, trying to re-create it before re-subscribing",
                    queueName);
                topologyRecording.recoverQueueAndBindings(channel.getConnection(), queueRecord);
            }
            channel.basicConsume(queueRecord == null ? queueName : queueRecord.name(), autoAck,
                consumerTag, false, false, this.consumerArguments, q);
        }
    }

    private static boolean queueMayBeDown(RecordedQueue queueRecord, TopologyRecording topologyRecording) {
        return queueRecord != null
            && queueRecord.isClassic() && queueRecord.isDurable() && topologyRecording.isCluster();
    }

    void maybeStopIfNoActivityOrQueueEmpty() {
        LOGGER.debug("Checking consumer activity");
        if (this.exitWhen == EXIT_WHEN.NEVER) {
            return;
        }
        TimestampProvider tp = state.getTimestampProvider();
        long lastActivityTimestamp = state.getLastActivityTimestamp();
        if (lastActivityTimestamp == -1) {
            // this avoids not terminating a consumer that never consumes
            state.setLastActivityTimestamp(tp.getCurrentTime());
            return;
        }
        Duration idleDuration = tp.difference(tp.getCurrentTime(), lastActivityTimestamp);
        if (idleDuration.toMillis() > 1000) {
            LOGGER.debug("Consumer idle for {}", idleDuration);
            List<String> queues = queueNames.get();
            if (this.exitWhen == EXIT_WHEN.IDLE) {
                maybeAckCommitBeforeExit();
                LOGGER.debug("Terminating consumer {} because of inactivity", this);
                countDown(STOP_REASON_CONSUMER_IDLE);
            } else if (this.exitWhen == EXIT_WHEN.EMPTY){
                LOGGER.debug("Checking content of consumer queue(s)");
                boolean empty = false;
                for (String queue : queues) {
                    try {
                        DeclareOk declareOk = this.channel.queueDeclarePassive(queue);
                        LOGGER.debug("Message count for queue {}: {}", queue, declareOk.getMessageCount());
                        if (declareOk.getMessageCount() == 0) {
                            empty = true;
                        }
                    } catch (IOException e) {
                        LOGGER.info("Error when calling queue.declarePassive({}) in consumer {}", queue, this);
                    }
                }
                if (empty) {
                    maybeAckCommitBeforeExit();
                    LOGGER.debug("Terminating consumer {} because its queue(s) is (are) empty", this);
                    countDown(STOP_REASON_CONSUMER_QUEUE_EMPTY);
                }
            }
        }
    }

    private void maybeAckCommitBeforeExit() {
        if (ackEnabled() && lastAckedDeliveryTag < lastDeliveryTag) {
            LOGGER.debug("Acking/committing before exit");
            try {
                dealWithWriteOperation(() -> {
                    this.channel.basicAck(lastDeliveryTag, true);
                    if (transactionEnabled()) {
                        this.channel.txCommit();
                    }
                }, recoveryProcess);
            } catch (IOException e) {
                LOGGER.warn("Error while acking/committing on exit: {}", e.getMessage());
            }
        }
    }

    private static String queueName(TopologyRecording recording, String queue) {
        TopologyRecording.RecordedQueue queueRecord = recording.queue(queue);
        // The recording is missing when using pre-declared, so just using the initial name.
        // This is a decent fallback as the record is useful only to have the new name
        // of the queue after recovery (for server-generated queue names).
        // Queue names are supposed to be stable when using pre-declared.
        return queueRecord == null ? queue : queueRecord.name();
    }

    private static class ConsumerState implements AgentState {

        private volatile long  lastStatsTime;
        private volatile long lastActivityTimestamp = -1;
        private final AtomicInteger msgCount = new AtomicInteger(0);
        private final TimestampProvider timestampProvider;

        protected ConsumerState(TimestampProvider timestampProvider) {
            this.timestampProvider = timestampProvider;
        }

        public long getLastStatsTime() {
            return lastStatsTime;
        }

        protected void setLastStatsTime(long lastStatsTime) {
            this.lastStatsTime = lastStatsTime;
        }

        public void setLastActivityTimestamp(long lastActivityTimestamp) {
            this.lastActivityTimestamp = lastActivityTimestamp;
        }

        public long getLastActivityTimestamp() {
            return lastActivityTimestamp;
        }

        public int getMsgCount() {
            return msgCount.get();
        }

        public TimestampProvider getTimestampProvider() {
            return timestampProvider;
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

    private static boolean latencySleep(long delay) {
        try {
            long ms = delay / 1000;
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean latencyBusyWait(long delay) {
        delay = delay * 1000;
        long start = System.nanoTime();
        while (System.nanoTime() - start < delay) ;
        return true;
    }

    private static class VariableConsumerLatency implements ConsumerLatency {

        private final ValueIndicator<Long> consumerLatenciesIndicator;

        private VariableConsumerLatency(ValueIndicator<Long> consumerLatenciesIndicator) {
            this.consumerLatenciesIndicator = consumerLatenciesIndicator;
        }

        @Override
        public boolean simulateLatency() {
            long consumerLatencyInMicroSeconds = consumerLatenciesIndicator.getValue();
            if (consumerLatencyInMicroSeconds <= 0) {
                return true;
            } else if (consumerLatencyInMicroSeconds >= 1000) {
                return latencySleep(consumerLatencyInMicroSeconds);
            } else {
                return latencyBusyWait(consumerLatencyInMicroSeconds);
            }
        }

    }

    private static class NoWaitConsumerLatency implements ConsumerLatency {

        @Override
        public boolean simulateLatency() {
            return true;
        }

    }

    private static class ThreadSleepConsumerLatency implements ConsumerLatency {

        private final ValueIndicator<Long> consumerLatenciesIndicator;

        private ThreadSleepConsumerLatency(ValueIndicator<Long> consumerLatenciesIndicator) {
            this.consumerLatenciesIndicator = consumerLatenciesIndicator;
        }

        @Override
        public boolean simulateLatency() {
            return latencySleep(consumerLatenciesIndicator.getValue());
        }
    }

    // from https://stackoverflow.com/a/11499351
    private static class BusyWaitConsumerLatency implements ConsumerLatency {

        private final ValueIndicator<Long> consumerLatenciesIndicator;

        private BusyWaitConsumerLatency(ValueIndicator<Long> consumerLatenciesIndicator) {
            this.consumerLatenciesIndicator = consumerLatenciesIndicator;
        }

        @Override
        public boolean simulateLatency() {
            return latencyBusyWait(consumerLatenciesIndicator.getValue());
        }
    }

    @FunctionalInterface
    private interface AckNackOperation {

        void apply(Channel channel, Envelope envelope, boolean multiple, boolean requeue) throws IOException;

    }

}
