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
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public abstract class Stats {
    protected final long    interval;

    protected final long    startTime;
    protected long    lastStatsTime;

    protected int     sendCountInterval;
    protected int     returnCountInterval;
    protected int     confirmCountInterval;
    protected int     nackCountInterval;
    protected int     recvCountInterval;

    protected int     sendCountTotal;
    protected int     recvCountTotal;

    protected int     latencyCountInterval;
    protected int     latencyCountTotal;
    protected long    minLatency;
    protected long    maxLatency;
    protected long    cumulativeLatencyInterval;
    protected long    cumulativeLatencyTotal;

    protected long    elapsedInterval;
    protected long    elapsedTotal;

    protected Histogram latency = new MetricRegistry().histogram("latency");

    private final Consumer<Long> updateLatency;

//    private final AtomicInteger sent, received;

    public Stats(long interval) {
        this(interval, false);
    }

    public Stats(long interval, boolean useMs) {
        this.interval = interval;
        startTime = System.currentTimeMillis();

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        try {
            Server server = new Server(8080);
            server.setHandler(new AbstractHandler() {

                @Override
                public void handle(String s, Request request, HttpServletRequest httpServletRequest, HttpServletResponse response)
                    throws IOException {
                    String scraped = registry.scrape();

                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentLength(scraped.length());
                    response.setContentType("text/plain");

                    response.getWriter().print(scraped);

                    request.setHandled(true);
                }
            });

            server.start();
//            server.join();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }

        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.of("client", "perf-test"));
//        tags.add(Tag.of("id", UUID.randomUUID().toString()));

        Timer latencyTimer = Timer
            .builder("latency")
            .description("message latency")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .tags(tags)
            .distributionStatisticExpiry(Duration.ofMillis(this.interval))
            .sla()
            .register(registry);

//        sent = registry.gauge("sent", tags, new AtomicInteger(0));
//        received = registry.gauge("received", tags, new AtomicInteger(0));

        updateLatency = useMs ? latency -> latencyTimer.record(latency, TimeUnit.MILLISECONDS) :
            latency -> latencyTimer.record(latency, TimeUnit.NANOSECONDS);

        reset(startTime);
    }

    private void reset(long t) {
        lastStatsTime             = t;

        sendCountInterval         = 0;
        returnCountInterval       = 0;
        confirmCountInterval      = 0;
        nackCountInterval         = 0;
        recvCountInterval         = 0;

        minLatency                = Long.MAX_VALUE;
        maxLatency                = Long.MIN_VALUE;
        latencyCountInterval      = 0;
        cumulativeLatencyInterval = 0L;
        latency                   = new MetricRegistry().histogram("latency");
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
        confirmCountInterval +=numConfirms;
        report();
    }

    public synchronized void handleNack(int numAcks) {
        nackCountInterval +=numAcks;
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


}
