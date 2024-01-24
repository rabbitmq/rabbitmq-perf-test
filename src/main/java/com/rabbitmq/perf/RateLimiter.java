// Copyright (c) 2023 Broadcom. All Rights Reserved.
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

  enum Type {
    GUAVA(new GuavaRateLimiterFactory());

    private final Factory factory;

    Type(Factory factory) {
      this.factory = factory;
    }

    Factory factory() {
      return this.factory;
    }
  }
}
