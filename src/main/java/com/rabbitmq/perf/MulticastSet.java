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

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static java.lang.Math.min;
import static java.lang.String.format;

public class MulticastSet {

    private final Stats stats;
    private final ConnectionFactory factory;
    private final MulticastParams params;
    private final String testID;
    private final List<String> uris;

    private final Random random = new Random();

    private ThreadingHandler threadingHandler = new DefaultThreadingHandler();

    private final CompletionHandler completionHandler;

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, List<String> uris, CompletionHandler completionHandler) {
        this(stats, factory, params, "perftest", uris, completionHandler);
    }

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, String testID, List<String> uris, CompletionHandler completionHandler) {
        this.stats = stats;
        this.factory = factory;
        this.params = params;
        this.testID = testID;
        this.uris = uris;
        this.completionHandler = completionHandler;
        this.params.init();
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
        Connection conn = factory.newConnection("perf-test-configuration");
        params.configureAllQueues(conn);
        conn.close();

        this.params.resetTopologyHandler();

        Runnable[] consumerRunnables = new Runnable[params.getConsumerThreadCount()];
        Connection[] consumerConnections = new Connection[params.getConsumerCount()];
        for (int i = 0; i < consumerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting consumer #" + i);
            }
            setUri();
            ExecutorService executorService = this.threadingHandler.executorService(
                format("perf-test-consumer-%s-worker-", i),
                nbThreadsForConsumer(this.params)
            );
            factory.setSharedExecutor(executorService);

            conn = factory.newConnection("perf-test-consumer-" + i);
            consumerConnections[i] = conn;
            for (int j = 0; j < params.getConsumerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting consumer #" + i + ", channel #" + j);
                }
                consumerRunnables[(i * params.getConsumerChannelCount()) + j] = params.createConsumer(conn, stats, this.completionHandler);
            }
        }

        this.params.resetTopologyHandler();

        AgentState[] producerStates = new AgentState[params.getProducerThreadCount()];
        Connection[] producerConnections = new Connection[params.getProducerCount()];
        // producers don't need an executor service, as they don't have any consumers
        // this consumer should never be asked to create any threads
        ExecutorService executorServiceForProducersConsumers = this.threadingHandler.executorService(
            "perf-test-producers-worker-", 0
        );
        factory.setSharedExecutor(executorServiceForProducersConsumers);
        for (int i = 0; i < producerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting producer #" + i);
            }
            setUri();
            conn = factory.newConnection("perf-test-producer-" + i);
            producerConnections[i] = conn;
            for (int j = 0; j < params.getProducerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting producer #" + i + ", channel #" + j);
                }
                AgentState agentState = new AgentState();
                agentState.runnable = params.createProducer(conn, stats, this.completionHandler);
                producerStates[(i * params.getProducerChannelCount()) + j] = agentState;
            }
        }

        for (Runnable runnable : consumerRunnables) {
            runnable.run();
            if(params.getConsumerSlowStart()) {
                System.out.println("Delaying start by 1 second because -S/--slow-start was requested");
                Thread.sleep(1000);
            }
        }

        ExecutorService producersExecutorService = this.threadingHandler.executorService(
            "perf-test-producer-", producerStates.length
        );
        for (AgentState producerState : producerStates) {
            producerState.task = producersExecutorService.submit(producerState.runnable);
        }

        this.completionHandler.waitForCompletion();

        int count = 1; // counting the threads
        for (int i = 0; i < producerStates.length; i++) {
            producerStates[i].task.cancel(true);
            if(count % params.getProducerChannelCount() == 0) {
                // this is the end of a group of threads on the same connection,
                // closing the connection
                producerConnections[count / params.getProducerChannelCount() - 1].close();
            }
            count++;
        }

        for (Connection consumerConnection : consumerConnections) {
            consumerConnection.close();
        }

        this.threadingHandler.shutdown();
    }

    // from Java Client ConsumerWorkService
    public final static int DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;

    protected static int nbThreadsForConsumer(MulticastParams params) {
        // for backward compatibility, the thread pool should large enough to dedicate
        // one thread for each channel when channel number is <= DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE
        // Above this number, we stick to DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE
        return min(params.getConsumerChannelCount(), DEFAULT_CONSUMER_WORK_SERVICE_THREAD_POOL_SIZE);
    }

    private void setUri() throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        if(uris != null) {
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

    static class DefaultThreadingHandler implements ThreadingHandler {

        private final Collection<ExecutorService> executorServices = new ArrayList<>();

        @Override
        public ExecutorService executorService(String name, int nbThreads) {
            if (nbThreads <= 0) {
                return create(() -> Executors.newSingleThreadExecutor(new NamedThreadFactory(name)));
            } else {
                return create(() -> Executors.newFixedThreadPool(nbThreads, new NamedThreadFactory(name)));
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
            for (ExecutorService executorService : executorServices) {
                executorService.shutdown();
            }
        }
    }

    private static class AgentState {

        private Runnable runnable;

        private Future<?> task;

    }

    interface CompletionHandler {

        void waitForCompletion() throws InterruptedException;

        void countDown();

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
                this.latch.await(timeLimit, TimeUnit.SECONDS);
            }
        }

        @Override
        public void countDown() {
            latch.countDown();
        }
    }

    static class NoLimitCompletionHandler implements CompletionHandler {

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void waitForCompletion() throws InterruptedException {
            latch.await();
        }

        @Override
        public void countDown() { }
    }


}
