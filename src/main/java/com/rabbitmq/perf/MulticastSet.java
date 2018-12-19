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

import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.DefaultExceptionHandler;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
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
    private final Random random = new Random();
    private final CompletionHandler completionHandler;
    private final ShutdownService shutdownService;
    private ThreadingHandler threadingHandler = new DefaultThreadingHandler();

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
        this.uris = uris;
        this.completionHandler = completionHandler;
        this.shutdownService = shutdownService;
        this.params.init();
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
        throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException, ExecutionException {
        ScheduledExecutorService heartbeatSenderExecutorService = this.threadingHandler.scheduledExecutorService(
            "perf-test-heartbeat-sender-",
            this.params.getHeartbeatSenderThreads()
        );
        factory.setHeartbeatExecutor(heartbeatSenderExecutorService);
        setUri();
        Connection configurationConnection = factory.newConnection("perf-test-configuration");
        MulticastParams.TopologyHandlerResult topologyHandlerResult = params.configureAllQueues(configurationConnection);
        enableTopologyRecoveryIfNecessary(configurationConnection, topologyHandlerResult);

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

        AutoCloseable shutdownSequence = this.shutdownService.wrap(
                () -> shutdown(configurationConnection, consumerConnections, producerStates, producerConnections)
        );

        this.completionHandler.waitForCompletion();
        try {
            shutdownSequence.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Function<Integer, ExecutorService> createConsumersExecutorsFactory() {
        Function<Integer, ExecutorService> consumersExecutorsFactory;
        if (params.getConsumersThreadPools() > 0) {
            consumersExecutorsFactory = new CacheConsumersExecutorsFactory(
                this.threadingHandler, this.params, params.getConsumersThreadPools()
            );
        } else {
            consumersExecutorsFactory = new NoCacheConsumersExecutorsFactory(
                this.threadingHandler, this.params
            );
        }
        return consumersExecutorsFactory;
    }

    private void createConsumers(boolean announceStartup, Runnable[] consumerRunnables, Connection[] consumerConnections, Function<Integer, ExecutorService> consumersExecutorsFactory) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException, IOException, TimeoutException {
        for (int i = 0; i < consumerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting consumer #" + i);
            }
            setUri();
            ExecutorService executorService = consumersExecutorsFactory.apply(i);
            factory.setSharedExecutor(executorService);

            Connection consumerConnection = factory.newConnection("perf-test-consumer-" + i);
            consumerConnections[i] = consumerConnection;
            for (int j = 0; j < params.getConsumerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting consumer #" + i + ", channel #" + j);
                }
                consumerRunnables[(i * params.getConsumerChannelCount()) + j] = params.createConsumer(consumerConnection, stats, this.completionHandler);
            }
        }
    }

    private void createProducers(boolean announceStartup, AgentState[] producerStates, Connection[] producerConnections) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException, IOException, TimeoutException {
        for (int i = 0; i < producerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting producer #" + i);
            }
            setUri();
            Connection producerConnection = factory.newConnection(PRODUCER_THREAD_PREFIX + i);
            producerConnections[i] = producerConnection;
            for (int j = 0; j < params.getProducerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting producer #" + i + ", channel #" + j);
                }
                AgentState agentState = new AgentState();
                agentState.runnable = params.createProducer(producerConnection, stats, this.completionHandler);
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
            ExecutorService producersExecutorService = this.threadingHandler.executorService(
                    PRODUCER_THREAD_PREFIX, producerStates.length
            );
            for (AgentState producerState : producerStates) {
                producerState.task = producersExecutorService.submit(producerState.runnable);
            }
        }
    }

    private void shutdown(Connection configurationConnection, Connection[] consumerConnections, AgentState[] producerStates, Connection[] producerConnections) {
        try {
            LOGGER.debug("Starting test shutdown");
            for (AgentState producerState : producerStates) {
                boolean cancelled = producerState.task.cancel(true);
                LOGGER.debug("Producer has been correctly cancelled: {}", cancelled);
            }

            // we do our best to stop producers before closing their connections
            for (AgentState producerState : producerStates) {
                if (!producerState.task.isDone()) {
                    try {
                        producerState.task.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.debug("Error while waiting for producer to stop: {}. Moving on.", e.getMessage());
                    }
                }
            }

            dispose(configurationConnection);

            for (Connection producerConnection : producerConnections) {
                dispose(producerConnection);
            }

            for (Connection consumerConnection : consumerConnections) {
                dispose(consumerConnection);
            }

            LOGGER.debug("Shutting down threading handler");
            this.threadingHandler.shutdown();
            LOGGER.debug("Threading handler shut down");
        } catch (Exception e) {
            LOGGER.warn("Error during test shutdown", e);
        }
    }

    private void enableTopologyRecoveryIfNecessary(Connection configurationConnection, MulticastParams.TopologyHandlerResult topologyHandlerResult)
        throws IOException {
        if (isRecoverable(topologyHandlerResult.connection)) {
            Connection connection = topologyHandlerResult.connection;
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
            configurationConnection.close();
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
            // don't do anything, we need to close the other connections
            LOGGER.debug("Error while closing connection {}: {}", connection.getClientProvidedName(), e.getMessage());
        }
    }

    private void setUri() throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        if (uris != null) {
            factory.setUri(uri());
        }
    }

    private String uri() {
        String uri = uris.get(random.nextInt(uris.size()));
        return uri;
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
                                "Some producer and/or consumer tasks didn't finish"
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
}
