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

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.rabbitmq.perf.Recovery.setupRecoveryProcess;

public class MulticastParams {

    private long confirm = -1;
    private int confirmTimeout = 30;
    private int consumerCount = 1;
    private int producerCount = 1;
    private int consumerChannelCount = 1;
    private int producerChannelCount = 1;
    private int consumerTxSize = 0;
    private int producerTxSize = 0;
    private int channelPrefetch = 0;
    private int consumerPrefetch = 0;
    private int minMsgSize = 0;

    private int timeLimit = 0;
    private float producerRateLimit = -1.0f;
    private float consumerRateLimit = -1.0f;
    private int producerMsgCount = 0;
    private int consumerMsgCount = 0;
    private boolean consumerSlowStart = false;

    private String exchangeName = "direct";
    private String exchangeType = "direct";
    private List<String> queueNames = new ArrayList<>();
    private String routingKey = null;
    private boolean randomRoutingKey = false;
    private boolean skipBindingQueues = false;

    private List<?> flags = new ArrayList<>();

    private int multiAckEvery = 0;
    private boolean autoAck = false;
    private boolean autoDelete = true;

    private List<String> bodyFiles = new ArrayList<>();
    private String bodyContentType = null;

    private boolean predeclared = false;
    private boolean useMillis = false;

    private Map<String, Object> queueArguments = null;

    private int consumerLatencyInMicroseconds = 0;

    private String queuePattern = null;
    private int queueSequenceFrom = -1;
    private int queueSequenceTo = -1;

    private Map<String, Object> messageProperties = null;

    private TopologyHandler topologyHandler;
    private TopologyRecording topologyRecording;

    private int heartbeatSenderThreads = -1;

    private int routingKeyCacheSize = 0;
    private boolean exclusive = false;
    private int publishingInterval = -1;
    private int producerRandomStartDelayInSeconds;
    private int producerSchedulerThreadCount = -1;
    private int consumersThreadPools = -1;
    private int shutdownTimeout = 5;

    private int serversStartUpTimeout = -1;
    private int serversUpLimit = -1;

    private List<String> publishingRates = new ArrayList<>();

    private List<String> messageSizes = new ArrayList<>();

    private boolean polling = false;

    private int pollingInterval = -1;

    private boolean nack = false;

    private boolean jsonBody = false;
    private int bodyFieldCount = 1000;
    private int bodyCount = 100;

    private Map<String, Object> consumerArguments = null;

    // for random JSON body generation
    private AtomicReference<MessageBodySource> messageBodySourceReference = new AtomicReference<>();

    public void setExchangeType(String exchangeType) {
        this.exchangeType = exchangeType;
    }

    public void setExchangeName(String exchangeName) {
        this.exchangeName = exchangeName;
    }

    public void setQueueNames(List<String> queueNames) {
        if(queueNames == null) {
            this.queueNames = new ArrayList<>();
        } else {
            this.queueNames = new ArrayList<>(queueNames);
        }
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public void setRandomRoutingKey(boolean randomRoutingKey) {
        this.randomRoutingKey = randomRoutingKey;
    }
    
    public void setSkipBindingQueues(boolean skipBindingQueues) {
    	this.skipBindingQueues = skipBindingQueues;
    }

    public void setProducerRateLimit(float producerRateLimit) {
        this.producerRateLimit = producerRateLimit;
    }

    public void setProducerCount(int producerCount) {
        this.producerCount = producerCount;
    }

    public void setProducerChannelCount(int producerChannelCount) {
        this.producerChannelCount = producerChannelCount;
    }

    public void setConsumerRateLimit(float consumerRateLimit) {
        this.consumerRateLimit = consumerRateLimit;
    }

    public void setConsumerCount(int consumerCount) {
        this.consumerCount = consumerCount;
    }

    public void setConsumerChannelCount(int consumerChannelCount) {
        this.consumerChannelCount = consumerChannelCount;
    }
    
    public void setConsumerSlowStart(boolean slowStart) {
        this.consumerSlowStart = slowStart;
    }

    public void setProducerTxSize(int producerTxSize) {
        this.producerTxSize = producerTxSize;
    }

    public void setConsumerTxSize(int consumerTxSize) {
        this.consumerTxSize = consumerTxSize;
    }

    public void setConfirm(long confirm) {
        this.confirm = confirm;
    }

    // package protected for testing
    long getConfirm() {
        return this.confirm;
    }

    public void setConfirmTimeout(int confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public void setAutoAck(boolean autoAck) {
        this.autoAck = autoAck;
    }

    public void setMultiAckEvery(int multiAckEvery) {
        this.multiAckEvery = multiAckEvery;
    }

    public void setChannelPrefetch(int channelPrefetch) {
        this.channelPrefetch = channelPrefetch;
    }

    public void setConsumerPrefetch(int consumerPrefetch) {
        this.consumerPrefetch = consumerPrefetch;
    }

    public void setMinMsgSize(int minMsgSize) {
        this.minMsgSize = minMsgSize;
    }

    public void setTimeLimit(int timeLimit) {
        this.timeLimit = timeLimit;
    }

    public void setUseMillis(boolean useMillis) {
        this.useMillis = useMillis;
    }

    public void setProducerMsgCount(int producerMsgCount) {
        this.producerMsgCount = producerMsgCount;
    }

    public void setConsumerMsgCount(int consumerMsgCount) {
        this.consumerMsgCount = consumerMsgCount;
    }

    public void setMsgCount(int msgCount) {
        setProducerMsgCount(msgCount);
        setConsumerMsgCount(msgCount);
    }

    public void setFlags(List<?> flags) {
        this.flags = flags;
    }

    public void setAutoDelete(boolean autoDelete) {
        this.autoDelete = autoDelete;
    }

    public void setPredeclared(boolean predeclared) {
        this.predeclared = predeclared;
    }

    public void setQueueArguments(Map<String, Object> queueArguments) {
        this.queueArguments = queueArguments;
    }

    public void setConsumerLatencyInMicroseconds(int consumerLatencyInMicroseconds) {
        this.consumerLatencyInMicroseconds = consumerLatencyInMicroseconds;
    }

    public void setMessageProperties(Map<String, Object> messageProperties) {
        this.messageProperties = messageProperties;
    }

    public void setConsumersThreadPools(int consumersThreadPools) {
        this.consumersThreadPools = consumersThreadPools;
    }

    public void setShutdownTimeout(int shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public void setServersStartUpTimeout(int serversStartUpTimeout) {
        this.serversStartUpTimeout = serversStartUpTimeout;
    }

    public void setServersUpLimit(int serversUpLimit) {
        this.serversUpLimit = serversUpLimit;
    }

    public void setPublishingRates(List<String> publishingRates) {
        this.publishingRates = publishingRates;
    }

    public void setConsumerArguments(Map<String, Object> consumerArguments) {
        this.consumerArguments = consumerArguments;
    }

    public int getConsumerCount() {
        return consumerCount;
    }

    public int getConsumerChannelCount() {
        return consumerChannelCount;
    }
    
    public boolean getConsumerSlowStart() {
        return consumerSlowStart;
    }

    public int getConsumerThreadCount() {
        return consumerCount * consumerChannelCount;
    }

    public int getProducerCount() {
        return producerCount;
    }

    public int getProducerChannelCount() {
        return producerChannelCount;
    }

    public int getProducerThreadCount() {
        return producerCount * producerChannelCount;
    }

    public int getMinMsgSize() {
        return minMsgSize;
    }

    public float getProducerRateLimit() {
        return producerRateLimit;
    }

    public void setBodyFiles(List<String> bodyFiles) {
        if (bodyFiles == null) {
            this.bodyFiles = new ArrayList<>();
        } else {
            this.bodyFiles = new ArrayList<>(bodyFiles);
        }
    }

    List<String> getBodyFiles() {
        return Collections.unmodifiableList(this.bodyFiles);
    }

    public void setBodyContentType(String bodyContentType) {
        this.bodyContentType = bodyContentType;
    }

    String getBodyContentType() {
        return bodyContentType;
    }

    public void setQueuePattern(String queuePattern) {
        this.queuePattern = queuePattern;
    }

    public void setQueueSequenceFrom(int queueSequenceFrom) {
        this.queueSequenceFrom = queueSequenceFrom;
    }

    public void setQueueSequenceTo(int queueSequenceTo) {
        this.queueSequenceTo = queueSequenceTo;
    }

    public void setHeartbeatSenderThreads(int heartbeatSenderThreads) {
        this.heartbeatSenderThreads = heartbeatSenderThreads;
    }

    public void setMessageSizes(List<String> messageSizes) {
        this.messageSizes = messageSizes;
    }

    public int getHeartbeatSenderThreads() {
        return heartbeatSenderThreads <= 0 ? producerCount + consumerCount : this.heartbeatSenderThreads;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public int getProducerMsgCount() {
        return producerMsgCount;
    }

    public int getConsumerMsgCount() {
        return consumerMsgCount;
    }

    public void setRoutingKeyCacheSize(int routingKeyCacheSize) {
        this.routingKeyCacheSize = routingKeyCacheSize;
    }

    public int getConsumersThreadPools() {
        return consumersThreadPools;
    }

    public int getShutdownTimeout() {
        return shutdownTimeout;
    }

    public int getServersStartUpTimeout() {
        return serversStartUpTimeout;
    }

    public int getServersUpLimit() {
        return serversUpLimit;
    }

    public List<String> getPublishingRates() {
        return publishingRates;
    }

    public List<String> getMessageSizes() {
        return messageSizes;
    }

    public void setPolling(boolean polling) {
        this.polling = polling;
    }

    public boolean isPolling() {
        return polling;
    }

    public void setPollingInterval(int pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public void setNack(boolean nack) {
        this.nack = nack;
    }

    public void setJsonBody(boolean jsonBody) {
        this.jsonBody = jsonBody;
    }

    public void setBodyFieldCount(int bodyFieldCount) {
        this.bodyFieldCount = bodyFieldCount;
    }

    public void setBodyCount(int bodyCount) {
        this.bodyCount = bodyCount;
    }

    public Producer createProducer(Connection connection, Stats stats, MulticastSet.CompletionHandler completionHandler,
                                   ValueIndicator<Float> rateIndicator, ValueIndicator<Integer> messageSizeIndicator) throws IOException {
        Channel channel = connection.createChannel(); //NOSONAR
        if (producerTxSize > 0) channel.txSelect();
        if (confirm >= 0) channel.confirmSelect();
        TopologyRecording topologyRecording = new TopologyRecording(this.isPolling());
        if (!predeclared || !exchangeExists(connection, exchangeName)) {
            channel.exchangeDeclare(exchangeName, exchangeType);
            topologyRecording.recordExchange(exchangeName, exchangeType);
        }
        MessageBodySource messageBodySource;
        TimestampProvider tsp;
        if (bodyFiles.size() > 0) {
            tsp = new TimestampProvider(useMillis, true);
            messageBodySource = new LocalFilesMessageBodySource(bodyFiles, bodyContentType);
        } else if (jsonBody) {
            tsp = new TimestampProvider(useMillis, true);
            if (messageBodySourceReference.get() == null) {
                messageBodySourceReference.set(new RandomJsonMessageBodySource(minMsgSize, bodyFieldCount, bodyCount));
            }
            messageBodySource = messageBodySourceReference.get();
        } else {
            tsp = new TimestampProvider(useMillis, false);
            messageBodySource = new TimeSequenceMessageBodySource(tsp, messageSizeIndicator);
        }

        Recovery.RecoveryProcess recoveryProcess = setupRecoveryProcess(connection, topologyRecording);

        final Producer producer = new Producer(new ProducerParameters()
            .setChannel(channel).setExchangeName(exchangeName).setId(this.topologyHandler.getRoutingKey())
            .setRandomRoutingKey(randomRoutingKey).setFlags(flags).setTxSize(producerTxSize)
            .setMsgLimit(producerMsgCount).setConfirm(confirm)
            .setConfirmTimeout(confirmTimeout).setMessageBodySource(messageBodySource).setTsp(tsp)
            .setStats(stats).setMessageProperties(messageProperties).setCompletionHandler(completionHandler)
            .setRoutingKeyCacheSize(this.routingKeyCacheSize)
            .setRandomStartDelayInSeconds(this.producerRandomStartDelayInSeconds)
            .setRecoveryProcess(recoveryProcess)
            .setRateIndicator(rateIndicator)
        );
        channel.addReturnListener(producer);
        channel.addConfirmListener(producer);
        this.topologyHandler.next();
        return producer;
    }

    public Consumer createConsumer(Connection connection, Stats stats, MulticastSet.CompletionHandler completionHandler, ExecutorService executorService) throws IOException {
        TopologyHandlerResult topologyHandlerResult = this.topologyHandler.configureQueuesForClient(connection);
        connection = topologyHandlerResult.connection;
        Channel channel = connection.createChannel(); //NOSONAR
        if (consumerTxSize > 0) channel.txSelect();
        if (consumerPrefetch > 0) channel.basicQos(consumerPrefetch);
        if (channelPrefetch > 0) channel.basicQos(channelPrefetch, true);

        boolean timestampInHeader;
        if (bodyFiles.size() > 0 || jsonBody) {
            timestampInHeader = true;
        } else {
            timestampInHeader = false;
        }
        TimestampProvider tsp = new TimestampProvider(useMillis, timestampInHeader);

        Recovery.RecoveryProcess recoveryProcess = setupRecoveryProcess(connection, topologyHandlerResult.topologyRecording);

        Consumer consumer = new Consumer(
                new ConsumerParameters()
                .setChannel(channel)
                .setId(this.topologyHandler.getRoutingKey())
                .setQueueNames(topologyHandlerResult.configuredQueues)
                .setTxSize(consumerTxSize)
                .setAutoAck(autoAck)
                .setMultiAckEvery(multiAckEvery)
                .setStats(stats)
                .setRateLimit(consumerRateLimit)
                .setMsgLimit(consumerMsgCount)
                .setConsumerLatencyInMicroSeconds(consumerLatencyInMicroseconds)
                .setTimestampProvider(tsp)
                .setCompletionHandler(completionHandler)
                .setRecoveryProcess(recoveryProcess)
                .setExecutorService(executorService)
                .setPolling(this.polling)
                .setPollingInterval(this.pollingInterval)
                .setNack(this.nack)
                .setConsumerArguments(this.consumerArguments)
        );
        this.topologyHandler.next();
        return consumer;
    }

    public List<TopologyHandlerResult> configureAllQueues(List<Connection> connection) throws IOException {
        return this.topologyHandler.configureAllQueues(connection);
    }

    public void init() {
        this.topologyRecording = new TopologyRecording(this.isPolling());
        if (this.queuePattern == null) {
            this.topologyHandler = new FixedQueuesTopologyHandler(this, this.routingKey, this.queueNames, topologyRecording);
        } else {
            this.topologyHandler = new SequenceTopologyHandler(this, this.queueSequenceFrom, this.queueSequenceTo, this.queuePattern, topologyRecording);
        }

    }

    public void resetTopologyHandler() {
        this.topologyHandler.reset();
    }

    public void deleteAutoDeleteQueuesIfNecessary(Connection connection) throws IOException, TimeoutException {
        if (this.polling) {
            try (Channel channel = connection.createChannel()) {
                for (TopologyRecording.RecordedQueue queue : this.topologyRecording.queues()) {
                    if (queue.isAutoDelete() && !queue.isExclusive()) {
                        if (Thread.interrupted()) {
                            return;
                        }
                        channel.queueDelete(queue.name());
                    }
                }
            }
        }
    }

    private static boolean exchangeExists(Connection connection, final String exchangeName) throws IOException {
        if ("".equals(exchangeName)) {
            // NB: default exchange always exists
            return true;
        } else {
            return exists(connection, ch -> ch.exchangeDeclarePassive(exchangeName));
        }
    }

    private static boolean queueExists(Connection connection, final String queueName) throws IOException {
        return queueName != null && exists(connection, ch -> ch.queueDeclarePassive(queueName));
    }

    public boolean hasLimit() {
        return this.timeLimit > 0 || this.consumerMsgCount > 0 || this.producerMsgCount > 0;
    }

    public void setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public void setPublishingInterval(int publishingIntervalInSeconds) {
        this.publishingInterval = publishingIntervalInSeconds;
    }

    public int getPublishingInterval() {
        return publishingInterval;
    }

    public void setProducerRandomStartDelayInSeconds(int producerRandomStartDelayInSeconds) {
        this.producerRandomStartDelayInSeconds = producerRandomStartDelayInSeconds;
    }

    public int getProducerRandomStartDelayInSeconds() {
        return producerRandomStartDelayInSeconds;
    }

    public int getProducerSchedulerThreadCount() {
        return producerSchedulerThreadCount;
    }

    public void setProducerSchedulerThreadCount(int producerSchedulerThreadCount) {
        this.producerSchedulerThreadCount = producerSchedulerThreadCount;
    }

    private interface Checker {
        void check(Channel ch) throws IOException;
    }

    private static boolean exists(Connection connection, Checker checker) throws IOException {
        try {
            Channel ch = connection.createChannel();
            checker.check(ch);
            ch.abort();
            return true;
        }
        catch (IOException e) {
            ShutdownSignalException sse = (ShutdownSignalException) e.getCause();
            if (!sse.isHardError()) {
                AMQP.Channel.Close closeMethod = (AMQP.Channel.Close) sse.getReason();
                if (closeMethod.getReplyCode() == AMQP.NOT_FOUND) {
                    return false;
                }
            }
            throw e;
        }
    }

    /**
     * Contract to handle the creation and configuration of resources.
     * E.g. creation of queues, binding exchange to queues.
     */
    interface TopologyHandler {

        /**
         * Get the current routing key
         * @return
         */
        String getRoutingKey();

        /**
         * Configure the queues for the current client (e.g. consumer or producer)
         * @param connection
         * @return the configured queues names (can be server-generated names), connection, and topology recording
         * @throws IOException
         */
        TopologyHandlerResult configureQueuesForClient(Connection connection) throws IOException;

        /**
         * Configure all the queues for this run
         * @param connections
         * @return the configured queues names (can be server-generated names), connection, and topology recording
         * @throws IOException
         */
        List<TopologyHandlerResult> configureAllQueues(List<Connection> connections) throws IOException;

        /**
         * Move the cursor forward.
         * Should be called when the configuration (queues and routing key)
         * is required for the next client (consumer or producer).
         */
        void next();

        /**
         * Reset the {@link TopologyHandler}.
         * Typically reset the cursor. To call e.g. when a new set of
         * clients need to configured.
         */
        void reset();

    }

    static class TopologyHandlerResult {

        /**
         * The connection to use to work against the configured resources.
         * Useful when using exclusive resources.
         */
        final Connection connection;

        final TopologyRecording topologyRecording;

        /**
         * The configured queues.
         */
        final List<String> configuredQueues;

        TopologyHandlerResult(Connection connection, List<String> configuredQueues, TopologyRecording topologyRecording) {
            this.connection = connection;
            this.configuredQueues = configuredQueues;
            this.topologyRecording = topologyRecording;
        }
    }

    /**
     * Support class that contains queue configuration.
     */
    static abstract class TopologyHandlerSupport {

        protected final MulticastParams params;
        private final ConcurrentMap<String, Connection> connectionCache = new ConcurrentHashMap<>();

        protected TopologyHandlerSupport(MulticastParams params) {
            this.params = params;
        }

        protected Connection maybeUseCachedConnection(List<String> queues, Connection connection) throws IOException {
            // if queues are exclusive, we create them for each consumer connection or re-use them
            // in case they are more consumers than queues
            Connection connectionToUse = connectionCache.putIfAbsent(queues.toString(), connection);
            if (connectionToUse == null) {
                // not a hit in the cache, we use the one passed-in
                connectionToUse = connection;
            } else {
                // hit in the cache, we used the cached one, and close the passed-in one
                // (unless the cache one and the passed-in one are the same object, which is the case
                // when using several channels for each consumer!)
                if (connection != connectionToUse) {
                    connection.close(AMQP.REPLY_SUCCESS, "Connection not used", -1);
                }
            }
            return connectionToUse;
        }

        protected TopologyHandlerResult configureQueues(Connection connection, List<String> queues, TopologyRecording topologyRecording,
                                                        Runnable afterQueueConfigurationCallback) throws IOException {
            return configureQueues(
                    Collections.singletonList(connection),
                    queues, topologyRecording,
                    afterQueueConfigurationCallback).get(0);
        }

        protected List<TopologyHandlerResult> configureQueues(List<Connection> connections, List<String> queues, TopologyRecording parentTopologyRecording,
                                                              Runnable afterQueueConfigurationCallback) throws IOException {

            class State {
                final Connection c;
                final Channel ch;
                final TopologyRecording topologyRecording;
                final List<String> generatedQueueNames = new ArrayList<>();

                State(Connection c, Channel ch, TopologyRecording topologyRecording) {
                    this.c = c;
                    this.ch = ch;
                    this.topologyRecording = topologyRecording;
                }
            }

            List<State> states = new ArrayList<>(connections.size());
            for (Connection connection : connections) {
                states.add(new State(connection, connection.createChannel(), parentTopologyRecording.child()));
            }

            State firstState = states.get(0);
            if (!params.predeclared || !exchangeExists(firstState.c, params.exchangeName)) {
                firstState.ch.exchangeDeclare(params.exchangeName, params.exchangeType);
                firstState.topologyRecording.recordExchange(params.exchangeName, params.exchangeType);
            }

            // To ensure we get at-least 1 default queue:
            // (don't declare any queues when --predeclared is passed,
            // otherwise unwanted server-named queues without consumers will pile up.
            // see https://github.com/rabbitmq/rabbitmq-perf-test/issues/25 and
            // https://github.com/rabbitmq/rabbitmq-perf-test/issues/43)
            if (!params.predeclared && queues.isEmpty()) {
                queues = Collections.singletonList("");
            }

            for (int i = 0; i < queues.size(); i++) {
                String qName = queues.get(i);
                State state = states.get(i % states.size());
                Connection connection = state.c;
                List<String> generatedQueueNames = state.generatedQueueNames;
                Channel channel = state.ch;
                TopologyRecording topologyRecording = state.topologyRecording;

                if (!params.predeclared || !queueExists(connection, qName)) {
                    boolean serverNamed = qName == null || "".equals(qName);
                    qName = channel.queueDeclare(qName,
                            params.flags.contains("persistent"),
                            params.isExclusive(),
                            params.autoDelete,
                            params.queueArguments).getQueue();
                    topologyRecording.recordQueue(
                            qName, params.flags.contains("persistent"),
                            params.isExclusive(), params.autoDelete,
                            params.queueArguments, serverNamed
                    );
                }
                generatedQueueNames.add(qName);
                // skipping binding to default exchange,
                // as it's not possible to explicitly bind to it.
                if (!"".equals(params.exchangeName) && !"amq.default".equals(params.exchangeName) && !params.skipBindingQueues) {
                    String routingKey = params.topologyHandler.getRoutingKey();
                    channel.queueBind(qName, params.exchangeName, routingKey);
                    topologyRecording.recordBinding(qName, params.exchangeName, routingKey);
                }
                afterQueueConfigurationCallback.run();
            }

            List<TopologyHandlerResult> topologyHandlerResults = new ArrayList<>(connections.size());
            for (State state : states) {
                try {
                    state.ch.close();
                    topologyHandlerResults.add(new TopologyHandlerResult(
                            state.c, state.generatedQueueNames, state.topologyRecording
                    ));
                } catch (TimeoutException e) {
                    throw new IOException(e);
                }
            }
            return topologyHandlerResults;
        }
    }

    /**
     * {@link TopologyHandler} implementation that contains a list of a queues and a fixed routing key.
     */
    static class FixedQueuesTopologyHandler extends TopologyHandlerSupport implements TopologyHandler {

        final String routingKey;

        final List<String> queueNames;

        final TopologyRecording topologyRecording;

        FixedQueuesTopologyHandler(MulticastParams params, String routingKey, List<String> queueNames, TopologyRecording topologyRecording) {
            super(params);
            if (routingKey == null) {
                this.routingKey = UUID.randomUUID().toString();
            } else {
                this.routingKey = routingKey;
            }
            this.queueNames = queueNames == null ? new ArrayList<>() : queueNames;
            this.topologyRecording = topologyRecording;
        }

        @Override
        public String getRoutingKey() {
            return routingKey;
        }

        @Override
        public TopologyHandlerResult configureQueuesForClient(Connection connection) throws IOException {
            Connection connectionToUse;
            if (this.params.isExclusive()) {
                connectionToUse = maybeUseCachedConnection(this.queueNames, connection);
            } else {
                connectionToUse = connection;
            }
            return configureQueues(connectionToUse, this.queueNames, topologyRecording, () -> {});
        }

        @Override
        public List<TopologyHandlerResult> configureAllQueues(List<Connection> connections) throws IOException {
            if (shouldConfigureQueues() && !this.params.isExclusive()) {
                return configureQueues(connections, this.queueNames, topologyRecording, () -> {});
            } else {
                return connections.stream()
                        .map(connection -> new TopologyHandlerResult(connection, new ArrayList<>(), this.topologyRecording.child()))
                        .collect(Collectors.toList());
            }
        }

        public boolean shouldConfigureQueues() {
            // if no consumer, no queue has been configured and
            // some queues are specified, we have to configure the queues and their bindings
            return this.params.consumerCount == 0 && !(queueNames.size() == 0);
        }

        @Override
        public void next() {
            // NO OP
        }

        @Override
        public void reset() {
            // NO OP
        }
    }

    /**
     * {@link TopologyHandler} meant to use a sequence of queues and routing keys.
     * E.g. <code>perf-test-1</code>, <code>perf-test-2</code>, etc.
     * The routing key has the same value as the current queue.
     */
    static class SequenceTopologyHandler extends TopologyHandlerSupport implements TopologyHandler {

        final List<String> queues;
        int index = 0;
        private final TopologyRecording topologyRecording;

        public SequenceTopologyHandler(MulticastParams params, int from, int to, String queuePattern, TopologyRecording topologyRecording) {
            super(params);
            queues = new ArrayList<>(to - from + 1);
            for (int i = from; i <= to; i++) {
                queues.add(String.format(queuePattern, i));
            }
            this.topologyRecording = topologyRecording;
        }

        @Override
        public String getRoutingKey() {
            return this.getQueueNamesForClient().get(0);
        }

        @Override
        public TopologyHandlerResult configureQueuesForClient(Connection connection) throws IOException {
            if (this.params.isExclusive()) {
                Connection connectionToUse = maybeUseCachedConnection(getQueueNamesForClient(), connection);
                return configureQueues(connectionToUse, getQueueNamesForClient(), topologyRecording, () -> {});
            } else {
                List<String> queues = getQueueNamesForClient();
                // this recording will contain the resources the consumer uses
                // this way, if its connections closes, it will try to reconnect and
                // re-create the resources it needs to work with
                // this is more resilient than counting only on the configuration connections
                // to recover all resources, as the producer/consumer connections can recover
                // before the configuration connections, and then don't see their resources
                TopologyRecording clientTopologyRecording = this.topologyRecording.subRecording(queues);
                return new TopologyHandlerResult(
                    connection,
                    queues,
                    clientTopologyRecording
                );
            }

        }

        @Override
        public List<TopologyHandlerResult> configureAllQueues(List<Connection> connections) throws IOException {
            // if queues are exclusive, we'll create them for each consumer connection
            if (this.params.isExclusive()) {
                return connections.stream()
                        .map(connection -> new TopologyHandlerResult(connection, new ArrayList<>(), this.topologyRecording.child()))
                        .collect(Collectors.toList());
            } else {
                return configureQueues(connections, getQueueNames(), this.topologyRecording, () -> this.next());
            }
        }

        protected List<String> getQueueNames() {
            return Collections.unmodifiableList(queues);
        }

        protected List<String> getQueueNamesForClient() {
            return Collections.singletonList(queues.get(index % queues.size()));
        }

        @Override
        public void next() {
            index++;
        }

        @Override
        public void reset() {
            index = 0;
        }
    }
}
