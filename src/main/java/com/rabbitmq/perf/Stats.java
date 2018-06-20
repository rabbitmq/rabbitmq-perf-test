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

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;

public abstract class Stats {

    protected final long interval;

    protected final long startTime;
    private final Consumer<Long> updateLatency;
    private final DoubleAccumulator published, returned, confirmed, nacked, consumed;
    protected long lastStatsTime;
    protected int sendCountInterval;
    protected int returnCountInterval;
    protected int confirmCountInterval;
    protected int nackCountInterval;
    protected int recvCountInterval;
    protected int sendCountTotal;
    protected int recvCountTotal;
    protected int latencyCountInterval;
    protected int latencyCountTotal;
    protected long minLatency;
    protected long maxLatency;
    protected long cumulativeLatencyInterval;
    protected long cumulativeLatencyTotal;
    protected long elapsedInterval;
    protected long elapsedTotal;
    protected Histogram latency = new MetricRegistry().histogram("latency");

    public Stats(long interval) {
        this(interval, false, new SimpleMeterRegistry(), null);
    }

    public Stats(long interval, boolean useMs, MeterRegistry registry, String metricsPrefix) {
        this.interval = interval;
        startTime = System.currentTimeMillis();

        metricsPrefix = metricsPrefix == null ? "" : metricsPrefix;

        Timer latencyTimer = Timer
            .builder(metricsPrefix + "latency")
            .description("message latency")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .distributionStatisticExpiry(Duration.ofMillis(this.interval))
            .sla()
            .register(registry);

        DoubleBinaryOperator accumulatorFunction = (x, y) -> y;
        published = registry.gauge(metricsPrefix + "published", new DoubleAccumulator(accumulatorFunction, 0.0));
        returned = registry.gauge(metricsPrefix + "returned", new DoubleAccumulator(accumulatorFunction, 0.0));
        confirmed = registry.gauge(metricsPrefix + "confirmed", new DoubleAccumulator(accumulatorFunction, 0.0));
        nacked = registry.gauge(metricsPrefix + "nacked", new DoubleAccumulator(accumulatorFunction, 0.0));
        consumed = registry.gauge(metricsPrefix + "consumed", new DoubleAccumulator(accumulatorFunction, 0.0));

        updateLatency = useMs ? latency -> latencyTimer.record(latency, TimeUnit.MILLISECONDS) :
            latency -> latencyTimer.record(latency, TimeUnit.NANOSECONDS);

        reset(startTime);
    }

    private void reset(long t) {
        lastStatsTime = t;

        sendCountInterval = 0;
        returnCountInterval = 0;
        confirmCountInterval = 0;
        nackCountInterval = 0;
        recvCountInterval = 0;

        minLatency = Long.MAX_VALUE;
        maxLatency = Long.MIN_VALUE;
        latencyCountInterval = 0;
        cumulativeLatencyInterval = 0L;
        latency = new MetricRegistry().histogram("latency");
    }

    private void report() {
        long now = System.currentTimeMillis();
        elapsedInterval = now - lastStatsTime;

        if (elapsedInterval >= interval) {
            elapsedTotal += elapsedInterval;
            report(now);
            reset(now);
        }
    }

    protected abstract void report(long now);

    public synchronized void handleSend() {
        sendCountInterval++;
        sendCountTotal++;
        report();
    }

    public synchronized void handleReturn() {
        returnCountInterval++;
        report();
    }

    public synchronized void handleConfirm(int numConfirms) {
        confirmCountInterval += numConfirms;
        report();
    }

    public synchronized void handleNack(int numAcks) {
        nackCountInterval += numAcks;
        report();
    }

    public synchronized void handleRecv(long latency) {
        recvCountInterval++;
        recvCountTotal++;
        if (latency > 0) {
            this.latency.update(latency);
            this.updateLatency.accept(latency);
            minLatency = Math.min(minLatency, latency);
            maxLatency = Math.max(maxLatency, latency);
            cumulativeLatencyInterval += latency;
            cumulativeLatencyTotal += latency;
            latencyCountInterval++;
            latencyCountTotal++;
        }
        report();
    }

    protected void published(double rate) {
        this.published.accumulate(rate);
    }

    protected void returned(double rate) {
        this.returned.accumulate(rate);
    }

    protected void confirmed(double rate) {
        this.confirmed.accumulate(rate);
    }

    protected void nacked(double rate) {
        this.nacked.accumulate(rate);
    }

    protected void received(double rate) {
        this.consumed.accumulate(rate);
    }
}
