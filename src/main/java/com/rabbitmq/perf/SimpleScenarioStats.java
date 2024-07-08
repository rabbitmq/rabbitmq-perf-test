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

import com.rabbitmq.perf.metrics.PerformanceMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class SimpleScenarioStats extends Stats implements ScenarioStats, PerformanceMetrics {
  private static final int IGNORE_FIRST = 3;

  private final List<Map<String, Object>> samples = new ArrayList<>();
  private long elapsedTotalToIgnore;
  private long minMsgSize;

  public SimpleScenarioStats(long interval) {
    super(Duration.ofMillis(interval));
  }

  protected synchronized void report(long now) {
    if (samples.size() == IGNORE_FIRST) {
      cumulativeLatencyTotal.set(0);
      latencyCountTotal.set(0);
      sendCountTotal.set(0);
      recvCountTotal.set(0);
      elapsedTotalToIgnore = elapsedTotal.get();
    }

    Map<String, Object> sample = new HashMap<>();
    long elapsedTime = Duration.ofNanos(elapsedInterval.get()).toMillis();
    sample.put("send-msg-rate", rate(sendCountInterval.get(), elapsedTime));
    sample.put("send-bytes-rate", rate(sendCountInterval.get(), elapsedTime) * minMsgSize);
    sample.put("recv-msg-rate", rate(recvCountInterval.get(), elapsedTime));
    sample.put("recv-bytes-rate", rate(recvCountInterval.get(), elapsedTime) * minMsgSize);
    sample.put("elapsed", elapsedTotal.get() / 1_000_000); // nano to ms
    if (latencyCountInterval.get() > 0) {
      sample.put("avg-latency", intervalAverageLatency());
      sample.put("min-latency", minLatency.get() / 1000L);
      sample.put("max-latency", maxLatency.get() / 1000L);
    }
    samples.add(sample);
  }

  public Map<String, Object> results() {
    Map<String, Object> map = new HashMap<String, Object>();
    map.put("send-msg-rate", getSendRate());
    map.put("send-bytes-rate", getSendRate() * minMsgSize);
    map.put("recv-msg-rate", getRecvRate());
    map.put("recv-bytes-rate", getRecvRate() * minMsgSize);
    if (latencyCountTotal.get() > 0) {
      map.put("avg-latency", overallAverageLatency());
    }
    map.put("samples", samples);
    return map;
  }

  public void setup(MulticastParams params) {
    this.minMsgSize = params.getMinMsgSize();
  }

  public double getSendRate() {
    return rate(sendCountTotal.get(), elapsedTotal.get() - elapsedTotalToIgnore);
  }

  public double getRecvRate() {
    return rate(recvCountTotal.get(), elapsedTotal.get() - elapsedTotalToIgnore);
  }

  private double rate(long count, long elapsed) {
    return elapsed == 0 ? 0.0 : MS_TO_SECOND * ((double) count / (double) elapsed);
  }

  private long overallAverageLatency() {
    return cumulativeLatencyTotal.get() / (1000L * latencyCountTotal.get());
  }

  private long intervalAverageLatency() {
    return cumulativeLatencyInterval.get() / (1000L * latencyCountInterval.get());
  }

  @Override
  public void start() {}

  @Override
  public void published() {
    this.handleSend();
  }

  @Override
  public void confirmed(int count, long[] latencies) {
    this.handleConfirm(count, latencies);
  }

  @Override
  public void nacked(int count) {
    this.handleNack(count);
  }

  @Override
  public void returned() {
    this.handleReturn();
  }

  @Override
  public void received(long latency) {
    this.handleRecv(latency);
  }

  @Override
  public Duration interval() {
    return this.interval();
  }

  @Override
  public void maybeResetGauges() {
    super.maybeResetGauges();
  }

  @Override
  public void resetGlobals() {
    super.resetGlobals();
  }
}
