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

import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ValueIndicator} that periodically changes the variable value based on variable value
 * definitions.
 *
 * <p>Definitions use the <code>[VALUE]:[DURATION]</code> syntax, where <code>VALUE</code> is an
 * integer >= 0 and <code>DURATION</code> is a positive integer. Duration is in seconds.
 *
 * <p>So with value definitions such as <code>200:60</code>, <code>1000:20</code>, and <code>500:15
 * </code>, the value will be 200 for 60 seconds, then 1000 for 20 seconds, then 500 for 15 seconds,
 * then back to 200 for 60 seconds, and so on.
 *
 * <p>The {@link VariableValueIndicator} periodically updates the value according to value
 * definitions. It uses a provided {@link ScheduledExecutorService} for this. The period is the
 * greatest common divisor of the durations.
 *
 * @since 2.8.0
 */
class VariableValueIndicator<T> implements ValueIndicator<T> {

  public static final int NANO_TO_SECOND = 1_000_000_000;
  private static final Logger LOGGER = LoggerFactory.getLogger(VariableValueIndicator.class);
  private final ScheduledExecutorService scheduledExecutorService;

  private final List<Interval<T>> intervals;

  private final AtomicReference<T> value = new AtomicReference<>();

  private final List<String> definitions;

  private final Set<Listener<T>> listeners = ConcurrentHashMap.newKeySet();

  public VariableValueIndicator(
      List<String> values,
      ScheduledExecutorService scheduledExecutorService,
      Function<String, T> conversion) {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException();
    }
    for (String value : values) {
      validate(value);
    }
    this.scheduledExecutorService = scheduledExecutorService;
    this.definitions = new ArrayList<>(values);
    this.intervals = intervals(values, conversion);
    this.value.set(intervals.get(0).value);
    LOGGER.debug("Setting variable value to {}", this.value.get());
  }

  static <T> void updateValueIfNecessary(
      List<Interval<T>> intervals,
      long startTime,
      long now,
      int cycleDuration,
      AtomicReference<T> value,
      Set<Listener<T>> listeners) {
    long elapsed = ((now - startTime) / NANO_TO_SECOND) % cycleDuration;
    for (Interval<T> interval : intervals) {
      if (interval.isIn(elapsed)) {
        if (!value.get().equals(interval.value)) {
          LOGGER.debug("Changing value from {} to {}", value.get(), interval.value);
          T oldValue = value.get();
          value.set(interval.value);
          listeners.forEach(
              l -> {
                try {
                  l.valueChanged(oldValue, value.get());
                } catch (Exception e) {
                  LOGGER.warn("Error while notifying variable value listener: {}", e.getMessage());
                }
              });
        }
        break;
      }
    }
  }

  @SuppressWarnings("unchecked")
  static void validate(String definition) throws IllegalArgumentException {
    final AtomicBoolean valid = new AtomicBoolean(true);
    if (definition == null || definition.isEmpty() || !definition.contains(":")) {
      valid.set(false);
    }
    String[] valueAndDuration = null;
    if (valid.get()) {
      valueAndDuration = definition.split(":");
      if (valueAndDuration.length != 2) {
        valid.set(false);
      }
    }
    if (valid.get()) {
      asList(
              new Object[][] {
                {valueAndDuration[0], (Predicate<Integer>) value -> value >= 0},
                {valueAndDuration[1], (Predicate<Integer>) value -> value > 0}
              })
          .forEach(
              parameters -> {
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
          "Invalid variable value definition: "
              + definition
              + ". "
              + "Should be [VALUE]:[DURATION] with VALUE integer >= 0 and DURATION integer > 0");
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

  static <T> List<Interval<T>> intervals(List<String> values, Function<String, T> conversion) {
    final List<Interval<T>> intervals = new ArrayList<>(values.size());
    int start = 0;
    for (String value : values) {
      validate(value);
      String[] valueAndDuration = value.split(":");
      int duration = Integer.valueOf(valueAndDuration[1]);
      int end = start + duration;
      Interval<T> interval = new Interval<>(start, end, conversion.apply(valueAndDuration[0]));
      intervals.add(interval);
      start = end;
    }
    return intervals;
  }

  @Override
  public T getValue() {
    return value.get();
  }

  @Override
  public void start() {
    int[] durations = new int[this.definitions.size()];
    for (int i = 0; i < this.definitions.size(); i++) {
      String[] valueAndDuration = definitions.get(i).split(":");
      durations[i] = Integer.valueOf(valueAndDuration[1]);
    }

    int cycleDuration = intervals.get(intervals.size() - 1).end;
    int gcdSchedulingPeriod = 1;

    try {
      gcdSchedulingPeriod = gcd(durations, durations.length);
    } catch (Exception e) {
      LOGGER.warn(
          "Error while calculating GCD: "
              + e.getMessage()
              + ". Falling back to 1-second scheduling.");
    }

    long startTime = System.nanoTime();
    scheduledExecutorService.scheduleAtFixedRate(
        () -> {
          updateValueIfNecessary(
              intervals, startTime, System.nanoTime(), cycleDuration, this.value, this.listeners);
        },
        0,
        gcdSchedulingPeriod,
        TimeUnit.SECONDS);
  }

  @Override
  public boolean isVariable() {
    return true;
  }

  @Override
  public List<T> values() {
    return this.intervals.stream().map(interval -> interval.value).collect(Collectors.toList());
  }

  static class Interval<T> {

    final int start;
    final int end;
    final T value;

    Interval(int start, int end, T value) {
      this.start = start;
      this.end = end;
      this.value = value;
    }

    boolean isIn(long value) {
      return value >= start && value < end;
    }

    @Override
    public String toString() {
      return "Interval{" + "start=" + start + ", end=" + end + ", value=" + value + '}';
    }
  }

  @Override
  public void register(Listener<T> listener) {
    this.listeners.add(listener);
  }
}
