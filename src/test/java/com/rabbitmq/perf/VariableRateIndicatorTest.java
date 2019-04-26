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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.rabbitmq.perf.VariableRateIndicator.*;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class VariableRateIndicatorTest {

    @Test
    public void validation() {
        validate("10:20");
        validate("1:1");
        validate("0:20");
        asList("1-1", ":", "20:0", "-1:20", "20:-1", "a:20", "20:a")
                .forEach(input -> assertThatThrownBy(
                        () -> validate(input)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(input));
    }

    @Test
    public void updateRate() {
        // 5:10 20:5 15:15
        List<VariableRateIndicator.Interval> intervals = asList(
                new VariableRateIndicator.Interval(0, 10, 5),
                new VariableRateIndicator.Interval(10, 15, 20),
                new VariableRateIndicator.Interval(15, 30, 15)
        );
        int cycleDuration = intervals.get(2).end;
        long startTime = System.nanoTime();
        AtomicReference<Float> rate = new AtomicReference<>(10.0f);
        Object[][] parameters = new Object[][]{
                {intervals.get(0).start + 1, intervals.get(0).rate},
                {intervals.get(1).start + 1, intervals.get(1).rate},
                {intervals.get(2).start + 1, intervals.get(2).rate},
                {cycleDuration + intervals.get(0).start + 1, intervals.get(0).rate},
                {cycleDuration + intervals.get(1).start + 1, intervals.get(1).rate},
                {cycleDuration + intervals.get(2).start + 1, intervals.get(2).rate},
        };
        asList(parameters).forEach(parameter -> {
            int secondsToAdd = (int) parameter[0];
            float expectedRate = (float) parameter[1];
            updateRateIfNecessary(intervals, startTime, addSecondsToNano(startTime, secondsToAdd), cycleDuration, rate);
            assertThat(rate).hasValue(expectedRate);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "10:20 15:30 20:10 10:60, 10",
            "10:5 15:14, 1",
            "10:5 15:15, 5",
    })
    public void scheduleAtGreatestCommonDivisor(String rate, long expectedPeriod) {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateIndicator rateIndicator = new VariableRateIndicator(
                Arrays.asList(rate.split(" ")),
                scheduledExecutorService
        );
        rateIndicator.start();
        verify(scheduledExecutorService).scheduleAtFixedRate(
                any(Runnable.class), eq(0L), eq(expectedPeriod), eq(TimeUnit.SECONDS)
        );
    }

    @Test
    public void gcdCalculation() {
        assertThat(gcd(new int[]{60, 15, 20}, 3)).isEqualTo(5);
        assertThat(gcd(new int[]{10, 13}, 2)).isEqualTo(1);
        assertThat(gcd(new int[]{10, 5}, 2)).isEqualTo(5);
    }

    long addSecondsToNano(long nanoTime, long secondsToAdd) {
        return nanoTime + secondsToAdd * 1_000_000_000;
    }

}
