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

import static com.rabbitmq.perf.Utils.isRecoverable;
import static java.lang.Math.min;
import static java.lang.String.format;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import com.rabbitmq.perf.PerfTest.EXIT_WHEN;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MulticastSet {

    // from Java Client ConsumerWorkService
    public final static int DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(MulticastSet.class);
    private static final String PRODUCER_THREAD_PREFIX = "perf-test-producer-";
    static final String STOP_REASON_REACHED_TIME_LIMIT = "Reached time limit";
    private final Stats stats;
    private final ConnectionFactory factory;
    private final MulticastParams params;
    private final String testID;
    private final List<String> uris;
    private final CompletionHandler completionHandler;
    private final ShutdownService shutdownService;
    private ThreadingHandler threadingHandler = new DefaultThreadingHandler();
    private final ValueIndicator<Float> rateIndicator;
    private final ValueIndicator<Integer> messageSizeIndicator;
    private final ValueIndicator<Long> consumerLatencyIndicator;
    private final ConnectionCreator connectionCreator;

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, List<String> uris, CompletionHandler completionHandler) {
        this(stats, factory, params, "perftest", uris, completionHandler, new ShutdownService());
    }

    public MulticastSet(Stats stats, ConnectionFactory factory,
                        MulticastParams params, String testID, List<String> uris, CompletionHandler completionHandler) {
        this(stats, factory, params, testID, uris, completionHandler, new ShutdownService());
    }
    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, String testID, List<String> uris, CompletionHandler completionHandler, ShutdownService shutdownService) {
        this.stats = stats;
        this.factory = factory;
        this.params = params;
        this.testID = testID;
        this.uris = uris == null || uris.isEmpty() ?
                null : new CopyOnWriteArrayList<>(uris);
        this.completionHandler = completionHandler;
        this.shutdownService = shutdownService;
        this.params.init();
        if (this.params.getPublishingRates() == null || this.params.getPublishingRates().isEmpty()) {
            this.rateIndicator = new FixedValueIndicator<>(params.getProducerRateLimit());
        } else {
            ScheduledExecutorService scheduledExecutorService = this.threadingHandler.scheduledExecutorService(
                    "perf-test-variable-rate-scheduler", 1
            );
            this.rateIndicator = new VariableValueIndicator<>(
                    params.getPublishingRates(), scheduledExecutorService, input -> Float.valueOf(input)
            );
        }
        if (this.params.getMessageSizes() == null || this.params.getMessageSizes().isEmpty()) {
            this.messageSizeIndicator = new FixedValueIndicator<>(params.getMinMsgSize());
        } else {
            ScheduledExecutorService scheduledExecutorService = this.threadingHandler.scheduledExecutorService(
                    "perf-test-variable-message-size-scheduler", 1
            );
            this.messageSizeIndicator = new VariableValueIndicator<>(
                    params.getMessageSizes(), scheduledExecutorService, input -> Integer.valueOf(input)
            );
        }

        if (this.params.getConsumerLatencies() == null || this.params.getConsumerLatencies().isEmpty()) {
            this.consumerLatencyIndicator = new FixedValueIndicator<>(params.getConsumerLatencyInMicroseconds());
        } else {
            ScheduledExecutorService scheduledExecutorService = this.threadingHandler.scheduledExecutorService(
                    "perf-test-variable-consumer-latency-scheduler", 1
            );
            this.consumerLatencyIndicator = new VariableValueIndicator<>(
                    params.getConsumerLatencies(), scheduledExecutorService, input -> Long.valueOf(input)
            );
        }

        this.connectionCreator = new ConnectionCreator(this.factory, this.uris);
        if (stats.interval().toMillis() > 0) {
            this.threadingHandler.scheduledExecutorService("perf-test-stats-activity-check-", 1)
                .scheduleAtFixedRate(() -> {
                    try {
                        stats.maybeResetGauges();
                    } catch (Exception e) {
                        LOGGER.warn("Error while checking stats activity: {}", e.getMessage());
                    }
                }, stats.interval().toMillis() * 2, stats.interval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    protected static int nbThreadsForConsumer(MulticastParams params) {
        // for backward compatibility, the thread pool should be large enough to dedicate
        // one thread for each channel when channel number is <= DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE
        // Above this number, we stick to DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE
        return min(params.getConsumerChannelCount(), DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE);
    }

    protected static int nbThreadsForProducerScheduledExecutorService(MulticastParams params) {
        int producerExecutorServiceNbThreads = params.getProducerSchedulerThreadCount();
        if (producerExecutorServiceNbThreads <= 0) {
            int producerThreadCount = params.getProducerThreadCount();
            Duration publishingInterval = params.getPublishingInterval() == null ? Duration.ofSeconds(1)
                : params.getPublishingInterval();
            long publishingIntervalMs = publishingInterval.toMillis();

            double publishingIntervalSeconds = (double) publishingIntervalMs / 1000d;
            double rate = (double) producerThreadCount / publishingIntervalSeconds;
            /**
             * Why 100? This is arbitrary. We assume 1 thread is more than enough to handle
             * the publishing of 100 messages in 1 second, the fastest rate
             * being 10 messages / second when using --publishing-interval.
             * Then, a new thread is used
             * every for every 100 messages / second.
             * This is 21 threads for 1000 producers publishing 1 message / second,
             * which seems reasonable.
             * There's a command line argument to override this anyway.
             */
            int threadCount = (int) (rate / 100d) + 1;
            LOGGER.debug("Using {} thread(s) to schedule {} publisher(s) publishing every {} ms",
                threadCount, producerThreadCount, publishingInterval.toMillis()
            );
            return threadCount;
        } else {
            return producerExecutorServiceNbThreads;
        }
    }

    public void run()
        throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException, ExecutionException {
        run(false);
    }

    public void run(boolean announceStartup)
        throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        if (waitUntilBrokerAvailableIfNecessary(params.getServersStartUpTimeout(),
                                                params.getServersUpLimit() == -1 ? (uris == null ? 0 : uris.size())
                                                        : params.getServersUpLimit(),
                                                uris, factory)) {
            ScheduledExecutorService heartbeatSenderExecutorService = this.threadingHandler.scheduledExecutorService(
                    "perf-test-heartbeat-sender-",
                    this.params.getHeartbeatSenderThreads()
            );
            factory.setHeartbeatExecutor(heartbeatSenderExecutorService);
            // use a single-threaded executor for the configuration connection
            // this way, a default one is not created and this one will shut down
            // when the run ends.
            // this can matter when this instance is used for several runs, e.g. with PerfTestMulti
            // see https://github.com/rabbitmq/rabbitmq-perf-test/issues/220
            ExecutorService executorServiceConfigurationConnection = this.threadingHandler.executorService(
                    "perf-test-configuration-", 1
            );
            factory.setSharedExecutor(executorServiceConfigurationConnection);
            List<Connection> configurationConnections = createConfigurationConnections();
            List<MulticastParams.TopologyHandlerResult> topologyHandlerResults = params.configureAllQueues(configurationConnections);
            enableTopologyRecoveryIfNecessary(topologyHandlerResults);

            ScheduledExecutorService topologyRecordingScheduledExecutorService = null;
            if (!configurationConnections.isEmpty() && Utils.isRecoverable(configurationConnections.get(0))) {
                topologyRecordingScheduledExecutorService = this.threadingHandler.scheduledExecutorService(
                  "perf-test-topology-recovery-", 1
                );
            }

            this.params.resetTopologyHandler();

            Consumer[] consumerRunnables = new Consumer[params.getConsumerThreadCount()];
            Connection[] consumerConnections = new Connection[params.getConsumerCount()];
            Function<Integer, ExecutorService> consumersExecutorsFactory;
            consumersExecutorsFactory = createConsumersExecutorsFactory();

            createConsumers(announceStartup, consumerRunnables, consumerConnections,
                consumersExecutorsFactory, topologyRecordingScheduledExecutorService);

            this.params.resetTopologyHandler();

            AgentState[] producerStates = new AgentState[params.getProducerThreadCount()];
            Connection[] producerConnections = new Connection[params.getProducerCount()];
            // producers don't need an executor service, as they don't have any consumers
            // this consumer should never be asked to create any threads
            ExecutorService executorServiceForProducersConsumers = this.threadingHandler.executorService(
                    "perf-test-producers-worker-", 0
            );
            factory.setSharedExecutor(executorServiceForProducersConsumers);

            createProducers(announceStartup, producerStates, producerConnections);

            startConsumers(consumerRunnables);
            startProducers(producerStates);

            if (params.getExitWhen() == EXIT_WHEN.EMPTY || params.getExitWhen() == EXIT_WHEN.IDLE) {
                ScheduledExecutorService scheduledExecutorService =
                    this.threadingHandler.scheduledExecutorService(
                        "perf-test-queue-empty-consumer-idle-scheduler", 1);
                    scheduledExecutorService.scheduleAtFixedRate(
                    () -> {
                      for (Consumer consumer : consumerRunnables) {
                        try {
                            consumer.maybeStopIfNoActivityOrQueueEmpty();
                        } catch (Exception e) {
                            LOGGER.info("Error while checking exit-when for consumer {}: {}", consumer, e.getMessage());
                        }
                      }
                    },
                    2,
                    1,
                    TimeUnit.SECONDS);
            }

            AutoCloseable shutdownSequence;
            int shutdownTimeout = this.params.getShutdownTimeout();
            if (shutdownTimeout > 0) {
                shutdownSequence = this.shutdownService.wrap(
                        () -> {
                            CountDownLatch latch = new CountDownLatch(1);
                            Thread shutdownThread = new Thread(() -> {
                                if (this.params.isPolling()) {
                                    Connection connection = null;
                                    try {
                                        connection = createConnection("perf-test-queue-deletion");
                                        this.params.deleteAutoDeleteQueuesIfNecessary(connection);
                                    } catch (Exception e) {
                                        LOGGER.warn("Error while trying to delete auto-delete queues");
                                    } finally {
                                        if (connection != null) {
                                            dispose(connection);
                                        }
                                    }
                                }
                                if (Thread.interrupted()) {
                                    return;
                                }
                                try {
                                    shutdown(configurationConnections, consumerConnections, producerStates, producerConnections);
                                } finally {
                                    latch.countDown();
                                }
                            });
                            shutdownThread.start();
                            boolean done = latch.await(shutdownTimeout, TimeUnit.SECONDS);
                            if (!done) {
                                LOGGER.debug("Shutdown not completed in {} second(s), aborting.", shutdownTimeout);
                                shutdownThread.interrupt();
                            }
                        }
                );
            } else {
                // no closing timeout, we don't do anything
                shutdownSequence = () -> { };
            }

            this.completionHandler.waitForCompletion();

            try {
                shutdownSequence.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            System.out.println("Could not connect to broker(s) in " + params.getServersStartUpTimeout() + " second(s), exiting.");
        }
    }

    static boolean waitUntilBrokerAvailableIfNecessary(int startUpTimeoutInSeconds, int serversUpLimit,
                                                       Collection<String> uris, ConnectionFactory factory)
            throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException, InterruptedException {
        if (startUpTimeoutInSeconds <= 0 || uris == null || uris.isEmpty()) {
            // we don't test the connection to the broker
            return true;
        } else {
            Collection<String> tested = new ArrayList<>(uris);
            Collection<String> connected = new ArrayList<>();
            long started = System.nanoTime();
            while ((System.nanoTime() - started) / 1_000_000_000 < startUpTimeoutInSeconds) {
                Iterator<String> iterator = tested.iterator();
                while (iterator.hasNext()) {
                    String uri = iterator.next();
                    factory.setUri(uri);
                    try (Connection ignored = factory.newConnection("perf-test-test")) {
                        connected.add(uri);
                        if (connected.size() == serversUpLimit) {
                            uris.clear();
                            uris.addAll(connected);
                            return true;
                        }
                        iterator.remove();
                    } catch (Exception e) {
                        LOGGER.info("Could not connect to broker " + factory.getHost()+ ":" + factory.getPort());
                    }
                }
                Thread.sleep(1000L);
            }
            return false;
        }
    }

    Connection createConnection(String name) throws IOException, TimeoutException {
        return this.connectionCreator.createConnection(name);
    }

    List<Connection> createConfigurationConnections() throws IOException, TimeoutException {
        return this.connectionCreator.createConfigurationConnections();
    }

    private Function<Integer, ExecutorService> createConsumersExecutorsFactory() {
        Function<Integer, ExecutorService> consumersExecutorsFactory;
        if (params.isPolling()) {
            // polling, i.e. using basic.get in a loop, we need a dedicated thread for each channel for each consumer
            // FIXME we keep also an extra thread for the connection factory consumer dispatcher, which sometimes
            // does not close properly without some room and makes the channel manager complain
            consumersExecutorsFactory = consumerNumber -> this.threadingHandler.executorService(
                format("perf-test-synchronous-consumer-%d-worker-", consumerNumber),
                this.params.getConsumerChannelCount() + 1
            );
        } else {
            // asynchronous consumers
            if (params.getConsumersThreadPools() > 0) {
                consumersExecutorsFactory = new CacheConsumersExecutorsFactory(
                        this.threadingHandler, this.params, params.getConsumersThreadPools()
                );
            } else {
                consumersExecutorsFactory = new NoCacheConsumersExecutorsFactory(
                        this.threadingHandler, this.params
                );
            }
        }

        return consumersExecutorsFactory;
    }

    private void createConsumers(boolean announceStartup,
                                 Runnable[] consumerRunnables,
                                 Connection[] consumerConnections,
                                 Function<Integer, ExecutorService> consumersExecutorsFactory,
                                 ScheduledExecutorService topologyRecordingScheduledExecutorService) throws IOException, TimeoutException {
        for (int i = 0; i < consumerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting consumer #" + i);
            }
            ExecutorService executorService = consumersExecutorsFactory.apply(i);
            factory.setSharedExecutor(executorService);

            Connection consumerConnection = createConnection("perf-test-consumer-" + i);
            consumerConnections[i] = consumerConnection;
            for (int j = 0; j < params.getConsumerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting consumer #" + i + ", channel #" + j);
                }
                Consumer consumer = params.createConsumer(consumerConnection, stats,
                    this.consumerLatencyIndicator, this.completionHandler, executorService,
                    topologyRecordingScheduledExecutorService);
                consumerRunnables[(i * params.getConsumerChannelCount()) + j] = consumer;
            }
        }
    }

    private void createProducers(boolean announceStartup, AgentState[] producerStates,
                                 Connection[] producerConnections) throws IOException, TimeoutException {
        for (int i = 0; i < producerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting producer #" + i);
            }
            Connection producerConnection = createConnection(PRODUCER_THREAD_PREFIX + i);
            producerConnections[i] = producerConnection;
            for (int j = 0; j < params.getProducerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting producer #" + i + ", channel #" + j);
                }
                AgentState agentState = new AgentState();
                agentState.runnable = params.createProducer(
                        producerConnection, stats,
                        this.completionHandler, this.rateIndicator, this.messageSizeIndicator
                );
                producerStates[(i * params.getProducerChannelCount()) + j] = agentState;
            }
        }
    }

    private void startConsumers(Runnable[] consumerRunnables) throws InterruptedException {
        this.consumerLatencyIndicator.start();
        for (Runnable runnable : consumerRunnables) {
            runnable.run();
            if (params.getConsumerSlowStart()) {
                System.out.println("Delaying start by 1 second because -S/--slow-start was requested");
                Thread.sleep(1000);
            }
        }
    }

    private void startProducers(AgentState[] producerStates) {
        this.messageSizeIndicator.start();
        Float rateIndication = this.rateIndicator.getValue();
        if (!this.rateIndicator.isVariable() && rateIndication >= 1.0f && rateIndication <= 10.0f) {
            Duration calculatedPublishingInterval = rateToPublishingInterval(rateIndication);
            LOGGER.debug("Rate between 1 and 10 messages / second, "
                + "falling back to scheduling with {} ms as publishing interval", calculatedPublishingInterval.toMillis());
            params.setPublishingInterval(calculatedPublishingInterval);
        }
        if (params.getPublishingInterval() != null) {
            ScheduledExecutorService producersExecutorService = this.threadingHandler.scheduledExecutorService(
                    PRODUCER_THREAD_PREFIX, nbThreadsForProducerScheduledExecutorService(params)
            );
            Supplier<Duration> startDelaySupplier;
            if (params.getProducerRandomStartDelayInSeconds() > 0) {
                LOGGER.debug("Using random start-up delay for producers, from 1 ms to {} s",
                    params.getProducerRandomStartDelayInSeconds());
                Random random = new Random();
                int bound = params.getProducerRandomStartDelayInSeconds() * 1000;
                startDelaySupplier = () -> Duration.ofMillis(
                    random.nextInt(bound) + 1
                );
            } else {
                LOGGER.debug("No start-up delay for producers, they are starting ASAP");
                startDelaySupplier = () -> Duration.ZERO;
            }
            Duration publishingInterval = params.getPublishingInterval();
            for (int i = 0; i < producerStates.length; i++) {
                AgentState producerState = producerStates[i];
                Duration delay = startDelaySupplier.get();
                producerState.task = producersExecutorService.scheduleAtFixedRate(
                        producerState.runnable.createRunnableForScheduling(),
                        delay.toMillis(), publishingInterval.toMillis(), TimeUnit.MILLISECONDS
                );
            }
        } else {
            if (!this.rateIndicator.isVariable() && this.rateIndicator.getValue() == 0.0f) {
                // don't do anything, the rate is 0, we just created the publishers connections
                // but they won't publish anything.
                for (AgentState producerState : producerStates) {
                    producerState.task = Utils.NO_OP_FUTURE;
                }
            } else {
                this.rateIndicator.start();
                ExecutorService producersExecutorService = this.threadingHandler.executorService(
                        PRODUCER_THREAD_PREFIX, producerStates.length
                );
                for (AgentState producerState : producerStates) {
                    producerState.task = producersExecutorService.submit(producerState.runnable);
                }
            }
        }
    }

    static Duration rateToPublishingInterval(double rate) {
        return Duration.ofMillis((long) (1.0d / rate * 1000.0));
    }

    private void shutdown(List<Connection> configurationConnections, Connection[] consumerConnections, AgentState[] producerStates, Connection[] producerConnections) {
        try {
            LOGGER.debug("Starting test shutdown");
            for (AgentState producerState : producerStates) {
                if (Thread.interrupted()) {
                    return;
                }
                boolean cancelled = producerState.task.cancel(true);
                LOGGER.debug("Producer has been correctly cancelled: {}", cancelled);
            }

            // we do our best to stop producers before closing their connections
            for (AgentState producerState : producerStates) {
                if (!producerState.task.isDone()) {
                    try {
                        if (Thread.interrupted()) {
                            return;
                        }
                        producerState.task.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.debug("Error while waiting for producer to stop: {}. Moving on.", e.getMessage());
                    }
                }
            }

            if (Thread.interrupted()) {
                return;
            }

            if(!closeConnections(configurationConnections.toArray(new Connection[] {}))) {
                return;
            }
            if(!closeConnections(producerConnections)) {
                return;
            }
            if(!closeConnections(consumerConnections)) {
                return;
            }

            if (Thread.interrupted()) {
                return;
            }
            LOGGER.debug("Shutting down threading handler");
            this.threadingHandler.shutdown();
            LOGGER.debug("Threading handler shut down");
        } catch (Exception e) {
            LOGGER.warn("Error during test shutdown", e);
        }
    }

    private static boolean closeConnections(Connection[] connections) {
        for (Connection connection : connections) {
            if (Thread.interrupted()) {
                return false;
            }
            dispose(connection);
        }
        return true;
    }

    private void enableTopologyRecoveryIfNecessary(List<MulticastParams.TopologyHandlerResult> topologyHandlerResults)
            throws IOException {
        for (MulticastParams.TopologyHandlerResult topologyHandlerResult : topologyHandlerResults) {
            Connection connection = topologyHandlerResult.connection;
            if (isRecoverable(topologyHandlerResult.connection)) {
                String connectionName = connection.getClientProvidedName();
                ((AutorecoveringConnection) connection).addRecoveryListener(new RecoveryListener() {

                    @Override
                    public void handleRecoveryStarted(Recoverable recoverable) {
                        LOGGER.debug("Connection recovery started for connection {}", connectionName);
                    }

                    @Override
                    public void handleRecovery(Recoverable recoverable) {
                        LOGGER.debug("Starting topology recovery for connection {}", connectionName);
                        topologyHandlerResult.topologyRecording.recover(connection);
                        LOGGER.debug("Topology recovery done for connection {}", connectionName);
                    }
                });
            } else {
                connection.close();
            }
        }
    }

    private static void dispose(Connection connection) {
        try {
            LOGGER.debug("Closing connection {}", connection.getClientProvidedName());
            // we need to shutdown, it should not take forever, so we pick a reasonably
            // small timeout (3 seconds)
            connection.close(AMQP.REPLY_SUCCESS, "Closed by PerfTest", 3000);
            LOGGER.debug("Connection {} has been closed", connection.getClientProvidedName());
        } catch (AlreadyClosedException e) {
            LOGGER.debug("Connection {} already closed", connection.getClientProvidedName());
        } catch (Exception e) {
            // just log, we don't want to stop here
            LOGGER.debug("Error while closing connection {}: {}", connection.getClientProvidedName(), e.getMessage());
        }
    }

    public void setThreadingHandler(ThreadingHandler threadingHandler) {
        this.threadingHandler = threadingHandler;
    }

    /**
     * Abstraction for thread management.
     * Exists to ease testing.
     */
    interface ThreadingHandler {

        ExecutorService executorService(String name, int nbThreads);

        ScheduledExecutorService scheduledExecutorService(String name, int nbThreads);

        void shutdown();
    }

    public interface CompletionHandler {

        void waitForCompletion() throws InterruptedException;

        void countDown(String reason);
    }

    static class DefaultThreadingHandler implements ThreadingHandler {

        private final Collection<ExecutorService> executorServices = new ArrayList<>();
        private final AtomicBoolean closing = new AtomicBoolean(false);
        private final String prefix;

        DefaultThreadingHandler(String prefix) {
            this.prefix = prefix;
        }

        DefaultThreadingHandler() {
            this("");
        }

        @Override
        public ExecutorService executorService(String name, int nbThreads) {
            if (nbThreads <= 0) {
                return create(() -> Executors.newSingleThreadExecutor(new NamedThreadFactory(prefix + name)));
            } else {
                return create(() -> Executors.newFixedThreadPool(nbThreads, new NamedThreadFactory(prefix + name)));
            }
        }

        @Override
        public ScheduledExecutorService scheduledExecutorService(String name, int nbThreads) {
            return (ScheduledExecutorService) create(() -> Executors.newScheduledThreadPool(nbThreads, new NamedThreadFactory(name)));
        }

        private ExecutorService create(Supplier<ExecutorService> s) {
            ExecutorService executorService = s.get();
            this.executorServices.add(executorService);
            return executorService;
        }

        @Override
        public void shutdown() {
            if (closing.compareAndSet(false, true)) {
                for (ExecutorService executorService : executorServices) {
                    executorService.shutdownNow();
                    try {
                        boolean terminated = executorService.awaitTermination(10, TimeUnit.SECONDS);
                        if (!terminated) {
                            LoggerFactory.getLogger(DefaultThreadingHandler.class).warn(
                                "Some PerfTest tasks (producer, consumer, rate scheduler) didn't finish"
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private static class AgentState {

        private Producer runnable;

        private Future<?> task;
    }

    private static void recordReason(Map<String, Integer> reasons, String reason) {
        reasons.compute(reason, (keyReason, count) -> count == null ? 1 : ++count);
    }

    static class DefaultCompletionHandler implements CompletionHandler {

        private final int timeLimit;
        private final CountDownLatch latch;
        private final ConcurrentMap<String, Integer> reasons;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        DefaultCompletionHandler(int timeLimit, int countLimit, ConcurrentMap<String, Integer> reasons) {
            this.timeLimit = timeLimit;
            this.latch = new CountDownLatch(countLimit <= 0 ? 1 : countLimit);
            this.reasons = reasons;
        }

        @Override
        public void waitForCompletion() throws InterruptedException {
            if (timeLimit <= 0) {
                this.latch.await();
                completed.set(true);
            } else {
                boolean countedDown = this.latch.await(timeLimit, TimeUnit.SECONDS);
                completed.set(true);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Completed, counted down? {}", countedDown);
                }
                if (!countedDown) {
                    recordReason(reasons, STOP_REASON_REACHED_TIME_LIMIT);
                }
            }
        }

        @Override
        public void countDown(String reason) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Counting down ({})", reason);
            }
            if (!completed.get()) {
                recordReason(reasons, reason);
                latch.countDown();
            }
        }
    }

    /**
     * This completion handler waits forever, but it can be counted down,
     * typically when a producer or a consumer fails. This avoids
     * PerfTest hanging after a failure.
     */
    static class NoLimitCompletionHandler implements CompletionHandler {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final ConcurrentMap<String, Integer> reasons;

        NoLimitCompletionHandler(ConcurrentMap<String, Integer> reasons) {
            this.reasons = reasons;
        }

        @Override
        public void waitForCompletion() throws InterruptedException {
            latch.await();
        }

        @Override
        public void countDown(String reason) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Counting down ({})", reason);
            }
            recordReason(reasons, reason);
            latch.countDown();
        }
    }

    static class NoCacheConsumersExecutorsFactory implements Function<Integer, ExecutorService> {

        private final ThreadingHandler threadingHandler;
        private final MulticastParams params;

        NoCacheConsumersExecutorsFactory(ThreadingHandler threadingHandler, MulticastParams params) {
            this.threadingHandler = threadingHandler;
            this.params = params;
        }

        @Override
        public ExecutorService apply(Integer consumerNumber) {
            ExecutorService executorService = this.threadingHandler.executorService(
                format("perf-test-consumer-%d-worker-", consumerNumber),
                nbThreadsForConsumer(this.params)
            );
            return executorService;
        }
    }

    static class CacheConsumersExecutorsFactory implements Function<Integer, ExecutorService> {

        private final ThreadingHandler threadingHandler;
        private final MulticastParams params;
        private final int modulo;
        private final List<ExecutorService> cache;

        CacheConsumersExecutorsFactory(ThreadingHandler threadingHandler, MulticastParams params, int modulo) {
            this.threadingHandler = threadingHandler;
            this.params = params;
            this.modulo = modulo;
            this.cache = new ArrayList<>(modulo);
            IntStream.range(0, modulo).forEach(i -> cache.add(null));
        }

        @Override
        public ExecutorService apply(Integer consumerNumber) {
            int remaining = consumerNumber % modulo;
            ExecutorService executorService = cache.get(remaining);
            if (executorService == null) {
                executorService = this.threadingHandler.executorService(
                    format("perf-test-shared-consumer-worker-%d-", remaining),
                    nbThreadsForConsumer(this.params)
                );
                cache.set(remaining, executorService);
            }
            return executorService;
        }
    }

    private static class ConnectionCreator {

        private final ConnectionFactory cf;
        private final List<Address> addresses;

        private ConnectionCreator(ConnectionFactory cf, List<String> uris) {
            this.cf = cf;
            if (uris == null || uris.isEmpty()) {
                // URI already set on the connection factory, nothing special to do
                addresses = Collections.emptyList();
            } else {
                List<Address> addresses = new ArrayList<>(uris.size());
                for (String uri : uris) {
                    try {
                        addresses.add(Utils.extract(uri));
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Could not parse URI: " + uri);
                    }
                }
                this.addresses = Collections.unmodifiableList(addresses);
            }
        }

        /**
         * Create a connection for a consumer or a producer.
         * @param name
         * @return
         * @throws IOException
         * @throws TimeoutException
         */
        Connection createConnection(String name) throws IOException, TimeoutException {
            if (this.addresses.isEmpty()) {
                return this.cf.newConnection(name);
            } else {
                List<Address> addrs = new ArrayList<>(addresses);
                if (addresses.size() > 1) {
                    Collections.shuffle(addrs);
                }
                return this.cf.newConnection(addrs, name);
            }
        }

        /**
         * Create connections to create resources across the cluster.
         * @return
         * @throws IOException
         * @throws TimeoutException
         */
        List<Connection> createConfigurationConnections() throws IOException, TimeoutException {
            if (this.addresses.isEmpty()) {
                return Collections.singletonList(createConnection("perf-test-configuration-0"));
            } else {
                List<Connection> connections = new ArrayList<>(this.addresses.size());
                for (int i = 0; i < addresses.size(); i++) {
                    connections.add(this.cf.newConnection(
                            Collections.singletonList(addresses.get(i)),
                            "perf-test-configuration-" + i
                    ));
                }
                return Collections.unmodifiableList(connections);
            }
        }

    }
}
