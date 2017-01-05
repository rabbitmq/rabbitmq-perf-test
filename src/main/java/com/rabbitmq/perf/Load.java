package com.rabbitmq.perf;

import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.nio.NioParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 */
public class Load {

    int nbProducerConnection = 40;
    int nbConsumerConnection = 60;
    int channelPerConnection = 10;
    int nbQueues = 1000;
    int nbPublisher = 10;
    String prefix = "";
    String uri = "amqp://localhost";

    public void run() throws Exception {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setUri(uri);
        connectionFactory.useNio();
        connectionFactory.setNioParams(new NioParams()
            .setNbIoThreads(100)
        );

        ExecutorService executorService = Executors.newFixedThreadPool(100);

        List<Connection> producerConnections = new ArrayList<Connection>(nbProducerConnection);
        final List<Channel> producerChannels = new ArrayList<Channel>(nbProducerConnection * channelPerConnection);
        for(int i = 0; i < nbProducerConnection; i++) {
            Connection connection = connectionFactory.newConnection(executorService);
            producerConnections.add(connection);
            for(int j = 0; j < channelPerConnection; j++) {
                producerChannels.add(connection.createChannel());
            }
        }

        List<Connection> consumerConnections = new ArrayList<Connection>(nbConsumerConnection);
        List<Channel> consumerChannels = new ArrayList<Channel>(nbConsumerConnection * channelPerConnection);
        for(int i = 0; i < nbConsumerConnection; i++) {
            Connection connection = connectionFactory.newConnection(executorService);
            consumerConnections.add(connection);
            for(int j = 0; j < channelPerConnection; j++) {
                consumerChannels.add(connection.createChannel());
            }
        }

        final List<String> queues = Collections.synchronizedList(new ArrayList<String>(nbQueues));
        final Random random = new Random();
        for(int i = 0; i < nbQueues; i++) {
            Channel channel = producerChannels.get(random.nextInt(nbProducerConnection * channelPerConnection));
            AMQP.Queue.DeclareOk declareOk = channel.queueDeclare(prefix + "queue-" + i, false, false, true, null);
            queues.add(declareOk.getQueue());
        }

        for(String queue : queues) {
            Channel channel = consumerChannels.get(random.nextInt(nbConsumerConnection * channelPerConnection));
            channel.basicConsume(queue, true, new DefaultConsumer(channel));
        }

        executorService = Executors.newFixedThreadPool(nbPublisher);
        for(int i = 0; i < nbPublisher; i++) {
            executorService.submit(new Callable<Void>() {

                @Override
                public Void call() throws Exception {
                    while(true) {
                        Channel channel = producerChannels.get(random.nextInt(nbProducerConnection * channelPerConnection));
                        String queue = queues.get(random.nextInt(nbQueues));
                        channel.basicPublish("", queue, null, "hello".getBytes());
                        Thread.sleep(100L);
                    }
                }
            });
        }
    }

    public void setNbProducerConnection(int nbProducerConnection) {
        this.nbProducerConnection = nbProducerConnection;
    }

    public void setNbConsumerConnection(int nbConsumerConnection) {
        this.nbConsumerConnection = nbConsumerConnection;
    }

    public void setChannelPerConnection(int channelPerConnection) {
        this.channelPerConnection = channelPerConnection;
    }

    public void setNbQueues(int nbQueues) {
        this.nbQueues = nbQueues;
    }

    public void setNbPublisher(int nbPublisher) {
        this.nbPublisher = nbPublisher;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public static void main(String[] args) throws Exception {
        new Load().run();
    }

}
