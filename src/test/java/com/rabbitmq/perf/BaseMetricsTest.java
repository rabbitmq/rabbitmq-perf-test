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

import static com.rabbitmq.perf.BaseMetrics.commandLineMetrics;
import static com.rabbitmq.perf.BaseMetrics.parseTags;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tag;
import org.apache.commons.cli.Options;
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

  @Test
  void commandLineMetricsTest() {
    Options metricsOptions = new Options();
    new BaseMetrics().options().getOptions().forEach(metricsOptions::addOption);
    new PrometheusMetrics().options().getOptions().forEach(metricsOptions::addOption);
    assertThat(
            commandLineMetrics(
                ("--uri amqp://default_user_kdId_cNrxfdolc5V7WJ:K8IYrRjh1NGqdVsaxfFa-r0KR1vGuPHB@cqv2 "
                        + "--metrics-prometheus --use-millis --servers-startup-timeout 300 "
                        + "--metrics-command-line-arguments "
                        + "-mt rabbitmq_cluster=cqv2,workload_name=test-new "
                        + "-x 1 -y 2 -u cq -c 1000 -A 1000 -q 1000 -f persistent -s 1000 "
                        + "--queue-args x-queue-version=2,x-queue-mode=lazy --auto-delete false")
                    .split(" "),
                metricsOptions))
        .isEqualTo(
            "--use-millis --servers-startup-timeout 300 "
                + "-x 1 -y 2 -u cq -c 1000 -A 1000 -q 1000 -f persistent -s 1000 "
                + "--queue-args x-queue-version=2,x-queue-mode=lazy --auto-delete false");
    assertThat(
            commandLineMetrics(
                ("-h amqp://default_user_kdId_cNrxfdolc5V7WJ:K8IYrRjh1NGqdVsaxfFa-r0KR1vGuPHB@cqv2 "
                        + "-mpr --use-millis --servers-startup-timeout 300 "
                        + "-mcla "
                        + "-mt rabbitmq_cluster=cqv2,workload_name=test-new "
                        + "-x 1 -y 2 -u cq -c 1000 -A 1000 -q 1000 -f persistent -s 1000 "
                        + "--queue-args x-queue-version=2,x-queue-mode=lazy --auto-delete false")
                    .split(" "),
                metricsOptions))
        .isEqualTo(
            "--use-millis --servers-startup-timeout 300 "
                + "-x 1 -y 2 -u cq -c 1000 -A 1000 -q 1000 -f persistent -s 1000 "
                + "--queue-args x-queue-version=2,x-queue-mode=lazy --auto-delete false");
  }
}
