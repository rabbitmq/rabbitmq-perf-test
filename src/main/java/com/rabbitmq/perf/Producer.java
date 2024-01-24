// Copyright (c) 2007-2023 Broadcom. All Rights Reserved.
// The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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

import static java.util.stream.Collectors.toMap;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.perf.StartListener.Type;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Producer extends AgentBase implements Runnable, ReturnListener, ConfirmListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(Producer.class);

  public static final String TIMESTAMP_PROPERTY = "timestamp";
  public static final String CONTENT_TYPE_PROPERTY = "contentType";
  public static final String CONTENT_ENCODING_PROPERTY = "contentEncoding";
  public static final String DELIVERY_MODE_PROPERTY = "deliveryMode";
  public static final String PRIORITY_PROPERTY = "priority";
  public static final String CORRELATION_ID_PROPERTY = "correlationId";
  public static final String REPLY_TO_PROPERTY = "replyTo";
  public static final String EXPIRATION_PROPERTY = "expiration";
  public static final String MESSAGE_ID_PROPERTY = "messageId";
  public static final String TYPE_PROPERTY = "type";
  public static final String USER_ID_PROPERTY = "userId";
  public static final String APP_ID_PROPERTY = "appId";
  public static final String CLUSTER_ID_PROPERTY = "clusterId";
  public static final String TIMESTAMP_HEADER = TIMESTAMP_PROPERTY;
  static final String STOP_REASON_PRODUCER_MESSAGE_LIMIT = "Producer reached message limit";
  static final String STOP_REASON_PRODUCER_THREAD_INTERRUPTED = "Producer thread interrupted";
  static final String STOP_REASON_ERROR_IN_PRODUCER = "Error in producer";
  private final Channel channel;
  private final String exchangeName;
  private final boolean mandatory;
  private final boolean persistent;
  private final int txSize;
  private final int msgLimit;

  private final PerformanceMetrics performanceMetrics;

  private final MessageBodySource messageBodySource;

  private final Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder>
      propertiesBuilderProcessor;
  private final Semaphore confirmPool;
  private final int confirmTimeout;
  private final int maxOutstandingConfirms;

  private final ConcurrentNavigableMap<Long, Long> unconfirmed = new ConcurrentSkipListMap<>();

  private final MulticastSet.CompletionHandler completionHandler;
  private final AtomicBoolean completed = new AtomicBoolean(false);

  private final Supplier<String> routingKeyGenerator;

  private final int randomStartDelay;

  private final Recovery.RecoveryProcess recoveryProcess;

  private final boolean shouldTrackPublishConfirms;

  private final TimestampProvider timestampProvider;

  private final ValueIndicator<Float> rateIndicator;

  private final Runnable rateLimiterCallback;

  public Producer(ProducerParameters parameters) {
    super(
        parameters.getStartListener(),
        parameters.getRoutingKey(),
        parameters.getId(),
        parameters.getFunctionalLogger());
    this.channel = parameters.getChannel();
    this.exchangeName = parameters.getExchangeName();
    this.mandatory = parameters.getFlags().contains("mandatory");
    this.persistent = parameters.getFlags().contains("persistent");

    Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> builderProcessor =
        Function.identity();
    this.txSize = parameters.getTxSize();
    this.msgLimit = parameters.getMsgLimit();
    this.messageBodySource = parameters.getMessageBodySource();
    this.timestampProvider = parameters.getTsp();
    if (this.timestampProvider.isTimestampInHeader()) {
      builderProcessor =
          builderProcessor.andThen(
              builder ->
                  builder.headers(
                      Collections.singletonMap(
                          TIMESTAMP_HEADER, parameters.getTsp().getCurrentTime())));
    }
    if (parameters.getMessageProperties() != null && !parameters.getMessageProperties().isEmpty()) {
      builderProcessor =
          builderProcessorWithMessageProperties(
              parameters.getMessageProperties(), builderProcessor);
    }

    this.shouldTrackPublishConfirms = shouldTrackPublishConfirm(parameters);

    if (parameters.getConfirm() > 0) {
      this.confirmPool = new Semaphore((int) parameters.getConfirm());
      this.confirmTimeout = parameters.getConfirmTimeout();
      this.maxOutstandingConfirms = (int) parameters.getConfirm();
    } else {
      this.confirmPool = null;
      this.confirmTimeout = -1;
      this.maxOutstandingConfirms = -1;
    }
    this.performanceMetrics = parameters.getPerformanceMetrics();
    this.completionHandler = parameters.getCompletionHandler();
    this.propertiesBuilderProcessor = builderProcessor;
    if (parameters.isRandomRoutingKey() || parameters.getRoutingKeyCacheSize() > 0) {
      if (parameters.getRoutingKeyCacheSize() > 0) {
        this.routingKeyGenerator =
            new CachingRoutingKeyGenerator(parameters.getRoutingKeyCacheSize());
      } else {
        this.routingKeyGenerator = () -> UUID.randomUUID().toString();
      }
    } else {
      this.routingKeyGenerator = () -> this.routingKey;
    }
    this.randomStartDelay = parameters.getRandomStartDelayInSeconds();

    this.rateIndicator = parameters.getRateIndicator();
    this.recoveryProcess = parameters.getRecoveryProcess();
    this.recoveryProcess.init(this);
    final RateLimiter.Factory rateLimiterFactory = parameters.getRateLimiterFactory();
    if (this.rateIndicator.getValue() >= 0 && this.rateIndicator.isVariable()) {
      RateLimiter rateLimiter =
          rateLimiterFactory.create(
              this.rateIndicator.getValue() > 0 ? this.rateIndicator.getValue() : 1);
      AtomicReference<RateLimiter> rateLimiterReference = new AtomicReference<>(rateLimiter);
      this.rateIndicator.register(
          (oldValue, newValue) -> {
            if (newValue > 0) {
              rateLimiterReference.set(rateLimiterFactory.create(newValue));
            }
          });
      this.rateLimiterCallback = () -> rateLimiterReference.get().acquire();
    } else if (this.rateIndicator.getValue() >= 0 && !this.rateIndicator.isVariable()) {
      if (this.rateIndicator.getValue() > 0) {
        RateLimiter rateLimiter = rateLimiterFactory.create(this.rateIndicator.getValue());
        this.rateLimiterCallback = rateLimiter::acquire;
      } else {
        this.rateLimiterCallback = () -> {};
      }
    } else {
      this.rateLimiterCallback = () -> {};
    }
  }

  private Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder>
      builderProcessorWithMessageProperties(
          Map<String, Object> messageProperties,
          Function<AMQP.BasicProperties.Builder, AMQP.BasicProperties.Builder> builderProcessor) {
    if (messageProperties.containsKey(CONTENT_TYPE_PROPERTY)) {
      String value = messageProperties.get(CONTENT_TYPE_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.contentType(value));
    }
    if (messageProperties.containsKey(CONTENT_ENCODING_PROPERTY)) {
      String value = messageProperties.get(CONTENT_ENCODING_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.contentEncoding(value));
    }
    if (messageProperties.containsKey(DELIVERY_MODE_PROPERTY)) {
      Integer value = ((Number) messageProperties.get(DELIVERY_MODE_PROPERTY)).intValue();
      builderProcessor = builderProcessor.andThen(builder -> builder.deliveryMode(value));
    }
    if (messageProperties.containsKey(PRIORITY_PROPERTY)) {
      Integer value = ((Number) messageProperties.get(PRIORITY_PROPERTY)).intValue();
      builderProcessor = builderProcessor.andThen(builder -> builder.priority(value));
    }
    if (messageProperties.containsKey(CORRELATION_ID_PROPERTY)) {
      String value = messageProperties.get(CORRELATION_ID_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.correlationId(value));
    }
    if (messageProperties.containsKey(REPLY_TO_PROPERTY)) {
      String value = messageProperties.get(REPLY_TO_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.replyTo(value));
    }
    if (messageProperties.containsKey(EXPIRATION_PROPERTY)) {
      String value = messageProperties.get(EXPIRATION_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.expiration(value));
    }
    if (messageProperties.containsKey(MESSAGE_ID_PROPERTY)) {
      String value = messageProperties.get(MESSAGE_ID_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.messageId(value));
    }
    if (messageProperties.containsKey(TIMESTAMP_PROPERTY)) {
      String value = messageProperties.get(TIMESTAMP_PROPERTY).toString();
      Date timestamp = Date.from(OffsetDateTime.parse(value).toInstant());
      builderProcessor = builderProcessor.andThen(builder -> builder.timestamp(timestamp));
    }
    if (messageProperties.containsKey(TYPE_PROPERTY)) {
      String value = messageProperties.get(TYPE_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.type(value));
    }
    if (messageProperties.containsKey(USER_ID_PROPERTY)) {
      String value = messageProperties.get(USER_ID_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.userId(value));
    }
    if (messageProperties.containsKey(APP_ID_PROPERTY)) {
      String value = messageProperties.get(APP_ID_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.appId(value));
    }
    if (messageProperties.containsKey(CLUSTER_ID_PROPERTY)) {
      String value = messageProperties.get(CLUSTER_ID_PROPERTY).toString();
      builderProcessor = builderProcessor.andThen(builder -> builder.clusterId(value));
    }

    final Map<String, Object> headers =
        messageProperties.entrySet().stream()
            .filter(entry -> !isPropertyKey(entry.getKey()))
            .collect(toMap(e -> e.getKey(), e -> e.getValue()));

    if (!headers.isEmpty()) {
      builderProcessor =
          builderProcessor.andThen(
              builder -> {
                // we merge if there are already some headers
                AMQP.BasicProperties properties = builder.build();
                Map<String, Object> existingHeaders = properties.getHeaders();
                if (existingHeaders != null && !existingHeaders.isEmpty()) {
                  Map<String, Object> newHeaders = new HashMap<>();
                  newHeaders.putAll(existingHeaders);
                  newHeaders.putAll(headers);
                  builder = builder.headers(newHeaders);
                } else {
                  builder = builder.headers(headers);
                }
                return builder;
              });
    }

    return builderProcessor;
  }

  @Override
  protected Type type() {
    return Type.PRODUCER;
  }

  private static final Collection<String> MESSAGE_PROPERTIES_KEYS =
      Arrays.asList(
          CONTENT_TYPE_PROPERTY,
          CONTENT_ENCODING_PROPERTY,
          "headers",
          DELIVERY_MODE_PROPERTY,
          PRIORITY_PROPERTY,
          CORRELATION_ID_PROPERTY,
          REPLY_TO_PROPERTY,
          EXPIRATION_PROPERTY,
          MESSAGE_ID_PROPERTY,
          TIMESTAMP_HEADER,
          TYPE_PROPERTY,
          USER_ID_PROPERTY,
          APP_ID_PROPERTY,
          CLUSTER_ID_PROPERTY);

  private boolean isPropertyKey(String key) {
    return MESSAGE_PROPERTIES_KEYS.contains(key);
  }

  private boolean shouldTrackPublishConfirm(ProducerParameters parameters) {
    return parameters.getConfirm() > 0;
  }

  public void handleReturn(
      int replyCode,
      String replyText,
      String exchange,
      String routingKey,
      AMQP.BasicProperties properties,
      byte[] body) {
    this.performanceMetrics.returned();
  }

  public void handleAck(long seqNo, boolean multiple) {
    handleAckNack(seqNo, multiple, false);
  }

  public void handleNack(long seqNo, boolean multiple) {
    handleAckNack(seqNo, multiple, true);
  }

  private void handleAckNack(long seqNo, boolean multiple, boolean nack) {
    int numConfirms;

    if (nack) {
      numConfirms = processNack(seqNo, multiple);
    } else {
      numConfirms = processAck(seqNo, multiple);
    }

    if (confirmPool != null && numConfirms > 0) {
      confirmPool.release(numConfirms);
    }
  }

  private int processAck(long seqNo, boolean multiple) {
    int numConfirms;
    long currentTime = this.timestampProvider.getCurrentTime();
    long[] latencies;
    if (multiple) {
      ConcurrentNavigableMap<Long, Long> confirmed = unconfirmed.headMap(seqNo, true);
      numConfirms = confirmed.size();
      logger().receivedPublishConfirm(this.id, true, seqNo, numConfirms);
      latencies = new long[numConfirms];
      int index = 0;
      for (Map.Entry<Long, Long> entry : confirmed.entrySet()) {
        logger().publishConfirmed(this.id, true, entry.getKey(), entry.getValue());
        latencies[index] = this.timestampProvider.getDifference(currentTime, entry.getValue());
        index++;
      }
      confirmed.clear();
    } else {
      logger().receivedPublishConfirm(this.id, true, seqNo, 1);
      Long messageTimestamp = unconfirmed.remove(seqNo);
      if (messageTimestamp != null) {
        latencies =
            new long[] {this.timestampProvider.getDifference(currentTime, messageTimestamp)};
        logger().publishConfirmed(this.id, true, seqNo, messageTimestamp);
      } else {
        latencies = new long[0];
      }
      numConfirms = 1;
    }
    this.performanceMetrics.confirmed(numConfirms, latencies);
    return numConfirms;
  }

  private int processNack(long seqNo, boolean multiple) {
    int numConfirms;
    if (multiple) {
      ConcurrentNavigableMap<Long, Long> confirmed = unconfirmed.headMap(seqNo, true);
      numConfirms = confirmed.size();
      logger().receivedPublishConfirm(this.id, false, seqNo, numConfirms);
      if (!logger().isNoOp()) {
        for (Map.Entry<Long, Long> entry : confirmed.entrySet()) {
          logger().publishConfirmed(this.id, false, entry.getKey(), entry.getValue());
        }
      }
      confirmed.clear();
    } else {
      Long messageTimestamp = unconfirmed.remove(seqNo);
      numConfirms = 1;
      logger().receivedPublishConfirm(this.id, false, seqNo, numConfirms);
      logger().publishConfirmed(this.id, false, seqNo, messageTimestamp);
    }
    this.performanceMetrics.nacked(numConfirms);
    return numConfirms;
  }

  public void run() {
    if (randomStartDelay > 0) {
      // in ms
      int delay = new Random().nextInt(randomStartDelay * 1000) + 1;
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    long now;
    final long startTime;
    startTime = System.nanoTime();
    ProducerState state = new ProducerState();
    state.setLastStatsTime(startTime);
    state.setMsgCount(0);
    final boolean variableRate = this.rateIndicator.isVariable();
    started();
    try {
      while (keepGoing(state)) {
        rateLimiterCallback.run();
        if (variableRate && this.rateIndicator.getValue() == 0.0f) {
          // instructed not to publish, so waiting
          waitForOneSecond();
        } else {
          handlePublish(state);
        }
        now = System.nanoTime();
        // if rate is variable, we need to reset producer stats every second
        // otherwise pausing to throttle rate will be based on the whole history
        // which is broken when rate varies
        if (variableRate && now - state.getLastStatsTime() > 1000) {
          state.setLastStatsTime(now);
          state.setMsgCount(0);
        }
      }
    } catch (PerfTestException pte) {
      countDown(pte.getMessage());
      throw pte;
    } catch (Exception e) {
      LOGGER.debug("Error in publisher", e);
      String reason;
      if (e.getCause() instanceof InterruptedException && this.rateIndicator.getValue() != 0.0f) {
        // likely to have been interrupted while sleeping to honor rate limit
        reason = STOP_REASON_PRODUCER_THREAD_INTERRUPTED;
      } else {
        reason = STOP_REASON_ERROR_IN_PRODUCER + " (" + e.getMessage() + ")";
      }
      // failing, we don't want to block the whole process, so counting down
      countDown(reason);
      throw e;
    }
    if (state.getMsgCount() >= msgLimit) {
      String reason;
      if (msgLimit == 0) {
        reason = STOP_REASON_PRODUCER_THREAD_INTERRUPTED;
      } else {
        reason = STOP_REASON_PRODUCER_MESSAGE_LIMIT;
        LOGGER.debug("Producer reached message limit of {}", this.msgLimit);
        maybeWaitForPublishConfirms();
      }
      countDown(reason);
    }
  }

  private void maybeWaitForPublishConfirms() {
    if (confirmPool != null) {
      LOGGER.debug("Publish confirms enabled, making sure all messages have been confirmed");
      LOGGER.debug("Outstanding publish confirm(s): {}", this.unconfirmed.size());
      long timeout = this.confirmTimeout * 1000;
      long waited = 0;
      long waitTime = 100;
      while (waited <= timeout) {
        if (this.unconfirmed.isEmpty()) {
          LOGGER.debug("All messages have been confirmed, moving on...");
          waited = timeout;
        }
        try {
          Thread.sleep(waitTime);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          waited = timeout;
        }
        waited += waitTime;
      }
      if (waited > timeout) {
        LOGGER.debug("Unconfirmed message(s): {}", this.unconfirmed.size());
      }
    }
  }

  private void waitForOneSecond() {
    try {
      Thread.sleep(1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private boolean keepGoing(AgentState state) {
    return (msgLimit == 0 || state.getMsgCount() < msgLimit) && !Thread.interrupted();
  }

  public Runnable createRunnableForScheduling() {
    final AtomicBoolean initialized = new AtomicBoolean(false);
    // make the producer state thread-safe for what we use in this case
    final ProducerState state =
        new ProducerState() {
          final AtomicInteger messageCount = new AtomicInteger(0);

          @Override
          protected void setMsgCount(int msgCount) {
            messageCount.set(msgCount);
          }

          @Override
          public int getMsgCount() {
            return messageCount.get();
          }

          @Override
          public int incrementMessageCount() {
            return messageCount.incrementAndGet();
          }
        };
    return () -> {
      if (initialized.compareAndSet(false, true)) {
        state.setLastStatsTime(System.nanoTime());
        state.setMsgCount(0);
        started();
      }
      try {
        maybeHandlePublish(state);
      } catch (PerfTestException pte) {
        // failing, we don't want to block the whole process, so counting down
        countDown(pte.getMessage());
        throw pte;
      } catch (Exception e) {
        // failing, we don't want to block the whole process, so counting down
        countDown("Error in scheduled producer (" + e.getMessage() + ")");
        throw e;
      }
    };
  }

  public void maybeHandlePublish(AgentState state) {
    if (keepGoing(state)) {
      handlePublish(state);
    } else {
      String reason;
      if (messageLimitReached(state)) {
        reason = STOP_REASON_PRODUCER_MESSAGE_LIMIT;
        LOGGER.debug("Producer reached message limit of {}", this.msgLimit);
        maybeWaitForPublishConfirms();
      } else {
        reason = STOP_REASON_PRODUCER_THREAD_INTERRUPTED;
      }
      countDown(reason);
    }
  }

  private boolean messageLimitReached(AgentState state) {
    if (msgLimit == 0) {
      return false;
    } else {
      return state.getMsgCount() >= msgLimit;
    }
  }

  public void handlePublish(AgentState currentState) {
    if (!this.recoveryProcess.isRecoverying()) {
      try {
        maybeWaitIfTooManyOutstandingPublishConfirms();

        dealWithWriteOperation(
            () -> publish(messageBodySource.create(currentState.getMsgCount())),
            this.recoveryProcess);

        int messageCount = currentState.incrementMessageCount();

        commitTransactionIfNecessary(messageCount);
        this.performanceMetrics.published();
      } catch (IOException e) {
        throw new RuntimeException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    } else {
      // The connection is recovering, waiting a bit.
      // The duration is arbitrary: don't want to empty loop
      // too much and don't want to catch too late with recovery
      try {
        LOGGER.debug("Recovery in progress, sleeping for a sec");
        Thread.sleep(1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void maybeWaitIfTooManyOutstandingPublishConfirms() throws InterruptedException {
    if (confirmPool != null) {
      if (confirmTimeout < 0) {
        confirmPool.acquire();
      } else {
        boolean acquired = confirmPool.tryAcquire(confirmTimeout, TimeUnit.SECONDS);
        if (!acquired) {
          // waiting for too long, broker may be gone, stopping thread
          throw new PerfTestException("Waiting for publisher confirms for too long");
        }
      }
    }
  }

  private void commitTransactionIfNecessary(int messageCount) throws IOException {
    if (txSize != 0 && messageCount % txSize == 0) {
      dealWithWriteOperation(() -> channel.txCommit(), this.recoveryProcess);
    }
  }

  private void publish(MessageBodySource.MessageEnvelope messageEnvelope) throws IOException {

    AMQP.BasicProperties.Builder propertiesBuilder = new AMQP.BasicProperties.Builder();
    if (persistent) {
      propertiesBuilder.deliveryMode(2);
    }

    if (messageEnvelope.getContentType() != null) {
      propertiesBuilder.contentType(messageEnvelope.getContentType());
    }

    propertiesBuilder = this.propertiesBuilderProcessor.apply(propertiesBuilder);

    AMQP.BasicProperties messageProperties = propertiesBuilder.build();

    long timestamp = 0;
    long publishindId = 0;
    if (shouldTrackPublishConfirms) {
      timestamp = timestamp(messageProperties, messageEnvelope);
      publishindId = channel.getNextPublishSeqNo();
      unconfirmed.put(publishindId, timestamp);
    }

    channel.basicPublish(
        exchangeName,
        routingKeyGenerator.get(),
        mandatory,
        false,
        messageProperties,
        messageEnvelope.getBody());
    if (!this.shouldTrackPublishConfirms && !logger().isNoOp()) {
      // the timestamp has not been extracted and we need it for the functional logger
      timestamp = timestamp(messageProperties, messageEnvelope);
    }
    logger()
        .published(this.id, timestamp, publishindId, messageProperties, messageEnvelope.getBody());
  }

  private long timestamp(
      AMQP.BasicProperties messageProperties, MessageBodySource.MessageEnvelope envelope) {
    if (this.timestampProvider.isTimestampInHeader()) {
      return (Long) messageProperties.getHeaders().get(TIMESTAMP_HEADER);
    } else {
      return envelope.getTime();
    }
  }

  private void countDown(String reason) {
    if (completed.compareAndSet(false, true)) {
      completionHandler.countDown(reason);
    }
  }

  @Override
  public void recover(TopologyRecording topologyRecording) {
    maybeResetConfirmPool();
  }

  private void maybeResetConfirmPool() {
    if (this.confirmPool != null) {
      // reset confirm pool. If the producer is waiting for confirms,
      // it will move on without failing because of a confirm timeout, which is good,
      // considering there has been a re-connection.
      int usedPermits = maxOutstandingConfirms - this.confirmPool.availablePermits();
      this.confirmPool.release(usedPermits);
      LOGGER.debug(
          "Resetting confirm pool in producer, used permit(s) {}, now {} available",
          usedPermits,
          this.confirmPool.availablePermits());
    }
  }

  /** Not thread-safe (OK for non-scheduled Producer, as it runs inside the same thread). */
  private static class ProducerState implements AgentState {

    private long lastStatsTime;
    private int msgCount = 0;

    public long getLastStatsTime() {
      return lastStatsTime;
    }

    protected void setLastStatsTime(long lastStatsTime) {
      this.lastStatsTime = lastStatsTime;
    }

    public int getMsgCount() {
      return msgCount;
    }

    protected void setMsgCount(int msgCount) {
      this.msgCount = msgCount;
    }

    public int incrementMessageCount() {
      return ++this.msgCount;
    }
  }

  static class CachingRoutingKeyGenerator implements Supplier<String> {

    private final String[] keys;
    private int count = 0;

    public CachingRoutingKeyGenerator(int cacheSize) {
      if (cacheSize <= 0) {
        throw new IllegalArgumentException(String.valueOf(cacheSize));
      }
      this.keys = new String[cacheSize];
      for (int i = 0; i < cacheSize; i++) {
        this.keys[i] = UUID.randomUUID().toString();
      }
    }

    @Override
    public String get() {
      if (count == keys.length) {
        count = 0;
      }
      return keys[count++ % keys.length];
    }
  }
}
