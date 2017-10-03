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

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MulticastParams {
    private long confirm = -1;
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
    private float producerRateLimit = 0;
    private float consumerRateLimit = 0;
    private int producerMsgCount = 0;
    private int consumerMsgCount = 0;

    private String exchangeName = "direct";
    private String exchangeType = "direct";
    private List<String> queueNames = new ArrayList<String>();
    private String routingKey = null;
    private boolean randomRoutingKey = false;

    private List<?> flags = new ArrayList<Object>();

    private int multiAckEvery = 0;
    private boolean autoAck = true;
    private boolean autoDelete = false;

    private List<String> bodyFiles = new ArrayList<String>();
    private String bodyContentType = null;

    private boolean predeclared;

    private Map<String, Object> queueArguments;

    private int consumerLatencyInMicroseconds;

    public void setExchangeType(String exchangeType) {
        this.exchangeType = exchangeType;
    }

    public void setExchangeName(String exchangeName) {
        this.exchangeName = exchangeName;
    }

    public void setQueueNames(List<String> queueNames) {
        if(queueNames == null) {
            this.queueNames = new ArrayList<String>();
        } else {
            this.queueNames = new ArrayList<String>(queueNames);
        }
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public void setRandomRoutingKey(boolean randomRoutingKey) {
        this.randomRoutingKey = randomRoutingKey;
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

    public void setProducerTxSize(int producerTxSize) {
        this.producerTxSize = producerTxSize;
    }

    public void setConsumerTxSize(int consumerTxSize) {
        this.consumerTxSize = consumerTxSize;
    }

    public void setConfirm(long confirm) {
        this.confirm = confirm;
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

    public int getConsumerCount() {
        return consumerCount;
    }

    public int getConsumerChannelCount() {
        return consumerChannelCount;
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

    public String getRoutingKey() {
        return routingKey;
    }

    public boolean getRandomRoutingKey() {
        return randomRoutingKey;
    }

    public void setBodyFiles(List<String> bodyFiles) {
        if (bodyFiles == null) {
            this.bodyFiles = new ArrayList<String>();
        } else {
            this.bodyFiles = new ArrayList<String>(bodyFiles);
        }
    }

    public void setBodyContentType(String bodyContentType) {
        this.bodyContentType = bodyContentType;
    }

    public Producer createProducer(Connection connection, Stats stats, String id) throws IOException {
        Channel channel = connection.createChannel();
        if (producerTxSize > 0) channel.txSelect();
        if (confirm >= 0) channel.confirmSelect();
        if (!predeclared || !exchangeExists(connection, exchangeName)) {
            channel.exchangeDeclare(exchangeName, exchangeType);
        }
        MessageBodySource messageBodySource = null;
        if (bodyFiles.size() > 0) {
            messageBodySource = new LocalFilesMessageBodySource(bodyFiles, bodyContentType);
        } else {
            messageBodySource = new TimeSequenceMessageBodySource(minMsgSize);
        }
        final Producer producer = new Producer(channel, exchangeName, id,
                                               randomRoutingKey, flags, producerTxSize,
                                               producerRateLimit, producerMsgCount,
                                               timeLimit,
                                               confirm, messageBodySource, stats);
        channel.addReturnListener(producer);
        channel.addConfirmListener(producer);
        return producer;
    }

    public Consumer createConsumer(Connection connection, Stats stats, String id) throws IOException {
        Channel channel = connection.createChannel();
        if (consumerTxSize > 0) channel.txSelect();
        List<String> generatedQueueNames = configureQueues(connection, id);
        if (consumerPrefetch > 0) channel.basicQos(consumerPrefetch);
        if (channelPrefetch > 0) channel.basicQos(channelPrefetch, true);
        return new Consumer(channel, id, generatedQueueNames,
                                         consumerTxSize, autoAck, multiAckEvery,
                                         stats, consumerRateLimit, consumerMsgCount, timeLimit, consumerLatencyInMicroseconds);
    }

    public boolean shouldConfigureQueues() {
        // don't declare any queues when --predeclared is passed,
        // otherwise unwanted server-named queues without consumers will pile up. MK.
        return consumerCount == 0 && !predeclared && !(queueNames.size() == 0);
    }

    public List<String> configureQueues(Connection connection, String id) throws IOException {
        Channel channel = connection.createChannel();
        if (!predeclared || !exchangeExists(connection, exchangeName)) {
            channel.exchangeDeclare(exchangeName, exchangeType);
        }
        // To ensure we get at-least 1 default queue:
        if (queueNames.isEmpty()) {
            queueNames.add("");
        }
        List<String> generatedQueueNames = new ArrayList<String>();
        for (String qName : queueNames) {
            if (!predeclared || !queueExists(connection, qName)) {
                qName = channel.queueDeclare(qName,
                                     flags.contains("persistent"),
                                     false,
                                     autoDelete,
                                     queueArguments).getQueue();
            }
            generatedQueueNames.add(qName);
            channel.queueBind(qName, exchangeName, id);
        }
        channel.abort();

        return generatedQueueNames;
    }

    private static boolean exchangeExists(Connection connection, final String exchangeName) throws IOException {
        return exists(connection, new Checker() {
            public void check(Channel ch) throws IOException {
                ch.exchangeDeclarePassive(exchangeName);
            }
        });
    }

    private static boolean queueExists(Connection connection, final String queueName) throws IOException {
        return queueName != null && exists(connection, new Checker() {
            public void check(Channel ch) throws IOException {
                ch.queueDeclarePassive(queueName);
            }
        });
    }

    private static interface Checker {
        public void check(Channel ch) throws IOException;
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
}
