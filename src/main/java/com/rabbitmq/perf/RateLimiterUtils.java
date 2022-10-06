// Copyright (c) 2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import static com.rabbitmq.perf.RateLimiterUtils.GuavaPreconditions.checkArgument;
import static com.rabbitmq.perf.RateLimiterUtils.GuavaPreconditions.checkNotNull;
import static com.rabbitmq.perf.RateLimiterUtils.GuavaPreconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.WARNING;

import com.rabbitmq.perf.RateLimiterUtils.SmoothRateLimiter.SmoothBursty;
import com.rabbitmq.perf.RateLimiterUtils.SmoothRateLimiter.SmoothWarmingUp;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Rate limiting utilities taken from Google Guava.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 */
abstract class RateLimiterUtils {

  private RateLimiterUtils() {

  }

  /**
   * A rate limiter. Conceptually, a rate limiter distributes permits at a configurable rate. Each
   * {@link #acquire()} blocks if necessary until a permit is available, and then takes it. Once
   * acquired, permits need not be released.
   *
   * <p>{@code RateLimiter} is safe for concurrent use: It will restrict the total rate of calls from
   * all threads. Note, however, that it does not guarantee fairness.
   *
   * <p>Rate limiters are often used to restrict the rate at which some physical or logical resource
   * is accessed. This is in contrast to {@link java.util.concurrent.Semaphore} which restricts the
   * number of concurrent accesses instead of the rate (note though that concurrency and rate are
   * closely related, e.g. see <a href="http://en.wikipedia.org/wiki/Little%27s_law">Little's
   * Law</a>).
   *
   * <p>A {@code RateLimiter} is defined primarily by the rate at which permits are issued. Absent
   * additional configuration, permits will be distributed at a fixed rate, defined in terms of
   * permits per second. Permits will be distributed smoothly, with the delay between individual
   * permits being adjusted to ensure that the configured rate is maintained.
   *
   * <p>It is possible to configure a {@code RateLimiter} to have a warmup period during which time
   * the permits issued each second steadily increases until it hits the stable rate.
   *
   * <p>As an example, imagine that we have a list of tasks to execute, but we don't want to submit
   * more than 2 per second:
   *
   * <pre>{@code
   * final RateLimiter rateLimiter = RateLimiter.create(2.0); // rate is "2 permits per second"
   * void submitTasks(List<Runnable> tasks, Executor executor) {
   *   for (Runnable task : tasks) {
   *     rateLimiter.acquire(); // may wait
   *     executor.execute(task);
   *   }
   * }
   * }</pre>
   *
   * <p>As another example, imagine that we produce a stream of data, and we want to cap it at 5kb per
   * second. This could be accomplished by requiring a permit per byte, and specifying a rate of 5000
   * permits per second:
   *
   * <pre>{@code
   * final RateLimiter rateLimiter = RateLimiter.create(5000.0); // rate = 5000 permits per second
   * void submitPacket(byte[] packet) {
   *   rateLimiter.acquire(packet.length);
   *   networkService.send(packet);
   * }
   * }</pre>
   *
   * <p>It is important to note that the number of permits requested <i>never</i> affects the
   * throttling of the request itself (an invocation to {@code acquire(1)} and an invocation to {@code
   * acquire(1000)} will result in exactly the same throttling, if any), but it affects the throttling
   * of the <i>next</i> request. I.e., if an expensive task arrives at an idle RateLimiter, it will be
   * granted immediately, but it is the <i>next</i> request that will experience extra throttling,
   * thus paying for the cost of the expensive task.
   *
   * @author Dimitris Andreou
   * @since 13.0
   */
  // TODO(user): switch to nano precision. A natural unit of cost is "bytes", and a micro precision
  // would mean a maximum rate of "1MB/s", which might be small in some cases.
  abstract static class RateLimiter {
    /**
     * Creates a {@code RateLimiter} with the specified stable throughput, given as "permits per
     * second" (commonly referred to as <i>QPS</i>, queries per second).
     *
     * <p>The returned {@code RateLimiter} ensures that on average no more than {@code
     * permitsPerSecond} are issued during any given second, with sustained requests being smoothly
     * spread over each second. When the incoming request rate exceeds {@code permitsPerSecond} the
     * rate limiter will release one permit every {@code (1.0 / permitsPerSecond)} seconds. When the
     * rate limiter is unused, bursts of up to {@code permitsPerSecond} permits will be allowed, with
     * subsequent requests being smoothly limited at the stable rate of {@code permitsPerSecond}.
     *
     * @param permitsPerSecond the rate of the returned {@code RateLimiter}, measured in how many
     *     permits become available per second
     * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero
     */
    // TODO(user): "This is equivalent to
    // {@code createWithCapacity(permitsPerSecond, 1, TimeUnit.SECONDS)}".
    static RateLimiter create(double permitsPerSecond) {
      /*
       * The default RateLimiter configuration can save the unused permits of up to one second. This
       * is to avoid unnecessary stalls in situations like this: A RateLimiter of 1qps, and 4 threads,
       * all calling acquire() at these moments:
       *
       * T0 at 0 seconds
       * T1 at 1.05 seconds
       * T2 at 2 seconds
       * T3 at 3 seconds
       *
       * Due to the slight delay of T1, T2 would have to sleep till 2.05 seconds, and T3 would also
       * have to sleep till 3.05 seconds.
       */
      return create(permitsPerSecond, SleepingStopwatch.createFromSystemTimer());
    }

    static RateLimiter create(double permitsPerSecond, SleepingStopwatch stopwatch) {
      RateLimiter rateLimiter = new SmoothBursty(stopwatch, 1.0 /* maxBurstSeconds */);
      rateLimiter.setRate(permitsPerSecond);
      return rateLimiter;
    }

    /**
     * Creates a {@code RateLimiter} with the specified stable throughput, given as "permits per
     * second" (commonly referred to as <i>QPS</i>, queries per second), and a <i>warmup period</i>,
     * during which the {@code RateLimiter} smoothly ramps up its rate, until it reaches its maximum
     * rate at the end of the period (as long as there are enough requests to saturate it). Similarly,
     * if the {@code RateLimiter} is left <i>unused</i> for a duration of {@code warmupPeriod}, it
     * will gradually return to its "cold" state, i.e. it will go through the same warming up process
     * as when it was first created.
     *
     * <p>The returned {@code RateLimiter} is intended for cases where the resource that actually
     * fulfills the requests (e.g., a remote server) needs "warmup" time, rather than being
     * immediately accessed at the stable (maximum) rate.
     *
     * <p>The returned {@code RateLimiter} starts in a "cold" state (i.e. the warmup period will
     * follow), and if it is left unused for long enough, it will return to that state.
     *
     * @param permitsPerSecond the rate of the returned {@code RateLimiter}, measured in how many
     *     permits become available per second
     * @param warmupPeriod the duration of the period where the {@code RateLimiter} ramps up its rate,
     *     before reaching its stable (maximum) rate
     * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero or {@code
     *     warmupPeriod} is negative
     * @since 28.0
     */
    static RateLimiter create(double permitsPerSecond, Duration warmupPeriod) {
      return create(permitsPerSecond, toNanosSaturated(warmupPeriod), TimeUnit.NANOSECONDS);
    }

    /**
     * Creates a {@code RateLimiter} with the specified stable throughput, given as "permits per
     * second" (commonly referred to as <i>QPS</i>, queries per second), and a <i>warmup period</i>,
     * during which the {@code RateLimiter} smoothly ramps up its rate, until it reaches its maximum
     * rate at the end of the period (as long as there are enough requests to saturate it). Similarly,
     * if the {@code RateLimiter} is left <i>unused</i> for a duration of {@code warmupPeriod}, it
     * will gradually return to its "cold" state, i.e. it will go through the same warming up process
     * as when it was first created.
     *
     * <p>The returned {@code RateLimiter} is intended for cases where the resource that actually
     * fulfills the requests (e.g., a remote server) needs "warmup" time, rather than being
     * immediately accessed at the stable (maximum) rate.
     *
     * <p>The returned {@code RateLimiter} starts in a "cold" state (i.e. the warmup period will
     * follow), and if it is left unused for long enough, it will return to that state.
     *
     * @param permitsPerSecond the rate of the returned {@code RateLimiter}, measured in how many
     *     permits become available per second
     * @param warmupPeriod the duration of the period where the {@code RateLimiter} ramps up its rate,
     *     before reaching its stable (maximum) rate
     * @param unit the time unit of the warmupPeriod argument
     * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero or {@code
     *     warmupPeriod} is negative
     */
    @SuppressWarnings("GoodTime") // should accept a java.time.Duration
    static RateLimiter create(double permitsPerSecond, long warmupPeriod, TimeUnit unit) {
      checkArgument(warmupPeriod >= 0, "warmupPeriod must not be negative: %s", warmupPeriod);
      return create(
          permitsPerSecond, warmupPeriod, unit, 3.0, SleepingStopwatch.createFromSystemTimer());
    }

    static RateLimiter create(
        double permitsPerSecond,
        long warmupPeriod,
        TimeUnit unit,
        double coldFactor,
        SleepingStopwatch stopwatch) {
      RateLimiter rateLimiter = new SmoothWarmingUp(stopwatch, warmupPeriod, unit, coldFactor);
      rateLimiter.setRate(permitsPerSecond);
      return rateLimiter;
    }

    /**
     * The underlying timer; used both to measure elapsed time and sleep as necessary. A separate
     * object to facilitate testing.
     */
    private final SleepingStopwatch stopwatch;

    // Can't be initialized in the constructor because mocks don't call the constructor.
    private volatile Object mutexDoNotUseDirectly;

    private Object mutex() {
      Object mutex = mutexDoNotUseDirectly;
      if (mutex == null) {
        synchronized (this) {
          mutex = mutexDoNotUseDirectly;
          if (mutex == null) {
            mutexDoNotUseDirectly = mutex = new Object();
          }
        }
      }
      return mutex;
    }

    RateLimiter(SleepingStopwatch stopwatch) {
      this.stopwatch = checkNotNull(stopwatch);
    }

    /**
     * Updates the stable rate of this {@code RateLimiter}, that is, the {@code permitsPerSecond}
     * argument provided in the factory method that constructed the {@code RateLimiter}. Currently
     * throttled threads will <b>not</b> be awakened as a result of this invocation, thus they do not
     * observe the new rate; only subsequent requests will.
     *
     * <p>Note though that, since each request repays (by waiting, if necessary) the cost of the
     * <i>previous</i> request, this means that the very next request after an invocation to {@code
     * setRate} will not be affected by the new rate; it will pay the cost of the previous request,
     * which is in terms of the previous rate.
     *
     * <p>The behavior of the {@code RateLimiter} is not modified in any other way, e.g. if the {@code
     * RateLimiter} was configured with a warmup period of 20 seconds, it still has a warmup period of
     * 20 seconds after this method invocation.
     *
     * @param permitsPerSecond the new stable rate of this {@code RateLimiter}
     * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero
     */
    final void setRate(double permitsPerSecond) {
      checkArgument(
          permitsPerSecond > 0.0 && !Double.isNaN(permitsPerSecond), "rate must be positive");
      synchronized (mutex()) {
        doSetRate(permitsPerSecond, stopwatch.readMicros());
      }
    }

    abstract void doSetRate(double permitsPerSecond, long nowMicros);

    /**
     * Returns the stable rate (as {@code permits per seconds}) with which this {@code RateLimiter} is
     * configured with. The initial value of this is the same as the {@code permitsPerSecond} argument
     * passed in the factory method that produced this {@code RateLimiter}, and it is only updated
     * after invocations to {@linkplain #setRate}.
     */
    final double getRate() {
      synchronized (mutex()) {
        return doGetRate();
      }
    }

    abstract double doGetRate();

    /**
     * Acquires a single permit from this {@code RateLimiter}, blocking until the request can be
     * granted. Tells the amount of time slept, if any.
     *
     * <p>This method is equivalent to {@code acquire(1)}.
     *
     * @return time spent sleeping to enforce rate, in seconds; 0.0 if not rate-limited
     * @since 16.0 (present in 13.0 with {@code void} return type})
     */
    double acquire() {
      return acquire(1);
    }

    /**
     * Acquires the given number of permits from this {@code RateLimiter}, blocking until the request
     * can be granted. Tells the amount of time slept, if any.
     *
     * @param permits the number of permits to acquire
     * @return time spent sleeping to enforce rate, in seconds; 0.0 if not rate-limited
     * @throws IllegalArgumentException if the requested number of permits is negative or zero
     * @since 16.0 (present in 13.0 with {@code void} return type})
     */
    double acquire(int permits) {
      long microsToWait = reserve(permits);
      stopwatch.sleepMicrosUninterruptibly(microsToWait);
      return 1.0 * microsToWait / SECONDS.toMicros(1L);
    }

    /**
     * Reserves the given number of permits from this {@code RateLimiter} for future use, returning
     * the number of microseconds until the reservation can be consumed.
     *
     * @return time in microseconds to wait until the resource can be acquired, never negative
     */
    final long reserve(int permits) {
      checkPermits(permits);
      synchronized (mutex()) {
        return reserveAndGetWaitLength(permits, stopwatch.readMicros());
      }
    }

    /**
     * Acquires a permit from this {@code RateLimiter} if it can be obtained without exceeding the
     * specified {@code timeout}, or returns {@code false} immediately (without waiting) if the permit
     * would not have been granted before the timeout expired.
     *
     * <p>This method is equivalent to {@code tryAcquire(1, timeout)}.
     *
     * @param timeout the maximum time to wait for the permit. Negative values are treated as zero.
     * @return {@code true} if the permit was acquired, {@code false} otherwise
     * @throws IllegalArgumentException if the requested number of permits is negative or zero
     * @since 28.0
     */
    boolean tryAcquire(Duration timeout) {
      return tryAcquire(1, toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires a permit from this {@code RateLimiter} if it can be obtained without exceeding the
     * specified {@code timeout}, or returns {@code false} immediately (without waiting) if the permit
     * would not have been granted before the timeout expired.
     *
     * <p>This method is equivalent to {@code tryAcquire(1, timeout, unit)}.
     *
     * @param timeout the maximum time to wait for the permit. Negative values are treated as zero.
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the permit was acquired, {@code false} otherwise
     * @throws IllegalArgumentException if the requested number of permits is negative or zero
     */
    @SuppressWarnings("GoodTime") // should accept a java.time.Duration
    boolean tryAcquire(long timeout, TimeUnit unit) {
      return tryAcquire(1, timeout, unit);
    }

    /**
     * Acquires permits from this {@link RateLimiter} if it can be acquired immediately without delay.
     *
     * <p>This method is equivalent to {@code tryAcquire(permits, 0, anyUnit)}.
     *
     * @param permits the number of permits to acquire
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if the requested number of permits is negative or zero
     * @since 14.0
     */
    boolean tryAcquire(int permits) {
      return tryAcquire(permits, 0, MICROSECONDS);
    }

    /**
     * Acquires a permit from this {@link RateLimiter} if it can be acquired immediately without
     * delay.
     *
     * <p>This method is equivalent to {@code tryAcquire(1)}.
     *
     * @return {@code true} if the permit was acquired, {@code false} otherwise
     * @since 14.0
     */
    boolean tryAcquire() {
      return tryAcquire(1, 0, MICROSECONDS);
    }

    /**
     * Acquires the given number of permits from this {@code RateLimiter} if it can be obtained
     * without exceeding the specified {@code timeout}, or returns {@code false} immediately (without
     * waiting) if the permits would not have been granted before the timeout expired.
     *
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait for the permits. Negative values are treated as zero.
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if the requested number of permits is negative or zero
     * @since 28.0
     */
    boolean tryAcquire(int permits, Duration timeout) {
      return tryAcquire(permits, toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires the given number of permits from this {@code RateLimiter} if it can be obtained
     * without exceeding the specified {@code timeout}, or returns {@code false} immediately (without
     * waiting) if the permits would not have been granted before the timeout expired.
     *
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait for the permits. Negative values are treated as zero.
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if the requested number of permits is negative or zero
     */
    @SuppressWarnings("GoodTime") // should accept a java.time.Duration
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
      long timeoutMicros = max(unit.toMicros(timeout), 0);
      checkPermits(permits);
      long microsToWait;
      synchronized (mutex()) {
        long nowMicros = stopwatch.readMicros();
        if (!canAcquire(nowMicros, timeoutMicros)) {
          return false;
        } else {
          microsToWait = reserveAndGetWaitLength(permits, nowMicros);
        }
      }
      stopwatch.sleepMicrosUninterruptibly(microsToWait);
      return true;
    }

    private boolean canAcquire(long nowMicros, long timeoutMicros) {
      return queryEarliestAvailable(nowMicros) - timeoutMicros <= nowMicros;
    }

    /**
     * Reserves next ticket and returns the wait time that the caller must wait for.
     *
     * @return the required wait time, never negative
     */
    final long reserveAndGetWaitLength(int permits, long nowMicros) {
      long momentAvailable = reserveEarliestAvailable(permits, nowMicros);
      return max(momentAvailable - nowMicros, 0);
    }

    /**
     * Returns the earliest time that permits are available (with one caveat).
     *
     * @return the time that permits are available, or, if permits are available immediately, an
     *     arbitrary past or present time
     */
    abstract long queryEarliestAvailable(long nowMicros);

    /**
     * Reserves the requested number of permits and returns the time that those permits can be used
     * (with one caveat).
     *
     * @return the time that the permits may be used, or, if the permits may be used immediately, an
     *     arbitrary past or present time
     */
    abstract long reserveEarliestAvailable(int permits, long nowMicros);

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "RateLimiter[stableRate=%3.1fqps]", getRate());
    }

    abstract static class SleepingStopwatch {
      /** Constructor for use by subclasses. */
      protected SleepingStopwatch() {}

      /*
       * We always hold the mutex when calling this. TODO(cpovirk): Is that important? Perhaps we need
       * to guarantee that each call to reserveEarliestAvailable, etc. sees a value >= the previous?
       * Also, is it OK that we don't hold the mutex when sleeping?
       */
      protected abstract long readMicros();

      protected abstract void sleepMicrosUninterruptibly(long micros);

      static SleepingStopwatch createFromSystemTimer() {
        return new SleepingStopwatch() {
          final Stopwatch stopwatch = Stopwatch.createStarted();

          @Override
          protected long readMicros() {
            return stopwatch.elapsed(MICROSECONDS);
          }

          @Override
          protected void sleepMicrosUninterruptibly(long micros) {
            if (micros > 0) {
              sleepUninterruptibly(micros, MICROSECONDS);
            }
          }
        };
      }
    }

    static void sleepUninterruptibly(long sleepFor, TimeUnit unit) {
      boolean interrupted = false;
      try {
        long remainingNanos = unit.toNanos(sleepFor);
        long end = System.nanoTime() + remainingNanos;
        while (true) {
          try {
            // TimeUnit.sleep() treats negative timeouts just like zero.
            NANOSECONDS.sleep(remainingNanos);
            return;
          } catch (InterruptedException e) {
            interrupted = true;
            remainingNanos = end - System.nanoTime();
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private static void checkPermits(int permits) {
      checkArgument(permits > 0, "Requested permits (%s) must be positive", permits);
    }

    private static long toNanosSaturated(Duration duration) {
      // Using a try/catch seems lazy, but the catch block will rarely get invoked (except for
      // durations longer than approximately +/- 292 years).
      try {
        return duration.toNanos();
      } catch (ArithmeticException tooBig) {
        return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
      }
    }
  }

  abstract static class SmoothRateLimiter extends RateLimiter {
    /*
     * How is the RateLimiter designed, and why?
     *
     * The primary feature of a RateLimiter is its "stable rate", the maximum rate that it should
     * allow in normal conditions. This is enforced by "throttling" incoming requests as needed. For
     * example, we could compute the appropriate throttle time for an incoming request, and make the
     * calling thread wait for that time.
     *
     * The simplest way to maintain a rate of QPS is to keep the timestamp of the last granted
     * request, and ensure that (1/QPS) seconds have elapsed since then. For example, for a rate of
     * QPS=5 (5 tokens per second), if we ensure that a request isn't granted earlier than 200ms after
     * the last one, then we achieve the intended rate. If a request comes and the last request was
     * granted only 100ms ago, then we wait for another 100ms. At this rate, serving 15 fresh permits
     * (i.e. for an acquire(15) request) naturally takes 3 seconds.
     *
     * It is important to realize that such a RateLimiter has a very superficial memory of the past:
     * it only remembers the last request. What if the RateLimiter was unused for a long period of
     * time, then a request arrived and was immediately granted? This RateLimiter would immediately
     * forget about that past underutilization. This may result in either underutilization or
     * overflow, depending on the real world consequences of not using the expected rate.
     *
     * Past underutilization could mean that excess resources are available. Then, the RateLimiter
     * should speed up for a while, to take advantage of these resources. This is important when the
     * rate is applied to networking (limiting bandwidth), where past underutilization typically
     * translates to "almost empty buffers", which can be filled immediately.
     *
     * On the other hand, past underutilization could mean that "the server responsible for handling
     * the request has become less ready for future requests", i.e. its caches become stale, and
     * requests become more likely to trigger expensive operations (a more extreme case of this
     * example is when a server has just booted, and it is mostly busy with getting itself up to
     * speed).
     *
     * To deal with such scenarios, we add an extra dimension, that of "past underutilization",
     * modeled by "storedPermits" variable. This variable is zero when there is no underutilization,
     * and it can grow up to maxStoredPermits, for sufficiently large underutilization. So, the
     * requested permits, by an invocation acquire(permits), are served from:
     *
     * - stored permits (if available)
     *
     * - fresh permits (for any remaining permits)
     *
     * How this works is best explained with an example:
     *
     * For a RateLimiter that produces 1 token per second, every second that goes by with the
     * RateLimiter being unused, we increase storedPermits by 1. Say we leave the RateLimiter unused
     * for 10 seconds (i.e., we expected a request at time X, but we are at time X + 10 seconds before
     * a request actually arrives; this is also related to the point made in the last paragraph), thus
     * storedPermits becomes 10.0 (assuming maxStoredPermits >= 10.0). At that point, a request of
     * acquire(3) arrives. We serve this request out of storedPermits, and reduce that to 7.0 (how
     * this is translated to throttling time is discussed later). Immediately after, assume that an
     * acquire(10) request arriving. We serve the request partly from storedPermits, using all the
     * remaining 7.0 permits, and the remaining 3.0, we serve them by fresh permits produced by the
     * rate limiter.
     *
     * We already know how much time it takes to serve 3 fresh permits: if the rate is
     * "1 token per second", then this will take 3 seconds. But what does it mean to serve 7 stored
     * permits? As explained above, there is no unique answer. If we are primarily interested to deal
     * with underutilization, then we want stored permits to be given out /faster/ than fresh ones,
     * because underutilization = free resources for the taking. If we are primarily interested to
     * deal with overflow, then stored permits could be given out /slower/ than fresh ones. Thus, we
     * require a (different in each case) function that translates storedPermits to throttling time.
     *
     * This role is played by storedPermitsToWaitTime(double storedPermits, double permitsToTake). The
     * underlying model is a continuous function mapping storedPermits (from 0.0 to maxStoredPermits)
     * onto the 1/rate (i.e. intervals) that is effective at the given storedPermits. "storedPermits"
     * essentially measure unused time; we spend unused time buying/storing permits. Rate is
     * "permits / time", thus "1 / rate = time / permits". Thus, "1/rate" (time / permits) times
     * "permits" gives time, i.e., integrals on this function (which is what storedPermitsToWaitTime()
     * computes) correspond to minimum intervals between subsequent requests, for the specified number
     * of requested permits.
     *
     * Here is an example of storedPermitsToWaitTime: If storedPermits == 10.0, and we want 3 permits,
     * we take them from storedPermits, reducing them to 7.0, and compute the throttling for these as
     * a call to storedPermitsToWaitTime(storedPermits = 10.0, permitsToTake = 3.0), which will
     * evaluate the integral of the function from 7.0 to 10.0.
     *
     * Using integrals guarantees that the effect of a single acquire(3) is equivalent to {
     * acquire(1); acquire(1); acquire(1); }, or { acquire(2); acquire(1); }, etc, since the integral
     * of the function in [7.0, 10.0] is equivalent to the sum of the integrals of [7.0, 8.0], [8.0,
     * 9.0], [9.0, 10.0] (and so on), no matter what the function is. This guarantees that we handle
     * correctly requests of varying weight (permits), /no matter/ what the actual function is - so we
     * can tweak the latter freely. (The only requirement, obviously, is that we can compute its
     * integrals).
     *
     * Note well that if, for this function, we chose a horizontal line, at height of exactly (1/QPS),
     * then the effect of the function is non-existent: we serve storedPermits at exactly the same
     * cost as fresh ones (1/QPS is the cost for each). We use this trick later.
     *
     * If we pick a function that goes /below/ that horizontal line, it means that we reduce the area
     * of the function, thus time. Thus, the RateLimiter becomes /faster/ after a period of
     * underutilization. If, on the other hand, we pick a function that goes /above/ that horizontal
     * line, then it means that the area (time) is increased, thus storedPermits are more costly than
     * fresh permits, thus the RateLimiter becomes /slower/ after a period of underutilization.
     *
     * Last, but not least: consider a RateLimiter with rate of 1 permit per second, currently
     * completely unused, and an expensive acquire(100) request comes. It would be nonsensical to just
     * wait for 100 seconds, and /then/ start the actual task. Why wait without doing anything? A much
     * better approach is to /allow/ the request right away (as if it was an acquire(1) request
     * instead), and postpone /subsequent/ requests as needed. In this version, we allow starting the
     * task immediately, and postpone by 100 seconds future requests, thus we allow for work to get
     * done in the meantime instead of waiting idly.
     *
     * This has important consequences: it means that the RateLimiter doesn't remember the time of the
     * _last_ request, but it remembers the (expected) time of the _next_ request. This also enables
     * us to tell immediately (see tryAcquire(timeout)) whether a particular timeout is enough to get
     * us to the point of the next scheduling time, since we always maintain that. And what we mean by
     * "an unused RateLimiter" is also defined by that notion: when we observe that the
     * "expected arrival time of the next request" is actually in the past, then the difference (now -
     * past) is the amount of time that the RateLimiter was formally unused, and it is that amount of
     * time which we translate to storedPermits. (We increase storedPermits with the amount of permits
     * that would have been produced in that idle time). So, if rate == 1 permit per second, and
     * arrivals come exactly one second after the previous, then storedPermits is _never_ increased --
     * we would only increase it for arrivals _later_ than the expected one second.
     */

    /**
     * This implements the following function where coldInterval = coldFactor * stableInterval.
     *
     * <pre>
     *          ^ throttling
     *          |
     *    cold  +                  /
     * interval |                 /.
     *          |                / .
     *          |               /  .   ← "warmup period" is the area of the trapezoid between
     *          |              /   .     thresholdPermits and maxPermits
     *          |             /    .
     *          |            /     .
     *          |           /      .
     *   stable +----------/  WARM .
     * interval |          .   UP  .
     *          |          . PERIOD.
     *          |          .       .
     *        0 +----------+-------+--------------→ storedPermits
     *          0 thresholdPermits maxPermits
     * </pre>
     *
     * Before going into the details of this particular function, let's keep in mind the basics:
     *
     * <ol>
     *   <li>The state of the RateLimiter (storedPermits) is a vertical line in this figure.
     *   <li>When the RateLimiter is not used, this goes right (up to maxPermits)
     *   <li>When the RateLimiter is used, this goes left (down to zero), since if we have
     *       storedPermits, we serve from those first
     *   <li>When _unused_, we go right at a constant rate! The rate at which we move to the right is
     *       chosen as maxPermits / warmupPeriod. This ensures that the time it takes to go from 0 to
     *       maxPermits is equal to warmupPeriod.
     *   <li>When _used_, the time it takes, as explained in the introductory class note, is equal to
     *       the integral of our function, between X permits and X-K permits, assuming we want to
     *       spend K saved permits.
     * </ol>
     *
     * <p>In summary, the time it takes to move to the left (spend K permits), is equal to the area of
     * the function of width == K.
     *
     * <p>Assuming we have saturated demand, the time to go from maxPermits to thresholdPermits is
     * equal to warmupPeriod. And the time to go from thresholdPermits to 0 is warmupPeriod/2. (The
     * reason that this is warmupPeriod/2 is to maintain the behavior of the original implementation
     * where coldFactor was hard coded as 3.)
     *
     * <p>It remains to calculate thresholdsPermits and maxPermits.
     *
     * <ul>
     *   <li>The time to go from thresholdPermits to 0 is equal to the integral of the function
     *       between 0 and thresholdPermits. This is thresholdPermits * stableIntervals. By (5) it is
     *       also equal to warmupPeriod/2. Therefore
     *       <blockquote>
     *       thresholdPermits = 0.5 * warmupPeriod / stableInterval
     *       </blockquote>
     *   <li>The time to go from maxPermits to thresholdPermits is equal to the integral of the
     *       function between thresholdPermits and maxPermits. This is the area of the pictured
     *       trapezoid, and it is equal to 0.5 * (stableInterval + coldInterval) * (maxPermits -
     *       thresholdPermits). It is also equal to warmupPeriod, so
     *       <blockquote>
     *       maxPermits = thresholdPermits + 2 * warmupPeriod / (stableInterval + coldInterval)
     *       </blockquote>
     * </ul>
     */
    static final class SmoothWarmingUp extends SmoothRateLimiter {
      private final long warmupPeriodMicros;
      /**
       * The slope of the line from the stable interval (when permits == 0), to the cold interval
       * (when permits == maxPermits)
       */
      private double slope;

      private double thresholdPermits;
      private double coldFactor;

      SmoothWarmingUp(
          SleepingStopwatch stopwatch, long warmupPeriod, TimeUnit timeUnit, double coldFactor) {
        super(stopwatch);
        this.warmupPeriodMicros = timeUnit.toMicros(warmupPeriod);
        this.coldFactor = coldFactor;
      }

      @Override
      void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
        double oldMaxPermits = maxPermits;
        double coldIntervalMicros = stableIntervalMicros * coldFactor;
        thresholdPermits = 0.5 * warmupPeriodMicros / stableIntervalMicros;
        maxPermits =
            thresholdPermits + 2.0 * warmupPeriodMicros / (stableIntervalMicros + coldIntervalMicros);
        slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
        if (oldMaxPermits == Double.POSITIVE_INFINITY) {
          // if we don't special-case this, we would get storedPermits == NaN, below
          storedPermits = 0.0;
        } else {
          storedPermits =
              (oldMaxPermits == 0.0)
                  ? maxPermits // initial state is cold
                  : storedPermits * maxPermits / oldMaxPermits;
        }
      }

      @Override
      long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
        double availablePermitsAboveThreshold = storedPermits - thresholdPermits;
        long micros = 0;
        // measuring the integral on the right part of the function (the climbing line)
        if (availablePermitsAboveThreshold > 0.0) {
          double permitsAboveThresholdToTake = min(availablePermitsAboveThreshold, permitsToTake);
          // TODO(cpovirk): Figure out a good name for this variable.
          double length =
              permitsToTime(availablePermitsAboveThreshold)
                  + permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake);
          micros = (long) (permitsAboveThresholdToTake * length / 2.0);
          permitsToTake -= permitsAboveThresholdToTake;
        }
        // measuring the integral on the left part of the function (the horizontal line)
        micros += (long) (stableIntervalMicros * permitsToTake);
        return micros;
      }

      private double permitsToTime(double permits) {
        return stableIntervalMicros + permits * slope;
      }

      @Override
      double coolDownIntervalMicros() {
        return warmupPeriodMicros / maxPermits;
      }
    }

    /**
     * This implements a "bursty" RateLimiter, where storedPermits are translated to zero throttling.
     * The maximum number of permits that can be saved (when the RateLimiter is unused) is defined in
     * terms of time, in this sense: if a RateLimiter is 2qps, and this time is specified as 10
     * seconds, we can save up to 2 * 10 = 20 permits.
     */
    static final class SmoothBursty extends SmoothRateLimiter {
      /** The work (permits) of how many seconds can be saved up if this RateLimiter is unused? */
      final double maxBurstSeconds;

      SmoothBursty(SleepingStopwatch stopwatch, double maxBurstSeconds) {
        super(stopwatch);
        this.maxBurstSeconds = maxBurstSeconds;
      }

      @Override
      void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
        double oldMaxPermits = this.maxPermits;
        maxPermits = maxBurstSeconds * permitsPerSecond;
        if (oldMaxPermits == Double.POSITIVE_INFINITY) {
          // if we don't special-case this, we would get storedPermits == NaN, below
          storedPermits = maxPermits;
        } else {
          storedPermits =
              (oldMaxPermits == 0.0)
                  ? 0.0 // initial state
                  : storedPermits * maxPermits / oldMaxPermits;
        }
      }

      @Override
      long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
        return 0L;
      }

      @Override
      double coolDownIntervalMicros() {
        return stableIntervalMicros;
      }
    }

    /** The currently stored permits. */
    double storedPermits;

    /** The maximum number of stored permits. */
    double maxPermits;

    /**
     * The interval between two unit requests, at our stable rate. E.g., a stable rate of 5 permits
     * per second has a stable interval of 200ms.
     */
    double stableIntervalMicros;

    /**
     * The time when the next request (no matter its size) will be granted. After granting a request,
     * this is pushed further in the future. Large requests push this further than small requests.
     */
    private long nextFreeTicketMicros = 0L; // could be either in the past or future

    private SmoothRateLimiter(SleepingStopwatch stopwatch) {
      super(stopwatch);
    }

    @Override
    final void doSetRate(double permitsPerSecond, long nowMicros) {
      resync(nowMicros);
      double stableIntervalMicros = SECONDS.toMicros(1L) / permitsPerSecond;
      this.stableIntervalMicros = stableIntervalMicros;
      doSetRate(permitsPerSecond, stableIntervalMicros);
    }

    abstract void doSetRate(double permitsPerSecond, double stableIntervalMicros);

    @Override
    final double doGetRate() {
      return SECONDS.toMicros(1L) / stableIntervalMicros;
    }

    @Override
    final long queryEarliestAvailable(long nowMicros) {
      return nextFreeTicketMicros;
    }

    @Override
    final long reserveEarliestAvailable(int requiredPermits, long nowMicros) {
      resync(nowMicros);
      long returnValue = nextFreeTicketMicros;
      double storedPermitsToSpend = min(requiredPermits, this.storedPermits);
      double freshPermits = requiredPermits - storedPermitsToSpend;
      long waitMicros =
          storedPermitsToWaitTime(this.storedPermits, storedPermitsToSpend)
              + (long) (freshPermits * stableIntervalMicros);

      this.nextFreeTicketMicros = saturatedAdd(nextFreeTicketMicros, waitMicros);
      this.storedPermits -= storedPermitsToSpend;
      return returnValue;
    }

    /**
     * Translates a specified portion of our currently stored permits which we want to spend/acquire,
     * into a throttling time. Conceptually, this evaluates the integral of the underlying function we
     * use, for the range of [(storedPermits - permitsToTake), storedPermits].
     *
     * <p>This always holds: {@code 0 <= permitsToTake <= storedPermits}
     */
    abstract long storedPermitsToWaitTime(double storedPermits, double permitsToTake);

    /**
     * Returns the number of microseconds during cool down that we have to wait to get a new permit.
     */
    abstract double coolDownIntervalMicros();

    /** Updates {@code storedPermits} and {@code nextFreeTicketMicros} based on the current time. */
    void resync(long nowMicros) {
      // if nextFreeTicket is in the past, resync to now
      if (nowMicros > nextFreeTicketMicros) {
        double newPermits = (nowMicros - nextFreeTicketMicros) / coolDownIntervalMicros();
        storedPermits = min(maxPermits, storedPermits + newPermits);
        nextFreeTicketMicros = nowMicros;
      }
    }

    private static long saturatedAdd(long a, long b) {
      long naiveSum = a + b;
      if ((a ^ b) < 0 | (a ^ naiveSum) >= 0) {
        // If a and b have different signs or a has the same sign as the result then there was no
        // overflow, return.
        return naiveSum;
      }
      // we did over/under flow, if the sign is negative we should return MAX otherwise MIN
      return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
    }
  }

  /**
   * An object that accurately measures <i>elapsed time</i>: the measured duration between two
   * successive readings of "now" in the same process.
   *
   * <p>In contrast, <i>wall time</i> is a reading of "now" as given by a method like
   * {@link System#currentTimeMillis()}, best represented as an Instant. Such values
   * <i>can</i> be subtracted to obtain a {@code Duration} (such as by {@code Duration.between}), but
   * doing so does <i>not</i> give a reliable measurement of elapsed time, because wall time readings
   * are inherently approximate, routinely affected by periodic clock corrections. Because this class
   * (by default) uses {@link System#nanoTime}, it is unaffected by these changes.
   *
   * <p>Use this class instead of direct calls to {@link System#nanoTime} for two reasons:
   *
   * <ul>
   *   <li>The raw {@code long} values returned by {@code nanoTime} are meaningless and unsafe to use
   *       in any other way than how {@code Stopwatch} uses them.
   *   <li>An alternative source of nanosecond ticks can be substituted, for example for testing or
   *       performance reasons, without affecting most of your code.
   * </ul>
   *
   * <p>Basic usage:
   *
   * <pre>{@code
   * Stopwatch stopwatch = Stopwatch.createStarted();
   * doSomething();
   * stopwatch.stop(); // optional
   *
   * Duration duration = stopwatch.elapsed();
   *
   * log.info("time: " + stopwatch); // formatted string like "12.3 ms"
   * }</pre>
   *
   * <p>The state-changing methods are not idempotent; it is an error to start or stop a stopwatch
   * that is already in the desired state.
   *
   * <p>When testing code that uses this class, use {@link #createUnstarted(Ticker)} or {@link
   * #createStarted(Ticker)} to supply a fake or mock ticker. This allows you to simulate any valid
   * behavior of the stopwatch.
   *
   * <p><b>Note:</b> This class is not thread-safe.
   *
   * <p><b>Warning for Android users:</b> a stopwatch with default behavior may not continue to keep
   * time while the device is asleep. Instead, create one like this:
   *
   * <pre>{@code
   * Stopwatch.createStarted(
   *      new Ticker() {
   *        public long read() {
   *          return android.os.SystemClock.elapsedRealtimeNanos(); // requires API Level 17
   *        }
   *      });
   * }</pre>
   *
   * @author Kevin Bourrillion
   * @since 10.0
   */
  @SuppressWarnings("GoodTime") // lots of violations
  static final class Stopwatch {
    private final Ticker ticker;
    private boolean isRunning;
    private long elapsedNanos;
    private long startTick;

    /**
     * Creates (but does not start) a new stopwatch using {@link System#nanoTime} as its time source.
     *
     * @since 15.0
     */
    static Stopwatch createUnstarted() {
      return new Stopwatch();
    }

    /**
     * Creates (but does not start) a new stopwatch, using the specified time source.
     *
     * @since 15.0
     */
    static Stopwatch createUnstarted(Ticker ticker) {
      return new Stopwatch(ticker);
    }

    /**
     * Creates (and starts) a new stopwatch using {@link System#nanoTime} as its time source.
     *
     * @since 15.0
     */
    static Stopwatch createStarted() {
      return new Stopwatch().start();
    }

    /**
     * Creates (and starts) a new stopwatch, using the specified time source.
     *
     * @since 15.0
     */
    static Stopwatch createStarted(Ticker ticker) {
      return new Stopwatch(ticker).start();
    }

    Stopwatch() {
      this.ticker = Ticker.systemTicker();
    }

    Stopwatch(Ticker ticker) {
      this.ticker = checkNotNull(ticker, "ticker");
    }

    /**
     * Returns {@code true} if {@link #start()} has been called on this stopwatch, and {@link #stop()}
     * has not been called since the last call to {@code start()}.
     */
    boolean isRunning() {
      return isRunning;
    }

    /**
     * Starts the stopwatch.
     *
     * @return this {@code Stopwatch} instance
     * @throws IllegalStateException if the stopwatch is already running.
     */
    Stopwatch start() {
      checkState(!isRunning, "This stopwatch is already running.");
      isRunning = true;
      startTick = ticker.read();
      return this;
    }

    /**
     * Stops the stopwatch. Future reads will return the fixed duration that had elapsed up to this
     * point.
     *
     * @return this {@code Stopwatch} instance
     * @throws IllegalStateException if the stopwatch is already stopped.
     */
    Stopwatch stop() {
      long tick = ticker.read();
      checkState(isRunning, "This stopwatch is already stopped.");
      isRunning = false;
      elapsedNanos += tick - startTick;
      return this;
    }

    /**
     * Sets the elapsed time for this stopwatch to zero, and places it in a stopped state.
     *
     * @return this {@code Stopwatch} instance
     */
    Stopwatch reset() {
      elapsedNanos = 0;
      isRunning = false;
      return this;
    }

    private long elapsedNanos() {
      return isRunning ? ticker.read() - startTick + elapsedNanos : elapsedNanos;
    }

    /**
     * Returns the current elapsed time shown on this stopwatch, expressed in the desired time unit,
     * with any fraction rounded down.
     *
     * <p><b>Note:</b> the overhead of measurement can be more than a microsecond, so it is generally
     * not useful to specify {@link TimeUnit#NANOSECONDS} precision here.
     *
     * <p>It is generally not a good idea to use an ambiguous, unitless {@code long} to represent
     * elapsed time. Therefore, we recommend using {@link #elapsed()} instead, which returns a
     * strongly-typed {@code Duration} instance.
     *
     * @since 14.0 (since 10.0 as {@code elapsedTime()})
     */
    long elapsed(TimeUnit desiredUnit) {
      return desiredUnit.convert(elapsedNanos(), NANOSECONDS);
    }

    /**
     * Returns the current elapsed time shown on this stopwatch as a {@link Duration}. Unlike {@link
     * #elapsed(TimeUnit)}, this method does not lose any precision due to rounding.
     *
     * @since 22.0
     */
    Duration elapsed() {
      return Duration.ofNanos(elapsedNanos());
    }

    /** Returns a string representation of the current elapsed time. */
    @Override
    public String toString() {
      long nanos = elapsedNanos();

      TimeUnit unit = chooseUnit(nanos);
      double value = (double) nanos / NANOSECONDS.convert(1, unit);

      // Too bad this functionality is not exposed as a regular method call
      return String.format(Locale.ROOT, "%.4g", value) + " " + abbreviate(unit);
    }

    private static TimeUnit chooseUnit(long nanos) {
      if (DAYS.convert(nanos, NANOSECONDS) > 0) {
        return DAYS;
      }
      if (HOURS.convert(nanos, NANOSECONDS) > 0) {
        return HOURS;
      }
      if (MINUTES.convert(nanos, NANOSECONDS) > 0) {
        return MINUTES;
      }
      if (SECONDS.convert(nanos, NANOSECONDS) > 0) {
        return SECONDS;
      }
      if (MILLISECONDS.convert(nanos, NANOSECONDS) > 0) {
        return MILLISECONDS;
      }
      if (MICROSECONDS.convert(nanos, NANOSECONDS) > 0) {
        return MICROSECONDS;
      }
      return NANOSECONDS;
    }

    private static String abbreviate(TimeUnit unit) {
      switch (unit) {
        case NANOSECONDS:
          return "ns";
        case MICROSECONDS:
          return "\u03bcs"; // μs
        case MILLISECONDS:
          return "ms";
        case SECONDS:
          return "s";
        case MINUTES:
          return "min";
        case HOURS:
          return "h";
        case DAYS:
          return "d";
        default:
          throw new AssertionError();
      }
    }
  }

  /**
   * A time source; returns a time value representing the number of nanoseconds elapsed since some
   * fixed but arbitrary point in time. Note that most users should use {@link Stopwatch} instead of
   * interacting with this class directly.
   *
   * <p><b>Warning:</b> this interface can only be used to measure elapsed time, not wall time.
   *
   * @author Kevin Bourrillion
   * @since 10.0 (<a href="https://github.com/google/guava/wiki/Compatibility">mostly
   *     source-compatible</a> since 9.0)
   */
  abstract static class Ticker {
    /** Constructor for use by subclasses. */
    protected Ticker() {}

    /** Returns the number of nanoseconds elapsed since this ticker's fixed point of reference. */
    abstract long read();

    /**
     * A ticker that reads the current time using {@link System#nanoTime}.
     *
     * @since 10.0
     */
    static Ticker systemTicker() {
      return SYSTEM_TICKER;
    }

    private static final Ticker SYSTEM_TICKER =
        new Ticker() {
          @Override
          public long read() {
            return System.nanoTime();
          }
        };
  }

  static class GuavaPreconditions {

    static <T> T checkNotNull(T reference, Object errorMessage) {
      if (reference == null) {
        throw new NullPointerException(String.valueOf(errorMessage));
      }
      return reference;
    }

    static void checkState(boolean expression, Object errorMessage) {
      if (!expression) {
        throw new IllegalStateException(String.valueOf(errorMessage));
      }
    }

    static void checkArgument(boolean b, String errorMessageTemplate, long p1) {
      if (!b) {
        throw new IllegalArgumentException(lenientFormat(errorMessageTemplate, p1));
      }
    }

    static String lenientFormat(
        String template, Object... args) {
      template = String.valueOf(template); // null -> "null"

      if (args == null) {
        args = new Object[] {"(Object[])null"};
      } else {
        for (int i = 0; i < args.length; i++) {
          args[i] = lenientToString(args[i]);
        }
      }

      // start substituting the arguments into the '%s' placeholders
      StringBuilder builder = new StringBuilder(template.length() + 16 * args.length);
      int templateStart = 0;
      int i = 0;
      while (i < args.length) {
        int placeholderStart = template.indexOf("%s", templateStart);
        if (placeholderStart == -1) {
          break;
        }
        builder.append(template, templateStart, placeholderStart);
        builder.append(args[i++]);
        templateStart = placeholderStart + 2;
      }
      builder.append(template, templateStart, template.length());

      // if we run out of placeholders, append the extra args in square braces
      if (i < args.length) {
        builder.append(" [");
        builder.append(args[i++]);
        while (i < args.length) {
          builder.append(", ");
          builder.append(args[i++]);
        }
        builder.append(']');
      }

      return builder.toString();
    }

    private static String lenientToString(Object o) {
      if (o == null) {
        return "null";
      }
      try {
        return o.toString();
      } catch (Exception e) {
        // Default toString() behavior - see Object.toString()
        String objectToString =
            o.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(o));
        // Logger is created inline with fixed name to avoid forcing Proguard to create another class.
        Logger.getLogger("com.google.common.base.Strings")
            .log(WARNING, "Exception during lenientFormat for " + objectToString, e);
        return "<" + objectToString + " threw " + e.getClass().getName() + ">";
      }
    }

    static <T> T checkNotNull(T reference) {
      if (reference == null) {
        throw new NullPointerException();
      }
      return reference;
    }

    static void checkArgument(boolean expression, Object errorMessage) {
      if (!expression) {
        throw new IllegalArgumentException(String.valueOf(errorMessage));
      }
    }
  }
}
