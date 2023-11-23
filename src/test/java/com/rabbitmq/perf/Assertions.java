// Copyright (c) 2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.fail;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.AbstractObjectAssert;

abstract class Assertions {

  private Assertions() {}

  static MeterRegistryAssert assertThat(MeterRegistry registry) {
    return new MeterRegistryAssert(registry);
  }

  static GaugeAssert assertThat(Gauge gauge) {
    return new GaugeAssert(gauge);
  }

  static class MeterRegistryAssert
      extends AbstractObjectAssert<MeterRegistryAssert, MeterRegistry> {

    public MeterRegistryAssert(MeterRegistry meterRegistry) {
      super(meterRegistry, MeterRegistryAssert.class);
    }

    public MeterRegistryAssert has(String name) {
      isNotNull();
      if (this.actual.find(name).meter() == null) {
        failWithMessage(
            "Expected meter %s, but not found among %s",
            name,
            this.actual.getMeters().stream()
                .map(Meter::getId)
                .map(Id::getName)
                .collect(toList())
                .toString());
      }
      return this;
    }

    public MeterRegistryAssert isEmpty() {
      isNotNull();
      if (this.actual.getMeters().size() > 0) {
        fail(
            "Expected meter registry to be empty, but has %d meter(s)",
            this.actual.getMeters().size());
      }
      return this;
    }

    public GaugeAssert gauge(String name) {
      has(name);
      Meter meter = this.actual.get(name).meter();
      if (!(meter instanceof Gauge)) {
        fail("Expected to be a gauge but is %s", meter.getClass().getSimpleName());
      }
      return new GaugeAssert((Gauge) this.actual.get(name).meter());
    }
  }

  public static class GaugeAssert extends AbstractObjectAssert<GaugeAssert, Gauge> {

    public GaugeAssert(Gauge gauge) {
      super(gauge, GaugeAssert.class);
    }

    GaugeAssert hasValue(double expectedValue) {
      isNotNull();
      if (this.actual.value() != expectedValue) {
        fail("Expected gauge value to be %f but is %f", expectedValue, this.actual.value());
      }
      return this;
    }
  }
}
