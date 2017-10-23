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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class MulticastSet {
    private final String routingKey;
    private final Stats stats;
    private final ConnectionFactory factory;
    private final MulticastParams params;
    private final String testID;
    private final List<String> uris;

    private final Random random = new Random();

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, List<String> uris) {
        if (params.getRoutingKey() == null) {
            this.routingKey = UUID.randomUUID().toString();
        } else {
            this.routingKey = params.getRoutingKey();
        }
        this.stats = stats;
        this.factory = factory;
        this.params = params;
        this.testID = "perftest";
        this.uris = uris;
    }

    public MulticastSet(Stats stats, ConnectionFactory factory,
        MulticastParams params, String testID, List<String> uris) {
        if (params.getRoutingKey() == null) {
            this.routingKey = UUID.randomUUID().toString();
        } else {
            this.routingKey = params.getRoutingKey();
        }
        this.stats = stats;
        this.factory = factory;
        this.params = params;
        this.testID = testID;
        this.uris = uris;
    }

    public void run() throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        run(false);
    }

    public void run(boolean announceStartup)
        throws IOException, InterruptedException, TimeoutException, NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        Set<String> queueNames = new HashSet<>();
        Collection<Consumer> consumers = new ArrayList<>();
        Thread[] consumerThreads = new Thread[params.getConsumerThreadCount()];
        Connection[] consumerConnections = new Connection[params.getConsumerCount()];
        for (int i = 0; i < consumerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting consumer #" + i);
            }
            setUri();
            Connection conn = factory.newConnection();
            consumerConnections[i] = conn;
            for (int j = 0; j < params.getConsumerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting consumer #" + i + ", channel #" + j);
                }
                Consumer consumer = params.createConsumer(conn, stats, routingKey);
                consumers.add(consumer);
                queueNames.addAll(consumer.getQueueNames());
                Thread t = new Thread(consumer);
                consumerThreads[(i * params.getConsumerChannelCount()) + j] = t;
            }
        }

        if (params.shouldConfigureQueues()) {
            setUri();
            Connection conn = factory.newConnection();
            List<String> configuredQueues = params.configureQueues(conn, routingKey);
            queueNames.addAll(configuredQueues);
            conn.close();
        }

        String producersRoutingKey;
        // if one queue, and no routing key, the routing key
        // must be the queue name and we should use the default exchange
        if (queueNames.size() == 1 && params.getRoutingKey() == null) {
            producersRoutingKey = queueNames.iterator().next();
        } else {
            producersRoutingKey = this.routingKey;
        }

        // consumers need the publishing routing key to match it against
        // against the received message and update the stats
        for (Consumer consumer : consumers) {
            consumer.setRoutingKey(producersRoutingKey);
        }

        Thread[] producerThreads = new Thread[params.getProducerThreadCount()];
        Connection[] producerConnections = new Connection[params.getProducerCount()];
        for (int i = 0; i < producerConnections.length; i++) {
            if (announceStartup) {
                System.out.println("id: " + testID + ", starting producer #" + i);
            }
            setUri();
            Connection conn = factory.newConnection();
            producerConnections[i] = conn;
            for (int j = 0; j < params.getProducerChannelCount(); j++) {
                if (announceStartup) {
                    System.out.println("id: " + testID + ", starting producer #" + i + ", channel #" + j);
                }
                Thread t = new Thread(params.createProducer(conn, stats, producersRoutingKey));
                producerThreads[(i * params.getProducerChannelCount()) + j] = t;
            }
        }

        for (Thread consumerThread : consumerThreads) {
            consumerThread.start();
        }

        for (Thread producerThread : producerThreads) {
            producerThread.start();
        }

        int count = 1; // counting the threads
        for (int i = 0; i < producerThreads.length; i++) {
            producerThreads[i].join();
            if(count % params.getProducerChannelCount() == 0) {
                // this is the end of a group of threads on the same connection,
                // closing the connection
                producerConnections[count / params.getProducerChannelCount() - 1].close();
            }
            count++;
        }

        count = 1; // counting the threads
        for (int i = 0; i < consumerThreads.length; i++) {
            consumerThreads[i].join();
            if(count % params.getConsumerChannelCount() == 0) {
                // this is the end of a group of threads on the same connection,
                // closing the connection
                consumerConnections[count / params.getConsumerChannelCount() - 1].close();
            }
            count++;
        }
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
}
