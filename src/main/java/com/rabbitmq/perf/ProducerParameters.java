// Copyright (c) 2018-2023 Broadcom. All Rights Reserved.
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

import com.rabbitmq.client.Channel;
import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.util.List;
import java.util.Map;

/**
 * @since 2.1.0
 */
public class ProducerParameters {

  private Channel channel;
  private String exchangeName;
  private String routingKey;
  private boolean randomRoutingKey;
  private List<?> flags;
  private int txSize;
  private int msgLimit;
  private long confirm;
  private int confirmTimeout;
  private MessageBodySource messageBodySource;
  private TimestampProvider tsp;
  private PerformanceMetrics performanceMetrics;
  private Map<String, Object> messageProperties;
  private MulticastSet.CompletionHandler completionHandler;
  private int routingKeyCacheSize;
  private int randomStartDelayInSeconds;
  private Recovery.RecoveryProcess recoveryProcess;
  private ValueIndicator<Float> rateIndicator;
  private RateLimiter.Factory rateLimiterFactory = RateLimiter.Type.GUAVA.factory();
  private StartListener startListener = StartListener.NO_OP;
  private int id;
  private FunctionalLogger functionalLogger = FunctionalLogger.NO_OP;

  public Channel getChannel() {
    return channel;
  }

  public ProducerParameters setChannel(Channel channel) {
    this.channel = channel;
    return this;
  }

  public String getExchangeName() {
    return exchangeName;
  }

  public ProducerParameters setExchangeName(String exchangeName) {
    this.exchangeName = exchangeName;
    return this;
  }

  public String getRoutingKey() {
    return routingKey;
  }

  public ProducerParameters setRoutingKey(String routingKey) {
    this.routingKey = routingKey;
    return this;
  }

  public boolean isRandomRoutingKey() {
    return randomRoutingKey;
  }

  public ProducerParameters setRandomRoutingKey(boolean randomRoutingKey) {
    this.randomRoutingKey = randomRoutingKey;
    return this;
  }

  public List<?> getFlags() {
    return flags;
  }

  public ProducerParameters setFlags(List<?> flags) {
    this.flags = flags;
    return this;
  }

  public int getTxSize() {
    return txSize;
  }

  public ProducerParameters setTxSize(int txSize) {
    this.txSize = txSize;
    return this;
  }

  public int getMsgLimit() {
    return msgLimit;
  }

  public ProducerParameters setMsgLimit(int msgLimit) {
    this.msgLimit = msgLimit;
    return this;
  }

  public long getConfirm() {
    return confirm;
  }

  public ProducerParameters setConfirm(long confirm) {
    this.confirm = confirm;
    return this;
  }

  public int getConfirmTimeout() {
    return confirmTimeout;
  }

  public ProducerParameters setConfirmTimeout(int confirmTimeout) {
    this.confirmTimeout = confirmTimeout;
    return this;
  }

  public MessageBodySource getMessageBodySource() {
    return messageBodySource;
  }

  public ProducerParameters setMessageBodySource(MessageBodySource messageBodySource) {
    this.messageBodySource = messageBodySource;
    return this;
  }

  public TimestampProvider getTsp() {
    return tsp;
  }

  public ProducerParameters setTsp(TimestampProvider tsp) {
    this.tsp = tsp;
    return this;
  }

  public PerformanceMetrics getPerformanceMetrics() {
    return performanceMetrics;
  }

  public ProducerParameters setPerformanceMetrics(PerformanceMetrics performanceMetrics) {
    this.performanceMetrics = performanceMetrics;
    return this;
  }

  public Map<String, Object> getMessageProperties() {
    return messageProperties;
  }

  public ProducerParameters setMessageProperties(Map<String, Object> messageProperties) {
    this.messageProperties = messageProperties;
    return this;
  }

  public MulticastSet.CompletionHandler getCompletionHandler() {
    return completionHandler;
  }

  public ProducerParameters setCompletionHandler(MulticastSet.CompletionHandler completionHandler) {
    this.completionHandler = completionHandler;
    return this;
  }

  public int getRoutingKeyCacheSize() {
    return routingKeyCacheSize;
  }

  public ProducerParameters setRoutingKeyCacheSize(int routingKeyCacheSize) {
    this.routingKeyCacheSize = routingKeyCacheSize;
    return this;
  }

  public int getRandomStartDelayInSeconds() {
    return randomStartDelayInSeconds;
  }

  public ProducerParameters setRandomStartDelayInSeconds(int randomStartDelayInSeconds) {
    this.randomStartDelayInSeconds = randomStartDelayInSeconds;
    return this;
  }

  public Recovery.RecoveryProcess getRecoveryProcess() {
    return recoveryProcess;
  }

  public ProducerParameters setRecoveryProcess(Recovery.RecoveryProcess recoveryProcess) {
    this.recoveryProcess = recoveryProcess;
    return this;
  }

  public ValueIndicator<Float> getRateIndicator() {
    return rateIndicator;
  }

  public ProducerParameters setRateIndicator(ValueIndicator<Float> rateIndicator) {
    this.rateIndicator = rateIndicator;
    return this;
  }

  public StartListener getStartListener() {
    return startListener;
  }

  public ProducerParameters setStartListener(StartListener startListener) {
    this.startListener = startListener;
    return this;
  }

  public ProducerParameters setRateLimiterFactory(RateLimiter.Factory rateLimiterFactory) {
    this.rateLimiterFactory = rateLimiterFactory;
    return this;
  }

  public RateLimiter.Factory getRateLimiterFactory() {
    return rateLimiterFactory;
  }

  public ProducerParameters setId(int id) {
    this.id = id;
    return this;
  }

  public int getId() {
    return id;
  }

  public ProducerParameters setFunctionalLogger(FunctionalLogger functionalLogger) {
    this.functionalLogger = functionalLogger;
    return this;
  }

  public FunctionalLogger getFunctionalLogger() {
    return functionalLogger;
  }
}
