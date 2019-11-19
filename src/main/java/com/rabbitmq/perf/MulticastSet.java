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

import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.rabbitmq.perf.Utils.isRecoverable;
import static java.lang.Math.min;
import static java.lang.String.format;

public class MulticastSet {

    // from Java Client ConsumerWorkService
    public final static int DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(MulticastSet.class);
    /**
     * Why 50? This is arbitrary. The fastest rate is 1 message / second when
     * using a publishing interval, so 1 thread should be able to keep up easily with
     * up to 50 messages / seconds (ie. 50 producers). Then, a new thread is used
     * every 50 producers. This is 20 threads for 1000 producers, which seems reasonable.
     * There's a command line argument to override this anyway.
     */
    private static final int PUBLISHING_INTERVAL_NB_PRODUCERS_PER_THREAD = 50;
    private static final String PRODUCER_THREAD_PREFIX = "perf-test-producer-";
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
        this.connectionCreator = new ConnectionCreator(this.factory, this.uris);
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
            return params.getProducerThreadCount() / PUBLISHING_INTERVAL_NB_PRODUCERS_PER_THREAD + 1;
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

            this.params.resetTopologyHandler();

            Runnable[] consumerRunnables = new Runnable[params.getConsumerThreadCount()];
            Connection[] consumerConnections = new Connection[params.getConsumerCount()];
            Function<Integer, ExecutorService> consumersExecutorsFactory;
            consumersExecutorsFactory = createConsumersExecutorsFactory();

            createConsumers(announceStartup, consumerRunnables, consumerConnections, consumersExecutorsFactory);

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

    private void createConsumers(boolean announceStartup, Runnable[] consumerRunnables, Connection[] consumerConnections, Function<Integer, ExecutorService> consumersExecutorsFactory) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException, IOException, TimeoutException {
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
                consumerRunnables[(i * params.getConsumerChannelCount()) + j] = params.createConsumer(consumerConnection, stats, this.completionHandler, executorService);
            }
        }
    }

    private void createProducers(boolean announceStartup, AgentState[] producerStates, Connection[] producerConnections) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException, IOException, TimeoutException {
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
        if (params.getPublishingInterval() > 0) {
            ScheduledExecutorService producersExecutorService = this.threadingHandler.scheduledExecutorService(
                    PRODUCER_THREAD_PREFIX, nbThreadsForConsumer(params)
            );
            Supplier<Integer> startDelaySupplier;
            if (params.getProducerRandomStartDelayInSeconds() > 0) {
                Random random = new Random();
                startDelaySupplier = () -> random.nextInt(params.getProducerRandomStartDelayInSeconds()) + 1;
            } else {
                startDelaySupplier = () -> 0;
            }
            int publishingInterval = params.getPublishingInterval();
            for (int i = 0; i < producerStates.length; i++) {
                AgentState producerState = producerStates[i];
                int delay = startDelaySupplier.get();
                producerState.task = producersExecutorService.scheduleAtFixedRate(
                        producerState.runnable.createRunnableForScheduling(),
                        delay, publishingInterval, TimeUnit.SECONDS
                );
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

        void countDown();
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

    static class DefaultCompletionHandler implements CompletionHandler {

        private final int timeLimit;
        private final CountDownLatch latch;

        DefaultCompletionHandler(int timeLimit, int countLimit) {
            this.timeLimit = timeLimit;
            this.latch = new CountDownLatch(countLimit <= 0 ? 1 : countLimit);
        }

        @Override
        public void waitForCompletion() throws InterruptedException {
            if (timeLimit <= 0) {
                this.latch.await();
            } else {
                boolean countedDown = this.latch.await(timeLimit, TimeUnit.SECONDS);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Completed, counted down? {}", countedDown);
                }
            }
        }

        @Override
        public void countDown() {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Counting down");
            }
            latch.countDown();
        }
    }

    /**
     * This completion handler waits forever, but it can be counted down,
     * typically when a producer or a consumer fails. This avoids
     * PerfTest hanging after a failure.
     */
    static class NoLimitCompletionHandler implements CompletionHandler {

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void waitForCompletion() throws InterruptedException {
            latch.await();
        }

        @Override
        public void countDown() {
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
