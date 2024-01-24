// Copyright (c) 2019-2023 Broadcom. All Rights Reserved.
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

import static com.rabbitmq.perf.VariableValueIndicator.*;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class VariableValueIndicatorTest {

  @Test
  public void validation() {
    validate("10:20");
    validate("1:1");
    validate("0:20");
    asList("1-1", ":", "20:0", "-1:20", "20:-1", "a:20", "20:a")
        .forEach(
            input ->
                assertThatThrownBy(() -> validate(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(input));
  }

  @Test
  public void updateValue() {
    // 5:10 20:5 15:15
    List<VariableValueIndicator.Interval<Integer>> intervals =
        asList(
            new VariableValueIndicator.Interval<>(0, 10, 5),
            new VariableValueIndicator.Interval<>(10, 15, 20),
            new VariableValueIndicator.Interval<>(15, 30, 15));
    int cycleDuration = intervals.get(2).end;
    long startTime = System.nanoTime();
    AtomicReference<Integer> value = new AtomicReference<>(10);
    Object[][] parameters =
        new Object[][] {
          {intervals.get(0).start + 1, intervals.get(0).value},
          {intervals.get(1).start + 1, intervals.get(1).value},
          {intervals.get(2).start + 1, intervals.get(2).value},
          {cycleDuration + intervals.get(0).start + 1, intervals.get(0).value},
          {cycleDuration + intervals.get(1).start + 1, intervals.get(1).value},
          {cycleDuration + intervals.get(2).start + 1, intervals.get(2).value},
        };
    asList(parameters)
        .forEach(
            parameter -> {
              int secondsToAdd = (int) parameter[0];
              int expectedValue = (int) parameter[1];
              updateValueIfNecessary(
                  intervals,
                  startTime,
                  addSecondsToNano(startTime, secondsToAdd),
                  cycleDuration,
                  value,
                  Collections.emptySet());
              assertThat(value).hasValue(expectedValue);
            });
  }

  @ParameterizedTest
  @CsvSource({
    "10:20 15:30 20:10 10:60, 10",
    "10:5 15:14, 1",
    "10:5 15:15, 5",
  })
  public void scheduleAtGreatestCommonDivisor(String input, long expectedPeriod) {
    ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
    ValueIndicator<Integer> rateIndicator =
        new VariableValueIndicator<>(
            Arrays.asList(input.split(" ")),
            scheduledExecutorService,
            value -> Integer.valueOf(value));
    rateIndicator.start();
    verify(scheduledExecutorService)
        .scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(expectedPeriod), eq(TimeUnit.SECONDS));
  }

  @Test
  public void gcdCalculation() {
    assertThat(gcd(new int[] {60, 15, 20}, 3)).isEqualTo(5);
    assertThat(gcd(new int[] {10, 13}, 2)).isEqualTo(1);
    assertThat(gcd(new int[] {10, 5}, 2)).isEqualTo(5);
  }

  long addSecondsToNano(long nanoTime, long secondsToAdd) {
    return nanoTime + secondsToAdd * 1_000_000_000;
  }
}
