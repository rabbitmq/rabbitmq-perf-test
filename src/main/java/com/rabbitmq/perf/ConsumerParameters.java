// Copyright (c) 2019-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom
// Inc. and/or its subsidiaries.
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
import com.rabbitmq.perf.PerfTest.EXIT_WHEN;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @since 2.8.0
 */
public class ConsumerParameters {

  private Channel channel;
  private String routingKey;
  private List<String> queueNames;
  private int txSize;
  private boolean autoAck;
  private int multiAckEvery;
  private PerformanceMetrics performanceMetrics;
  private float rateLimit;
  private int msgLimit;
  private ValueIndicator<Long> consumerLatenciesIndicator;
  private TimestampProvider timestampProvider;
  private MulticastSet.CompletionHandler completionHandler;
  private Recovery.RecoveryProcess recoveryProcess;
  private ExecutorService executorService;
  private boolean polling;
  private boolean nack = false;
  private boolean requeue = true;

  private int pollingInterval;

  private EXIT_WHEN exitWhen = EXIT_WHEN.NEVER;

  private Map<String, Object> consumerArguments = null;

  private ScheduledExecutorService topologyRecoveryScheduledExecutorService;

  private StartListener startListener = StartListener.NO_OP;

  private RateLimiter.Factory rateLimiterFactory = RateLimiter.Type.GUAVA.factory();

  private int id;
  private FunctionalLogger functionalLogger = FunctionalLogger.NO_OP;

  public Channel getChannel() {
    return channel;
  }

  public ConsumerParameters setChannel(Channel channel) {
    this.channel = channel;
    return this;
  }

  public String getRoutingKey() {
    return routingKey;
  }

  public ConsumerParameters setRoutingKey(String routingKey) {
    this.routingKey = routingKey;
    return this;
  }

  public List<String> getQueueNames() {
    return queueNames;
  }

  public ConsumerParameters setQueueNames(List<String> queueNames) {
    this.queueNames = queueNames;
    return this;
  }

  public int getTxSize() {
    return txSize;
  }

  public ConsumerParameters setTxSize(int txSize) {
    this.txSize = txSize;
    return this;
  }

  public boolean isAutoAck() {
    return autoAck;
  }

  public ConsumerParameters setAutoAck(boolean autoAck) {
    this.autoAck = autoAck;
    return this;
  }

  public int getMultiAckEvery() {
    return multiAckEvery;
  }

  public ConsumerParameters setMultiAckEvery(int multiAckEvery) {
    this.multiAckEvery = multiAckEvery;
    return this;
  }

  public PerformanceMetrics getPerformanceMetrics() {
    return performanceMetrics;
  }

  public ConsumerParameters setPerformanceMetrics(PerformanceMetrics performanceMetrics) {
    this.performanceMetrics = performanceMetrics;
    return this;
  }

  public float getRateLimit() {
    return rateLimit;
  }

  public ConsumerParameters setRateLimit(float rateLimit) {
    this.rateLimit = rateLimit;
    return this;
  }

  public int getMsgLimit() {
    return msgLimit;
  }

  public ConsumerParameters setMsgLimit(int msgLimit) {
    this.msgLimit = msgLimit;
    return this;
  }

  public ValueIndicator<Long> getConsumerLatenciesIndicator() {
    return consumerLatenciesIndicator;
  }

  public ConsumerParameters setConsumerLatencyIndicator(
      ValueIndicator<Long> consumerLatenciesIndicator) {
    this.consumerLatenciesIndicator = consumerLatenciesIndicator;
    return this;
  }

  public TimestampProvider getTimestampProvider() {
    return timestampProvider;
  }

  public ConsumerParameters setTimestampProvider(TimestampProvider timestampProvider) {
    this.timestampProvider = timestampProvider;
    return this;
  }

  public MulticastSet.CompletionHandler getCompletionHandler() {
    return completionHandler;
  }

  public ConsumerParameters setCompletionHandler(MulticastSet.CompletionHandler completionHandler) {
    this.completionHandler = completionHandler;
    return this;
  }

  public Recovery.RecoveryProcess getRecoveryProcess() {
    return recoveryProcess;
  }

  public ConsumerParameters setRecoveryProcess(Recovery.RecoveryProcess recoveryProcess) {
    this.recoveryProcess = recoveryProcess;
    return this;
  }

  public ExecutorService getExecutorService() {
    return executorService;
  }

  public ConsumerParameters setExecutorService(ExecutorService executorService) {
    this.executorService = executorService;
    return this;
  }

  public boolean isPolling() {
    return polling;
  }

  public ConsumerParameters setPolling(boolean polling) {
    this.polling = polling;
    return this;
  }

  public int getPollingInterval() {
    return pollingInterval;
  }

  public ConsumerParameters setPollingInterval(int pollingInterval) {
    this.pollingInterval = pollingInterval;
    return this;
  }

  public boolean isNack() {
    return nack;
  }

  public ConsumerParameters setNack(boolean nack) {
    this.nack = nack;
    return this;
  }

  public boolean isRequeue() {
    return requeue;
  }

  public ConsumerParameters setRequeue(boolean requeue) {
    this.requeue = requeue;
    return this;
  }

  public ConsumerParameters setConsumerArguments(Map<String, Object> consumerArguments) {
    this.consumerArguments = consumerArguments;
    return this;
  }

  public Map<String, Object> getConsumerArguments() {
    return consumerArguments;
  }

  public ConsumerParameters setExitWhen(EXIT_WHEN exitWhen) {
    this.exitWhen = exitWhen;
    return this;
  }

  public EXIT_WHEN getExitWhen() {
    return exitWhen;
  }

  ConsumerParameters setTopologyRecoveryScheduledExecutorService(
      ScheduledExecutorService topologyRecoveryScheduledExecutorService) {
    this.topologyRecoveryScheduledExecutorService = topologyRecoveryScheduledExecutorService;
    return this;
  }

  public ScheduledExecutorService getTopologyRecoveryScheduledExecutorService() {
    return topologyRecoveryScheduledExecutorService;
  }

  public StartListener getStartListener() {
    return startListener;
  }

  public ConsumerParameters setStartListener(StartListener startListener) {
    this.startListener = startListener;
    return this;
  }

  public ConsumerParameters setRateLimiterFactory(RateLimiter.Factory rateLimiterFactory) {
    this.rateLimiterFactory = rateLimiterFactory;
    return this;
  }

  public RateLimiter.Factory getRateLimiterFactory() {
    return rateLimiterFactory;
  }

  public ConsumerParameters setId(int id) {
    this.id = id;
    return this;
  }

  public int getId() {
    return id;
  }

  public ConsumerParameters setFunctionalLogger(FunctionalLogger functionalLogger) {
    this.functionalLogger = functionalLogger;
    return this;
  }

  public FunctionalLogger getFunctionalLogger() {
    return functionalLogger;
  }
}
