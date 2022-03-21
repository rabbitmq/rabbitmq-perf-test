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

import static com.rabbitmq.perf.BaseMetrics.parseTags;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;

public class BaseMetricsTest {

  private static Tag tag(String key, String value) {
    return Tag.of(key, value);
  }

  @Test
  void parseTagsTest() {
    assertThat(parseTags(null)).isEmpty();
    assertThat(parseTags("")).isEmpty();
    assertThat(parseTags("env=performance,datacenter=eu"))
        .hasSize(2)
        .contains(tag("env", "performance"))
        .contains(tag("datacenter", "eu"));
    assertThat(parseTags("args=--queue-args \"x-max-length=100000\""))
        .hasSize(1)
        .contains(tag("args", "--queue-args \"x-max-length=100000\""));
  }
}
