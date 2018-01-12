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
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class MulticastSet {

    private final Stats stats;
    private final ConnectionFactory factory;
    private final MulticastParams params;
    private final String testID;
    private final List<String> uris;

    private final Random random = new Random();

    private ThreadHandler threadHandler = new DefaultThreadHandler();

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, List<String> uris) {
        this(stats, factory, params, "perftest", uris);
    }

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, String testID, List<String> uris) {
        this.stats = stats;
        this.factory = factory;
        this.params = params;
        this.testID = testID;
        this.uris = uris;

        this.params.init();
    }

    public void run() throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        run(false);
    }

    public void run(boolean announceStartup)
        throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {

        setUri();
        Connection conn = factory.newConnection();
        params.configureAllQueues(conn);
        conn.close();

        this.params.resetTopologyHandler();

        Thread[] consumerThreads = new Thread[params.getConsumerThreadCount()];
        Connection[] consumerConnections = new Connection[params.getConsumerCount()];
        for (int i = 0; i < consumerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting consumer #" + i);
            }
            setUri();
            conn = factory.newConnection();
            consumerConnections[i] = conn;
            for (int j = 0; j < params.getConsumerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting consumer #" + i + ", channel #" + j);
                }
                Thread t = new Thread(params.createConsumer(conn, stats));
                consumerThreads[(i * params.getConsumerChannelCount()) + j] = t;
            }
        }

        this.params.resetTopologyHandler();

        Thread[] producerThreads = new Thread[params.getProducerThreadCount()];
        Connection[] producerConnections = new Connection[params.getProducerCount()];
        for (int i = 0; i < producerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting producer #" + i);
            }
            setUri();
            conn = factory.newConnection();
            producerConnections[i] = conn;
            for (int j = 0; j < params.getProducerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting producer #" + i + ", channel #" + j);
                }
                Thread t = new Thread(params.createProducer(conn, stats));
                producerThreads[(i * params.getProducerChannelCount()) + j] = t;
            }
        }

        for (Thread consumerThread : consumerThreads) {
            this.threadHandler.start(consumerThread);
            if(params.getConsumerSlowStart()) {
            	System.out.println("Delaying start by 1 second because -S/--slow-start was requested");
            	Thread.sleep(1000);
            }
        }

        for (Thread producerThread : producerThreads) {
            this.threadHandler.start(producerThread);
        }

        int count = 1; // counting the threads
        for (int i = 0; i < producerThreads.length; i++) {
            this.threadHandler.waitForCompletion(producerThreads[i]);
            if(count % params.getProducerChannelCount() == 0) {
                // this is the end of a group of threads on the same connection,
                // closing the connection
                producerConnections[count / params.getProducerChannelCount() - 1].close();
            }
            count++;
        }

        count = 1; // counting the threads
        for (int i = 0; i < consumerThreads.length; i++) {
            this.threadHandler.waitForCompletion(consumerThreads[i]);
            if(count % params.getConsumerChannelCount() == 0) {
                // this is the end of a group of threads on the same connection,
                // closing the connection
                consumerConnections[count / params.getConsumerChannelCount() - 1].close();
            }
            count++;
        }
    }

    public void setThreadHandler(ThreadHandler threadHandler) {
        this.threadHandler = threadHandler;
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

    /**
     * Abstraction for thread management.
     * Exists to ease testing.
     */
    interface ThreadHandler {

        void start(Thread thread);

        void waitForCompletion(Thread thread) throws InterruptedException;

    }

    static class DefaultThreadHandler implements ThreadHandler {

        @Override
        public void start(Thread thread) {
            thread.start();
        }

        @Override
        public void waitForCompletion(Thread thread) throws InterruptedException {
            thread.join();
        }

    }

}
