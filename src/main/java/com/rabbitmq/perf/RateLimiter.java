// Copyright (c) 2023 VMware, Inc. or its affiliates.  All rights reserved.
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

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;

interface RateLimiter {

  void acquire();

  interface Factory {

    RateLimiter create(double rate);
  }

  class GuavaRateLimiterFactory implements Factory {

    @Override
    public RateLimiter create(double rate) {
      return new GuavaRateLimiter(RateLimiterUtils.RateLimiter.create(rate));
    }
  }

  class GuavaRateLimiter implements RateLimiter {

    private final RateLimiterUtils.RateLimiter delegate;

    public GuavaRateLimiter(RateLimiterUtils.RateLimiter delegate) {
      this.delegate = delegate;
    }

    @Override
    public void acquire() {
      this.delegate.acquire();
    }
  }

  class Resilience4jRateLimiterFactory implements Factory {

    @Override
    public RateLimiter create(double rate) {
      RateLimiterConfig config =
          RateLimiterConfig.custom()
              .timeoutDuration(Duration.ofSeconds(10))
              .limitRefreshPeriod(Duration.ofSeconds(1))
              .limitForPeriod((int) rate)
              .build();
      return new Resilience4jRateLimiter(
          io.github.resilience4j.ratelimiter.RateLimiter.of("perftest", config));
    }
  }

  class Resilience4jRateLimiter implements RateLimiter {

    private final io.github.resilience4j.ratelimiter.RateLimiter delegate;

    public Resilience4jRateLimiter(io.github.resilience4j.ratelimiter.RateLimiter delegate) {
      this.delegate = delegate;
    }

    @Override
    public void acquire() {
      delegate.acquirePermission();
    }
  }

  enum Type {
    GUAVA(new GuavaRateLimiterFactory()),
    RESILIENCE4J(new Resilience4jRateLimiterFactory());

    private final Factory factory;

    Type(Factory factory) {
      this.factory = factory;
    }

    Factory factory() {
      return this.factory;
    }
  }
}
