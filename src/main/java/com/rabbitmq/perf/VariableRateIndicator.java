// Copyright (c) 2019 Pivotal Software, Inc.  All rights reserved.
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static java.util.Arrays.asList;

/**
 * {@link RateIndicator} that periodically changes rate hints based on variable rate definitions.
 * <p>
 * Rate definitions use the <code>[RATE]:[DURATION]</code> syntax, where <code>RATE</code>
 * and <code>DURATION</code>  are positive integers. Rate is in messages per second, duration
 * is in seconds.
 * <p>
 * So with rate definitions such as <code>200:60</code>, <code>1000:20</code>, and
 * <code>500:15</code>, the rate will be 200 messages per second for 60 seconds, then
 * 1000 messages per second for 20 seconds, then 500 messages per second for 15 seconds, then
 * back to 200 messages per second for 60 seconds, and so on.
 * <p>
 * The {@link VariableRateIndicator} periodically updates the rate according to rate definitions.
 * It uses a provided {@link ScheduledExecutorService} for this. The period is the
 * greatest common divisor of the durations.
 *
 * @since 2.8.0
 */
class VariableRateIndicator implements RateIndicator {

    public static final int NANO_TO_SECOND = 1_000_000_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(VariableRateIndicator.class);
    private final ScheduledExecutorService scheduledExecutorService;

    private final List<String> rates;

    private final AtomicReference<Float> rate = new AtomicReference<>();

    public VariableRateIndicator(List<String> rates, ScheduledExecutorService scheduledExecutorService) {
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException();
        }
        for (String rate : rates) {
            validate(rate);
        }
        this.scheduledExecutorService = scheduledExecutorService;
        this.rates = new ArrayList<>(rates);
    }

    static void updateRateIfNecessary(List<Interval> intervals, long startTime, long now, int cycleDuration, AtomicReference<Float> rate) {
        long elapsed = ((now - startTime) / NANO_TO_SECOND) % cycleDuration;
        for (Interval interval : intervals) {
            if (interval.isIn(elapsed)) {
                if (!rate.get().equals(interval.rate)) {
                    rate.set(interval.rate);
                }
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void validate(String rateDefinition) throws IllegalArgumentException {
        final AtomicBoolean valid = new AtomicBoolean(true);
        if (rateDefinition == null || rateDefinition.isEmpty() || !rateDefinition.contains(":")) {
            valid.set(false);
        }
        String[] rateAndDuration = null;
        if (valid.get()) {
            rateAndDuration = rateDefinition.split(":");
            if (rateAndDuration.length != 2) {
                valid.set(false);
            }
        }
        if (valid.get()) {
            asList(new Object[][]{
                    {rateAndDuration[0], (Predicate<Integer>) value -> value >= 0},
                    {rateAndDuration[1], (Predicate<Integer>) value -> value > 0}
            }).forEach(parameters -> {
                String input = parameters[0].toString();
                Predicate<Integer> validation = (Predicate<Integer>) parameters[1];
                try {
                    int value = Integer.valueOf(input);
                    if (!validation.test(value)) {
                        valid.set(false);
                    }
                } catch (NumberFormatException e) {
                    valid.set(false);
                }
            });
        }

        if (!valid.get()) {
            throw new IllegalArgumentException(
                    "Invalid variable rate definition: " + rateDefinition + ". " +
                            "Should be [RATE]:[DURATION] with RATE integer >= 0 and DURATION integer > 0"
            );
        }

    }

    private static int gcd(int a, int b) {
        if (a == 0) {
            return b;
        }
        return gcd(b % a, a);
    }

    static int gcd(int[] arr, int n) {
        int result = arr[0];
        for (int i = 1; i < n; i++) {
            result = gcd(arr[i], result);
        }
        return result;
    }

    static List<Interval> intervals(List<String> rates) {
        final List<Interval> intervals = new ArrayList<>(rates.size());
        int start = 0;
        for (String rate : rates) {
            validate(rate);
            String[] rateAndDuration = rate.split(":");
            int duration = Integer.valueOf(rateAndDuration[1]);
            int end = start + duration;
            Interval interval = new Interval(start, end, Float.valueOf(rateAndDuration[0]));
            intervals.add(interval);
            start = end;
        }
        return intervals;
    }

    @Override
    public float getRate() {
        return rate.get();
    }

    @Override
    public void start() {
        final List<Interval> intervals = intervals(this.rates);

        int[] durations = new int[this.rates.size()];
        for (int i = 0; i < this.rates.size(); i++) {
            String[] rateAndDuration = rates.get(i).split(":");
            durations[i] = Integer.valueOf(rateAndDuration[1]);
        }

        int cycleDuration = intervals.get(intervals.size() - 1).end;
        int gcdSchedulingPeriod = 1;

        try {
            gcdSchedulingPeriod = gcd(durations, durations.length);
        } catch (Exception e) {
            LOGGER.warn("Error while calculating GCD: " + e.getMessage() + ". Falling back to 1-second scheduling.");
        }

        long startTime = System.nanoTime();
        this.rate.set(intervals.get(0).rate);
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            updateRateIfNecessary(intervals, startTime, System.nanoTime(), cycleDuration, this.rate);
        }, 0, gcdSchedulingPeriod, TimeUnit.SECONDS);
    }

    @Override
    public boolean isVariable() {
        return true;
    }

    static class Interval {

        final int start;
        final int end;
        final float rate;

        Interval(int start, int end, float rate) {
            this.start = start;
            this.end = end;
            this.rate = rate;
        }

        boolean isIn(long value) {
            return value >= start && value < end;
        }

        @Override
        public String toString() {
            return "Interval{" +
                    "start=" + start +
                    ", end=" + end +
                    ", rate=" + rate +
                    '}';
        }
    }

}
