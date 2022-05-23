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

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.util.Collections;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 *
 */
public class TopologyRecording {

    private static final Logger LOGGER = LoggerFactory.getLogger(TopologyRecording.class);

    private final ConcurrentMap<String, RecordedExchange> exchanges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RecordedQueue> queues = new ConcurrentHashMap<>();
    private final Collection<RecordedBinding> bindings = new CopyOnWriteArrayList<>();
    private final Collection<TopologyRecording> children = new CopyOnWriteArrayList<>();

    private final boolean polling;
    private final boolean cluster;

    /**
     * Create  a {@link TopologyRecording} with or without polling option.
     * <p>
     * The polling option is set to true when <code>basic.get</code> is used to
     * consume messages. This way, auto-delete queues with server-generated
     * names are cleaned on recovery (because they don't go away by themselves).
     *
     * @param polling
     */
    public TopologyRecording(boolean polling, boolean cluster) {
        this.polling = polling;
        this.cluster = cluster;
    }

    private static Channel reliableWrite(Connection connection, Channel channel, WriteOperation operation) throws IOException {
        return reliableWrite(connection, channel, operation, () -> "");
    }

    private static Channel reliableWrite(Connection connection, Channel channel, WriteOperation operation, Supplier<String> message) throws IOException {
        try {
            operation.write(channel);
            return channel;
        } catch (Exception e) {
            LOGGER.warn("Error during topology recovery: {}", e.getMessage());
            String msg = message.get();
            if (msg != null && !msg.isEmpty()) {
               LOGGER.debug(msg);
            }
            return connection.createChannel();
        }
    }

    /**
     * Create a child {@link TopologyRecording} of this instance.
     * <p>
     * A {@link TopologyRecording} keeps a reference of its children, it makes
     * easier to track all the resources created by a {@link TopologyRecording}
     * and its children.
     *
     * @return
     */
    TopologyRecording child() {
        TopologyRecording child = new TopologyRecording(this.polling, this.cluster);
        this.children.add(child);
        return child;
    }

    Collection<RecordedQueue> queues() {
        Collection<RecordedQueue> queues = new ArrayList<>(this.queues.values());
        for (TopologyRecording child : children) {
            queues.addAll(child.queues());
        }
        return queues;
    }

    public RecordedExchange recordExchange(String name, String type) {
        exchanges.putIfAbsent(name, new RecordedExchange(name, type));
        return exchanges.get(name);
    }

    public RecordedQueue recordQueue(String name, boolean durable, boolean exclusive, boolean autoDelete, Map<String, Object> arguments, boolean serverNamed) {
        queues.putIfAbsent(name, new RecordedQueue(name, durable, exclusive, autoDelete, arguments, serverNamed));
        return queues.get(name);
    }

    public RecordedBinding recordBinding(String queue, String exchange, String routingKey) {
        RecordedBinding binding = new RecordedBinding(queue, exchange, routingKey);
        bindings.add(binding);
        return binding;
    }

    public RecordedQueue queue(String name) {
        return queues.get(name);
    }

    public RecordedExchange exchange(String name) {
        return exchanges.get(name);
    }

    private Collection<RecordedBinding> getBindingsFor(String queue) {
        return bindings.stream().filter(b -> b.queue.equals(queue)).collect(Collectors.toList());
    }

    /**
     * Create a subset of the topology of this recording for the passed-in queues.
     *
     * @param queues
     * @return
     */
    public TopologyRecording subRecording(Collection<String> queues) {
        TopologyRecording clientTopologyRecording = this.child();
        for (String queue : queues) {
            RecordedQueue recordedQueue = lookupQueueInHierarchy(queue);
            if (recordedQueue == null) {
                throw new IllegalArgumentException("Not able to sub-record queue " + queue + ", it is not in the parent recording");
            }
            clientTopologyRecording.queues.putIfAbsent(queue, recordedQueue);
            for (TopologyRecording.RecordedBinding binding : this.getBindingsFor(queue)) {
                clientTopologyRecording.bindings.add(binding);
                clientTopologyRecording.exchanges.put(binding.getExchange(), exchanges.get(binding.getExchange()));
            }
        }
        return clientTopologyRecording;
    }

    private RecordedQueue lookupQueueInHierarchy(String queue) {
        RecordedQueue recordedQueue = this.queues.get(queue);
        if (recordedQueue == null) {
            for (TopologyRecording child : this.children) {
                recordedQueue = child.lookupQueueInHierarchy(queue);
                if (recordedQueue != null) {
                    break;
                }
            }
        }
        return recordedQueue;
    }

    public void recover(Connection connection) {
        try {
            Channel channel = connection.createChannel();
            for (Map.Entry<String, RecordedQueue> entry : queues.entrySet()) {
                RecordedQueue queue = entry.getValue();
                synchronized (queue) {
                    String originalName = queue.name;
                    LOGGER.debug("Connection {}, recovering queue {}", connection.getClientProvidedName(), queue);
                    boolean redeclare = true;
                    // the queue may still be around (e.g. a quorum queue in a cluster)
                    // so checking if it's necessary to create it
                    if (queue.durable && queue.serverNamed && !queue.autoDelete && !queue.exclusive) {
                        // so in this case, the server-named queue won't be renamed
                        try {
                            channel.queueDeclarePassive(queue.name);
                            // the queue exists
                            redeclare = false;
                        } catch (IOException e) {
                            // the queue does not exists
                            redeclare = true;
                            channel = connection.createChannel();
                        }
                    }
                    if (redeclare) {
                        LOGGER.debug("Trying to re-declare queue {}", originalName);
                        channel = reliableWrite(connection, channel, ch -> {
                            String newName = ch.queueDeclare(
                                    queue.serverNamed ? "" : queue.name, queue.durable, queue.exclusive,
                                    queue.autoDelete, queue.arguments
                            ).getQueue();
                            LOGGER.debug("Re-declared queue {}", originalName);
                            queue.name = newName;
                            if (queue.serverNamed) {
                                LOGGER.debug("Queue {} was server-named, it is now {}", originalName, newName);
                            }
                        }, () -> "Error while trying to re-declare queue " + originalName);
                    }

                    LOGGER.debug("Connection {}, recovered queue {}", connection.getClientProvidedName(), queue);

                    // Recovering an auto-delete, server-named queue create a brand new queue, with a new name.
                    // This is not fine with polling, as the former server-named queue is still around: no consumer
                    // registered to it.
                    // so we delete this former queue explicitly when polling.
                    if (this.polling && queue.autoDelete && queue.serverNamed && !queue.exclusive) {
                        channel = reliableWrite(connection, channel, ch -> ch.queueDelete(originalName));
                    }
                }
            }
            for (RecordedExchange exchange : exchanges.values()) {
                LOGGER.debug("Connection {}, recovering exchange {}", connection.getClientProvidedName(), exchange);
                channel = reliableWrite(connection, channel, ch -> Utils.exchangeDeclare(ch, exchange.name, exchange.type));
                LOGGER.debug("Connection {}, recovered exchange {}", connection.getClientProvidedName(), exchange);
            }
            for (RecordedBinding binding : bindings) {
                LOGGER.debug("Connection {}, recovering binding {}", connection.getClientProvidedName(), binding);
                RecordedQueue queue = queues.get(binding.queue);
                String queueName;
                Object lock;
                // we may have not recorded the queue because it's --predeclared
                // we do our best to re-create the binding nevertheless, assuming the queue is still around
                // otherwise we may end with publishers publishing to an exchange without bindings
                if (queue == null) {
                    queueName = binding.queue;
                    lock = new Object();
                } else {
                    queueName = queue.name;
                    lock = queue;
                }
                synchronized (lock) {
                    channel = reliableWrite(connection, channel,
                        ch -> ch.queueBind(queueName, binding.exchange, binding.routingKeyIsQueue() ? queueName : binding.routingKey));
                }
                LOGGER.debug("Connection {}, recovered binding {}", connection.getClientProvidedName(), binding);
            }
            channel.close();
        } catch (Exception e) {
            LOGGER.warn("Error during topology recovery for connection {}: {}", connection.getClientProvidedName(), e.getMessage());
        }
    }

    @FunctionalInterface
    private interface WriteOperation {

        void write(Channel channel) throws IOException;
    }

    boolean isCluster() {
        return this.cluster;
    }

    class RecordedExchange {

        private final String name, type;

        RecordedExchange(String name, String type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String toString() {
            return "RecordedExchange{" +
                    "name='" + name + '\'' +
                    ", type='" + type + '\'' +
                    '}';
        }
    }

    class RecordedQueue {

        private final boolean durable;
        private final boolean exclusive;
        private final boolean autoDelete;
        private final Map<String, Object> arguments;
        private final boolean serverNamed;
        private String name;

        private RecordedQueue(String name, boolean durable, boolean exclusive, boolean autoDelete, Map<String, Object> arguments, boolean serverNamed) {
            this.name = name;
            this.durable = durable;
            this.exclusive = exclusive;
            this.autoDelete = autoDelete;
            this.arguments = arguments == null ? Collections.emptyMap() : arguments;
            this.serverNamed = serverNamed;
        }

        public String name() {
            return this.name;
        }

        public boolean isAutoDelete() {
            return autoDelete;
        }

        public boolean isServerNamed() {
            return serverNamed;
        }

        public boolean isExclusive() {
            return exclusive;
        }

        boolean isQuorum() {
            return "quorum".equals(this.arguments.get("x-queue-type"));
        }

        boolean isStream() {
            return "stream".equals(this.arguments.get("x-queue-type"));
        }

        boolean isClassic() {
            return !isQuorum() && !isStream();
        }

        boolean isDurable() {
            return durable;
        }

        @Override
        public String toString() {
            return "RecordedQueue{" +
                    "name='" + name + '\'' +
                    ", durable=" + durable +
                    ", exclusive=" + exclusive +
                    ", autoDelete=" + autoDelete +
                    ", arguments=" + arguments +
                    ", serverNamed=" + serverNamed +
                    '}';
        }
    }

    class RecordedBinding {

        private final String queue, exchange, routingKey;

        RecordedBinding(String queue, String exchange, String routingKey) {
            this.queue = queue;
            this.exchange = exchange;
            this.routingKey = routingKey;
        }

        public String getExchange() {
            return exchange;
        }

        public boolean routingKeyIsQueue() {
            return queue.equals(routingKey);
        }

        @Override
        public String toString() {
            return "RecordedBinding{" +
                    "queue='" + queue + '\'' +
                    ", exchange='" + exchange + '\'' +
                    ", routingKey='" + routingKey + '\'' +
                    '}';
        }
    }
}
