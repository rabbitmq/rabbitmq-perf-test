// Copyright (c) 2018-2023 Broadcom. All Rights Reserved.
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class SequenceTopologyHandlerTest {

  MulticastParams.SequenceTopologyHandler handler;

  @Test
  public void sequence() {
    handler =
        new MulticastParams.SequenceTopologyHandler(
            null, 1, 5, "test-%d", new TopologyRecording(false, false), null);
    assertThat(handler.getQueueNames())
        .hasSize(5)
        .contains("test-1", "test-2", "test-3", "test-4", "test-5");

    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-1");
    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-2");
    assertThat(handler.getRoutingKey()).isEqualTo("test-2");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-3");
    assertThat(handler.getRoutingKey()).isEqualTo("test-3");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-4");
    assertThat(handler.getRoutingKey()).isEqualTo("test-4");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-5");
    assertThat(handler.getRoutingKey()).isEqualTo("test-5");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-1");
    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
  }

  @Test
  public void useFixedRoutingKeyWhenProvided() {
    handler =
        new MulticastParams.SequenceTopologyHandler(
            null, 1, 5, "test-%d", new TopologyRecording(false, false), "rk");
    assertThat(handler.getQueueNames())
        .hasSize(5)
        .contains("test-1", "test-2", "test-3", "test-4", "test-5");

    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-1");
    assertThat(handler.getRoutingKey()).isEqualTo("rk");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-2");
    assertThat(handler.getRoutingKey()).isEqualTo("rk");
  }

  @Test
  public void reset() {
    handler =
        new MulticastParams.SequenceTopologyHandler(
            null, 1, 100, "test-%d", new TopologyRecording(false, false), null);
    assertThat(handler.getQueueNames()).hasSize(100);

    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-1");
    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-2");
    assertThat(handler.getRoutingKey()).isEqualTo("test-2");
    handler.next();

    handler.reset();

    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-1");
    assertThat(handler.getRoutingKey()).isEqualTo("test-1");
    handler.next();
    assertThat(handler.getQueueNamesForClient()).hasSize(1).contains("test-2");
    assertThat(handler.getRoutingKey()).isEqualTo("test-2");
  }

  @Test
  public void format() {
    handler =
        new MulticastParams.SequenceTopologyHandler(
            null, 1, 5, "test-%03d", new TopologyRecording(false, false), null);
    assertThat(handler.getQueueNames())
        .hasSize(5)
        .contains("test-001", "test-002", "test-003", "test-004", "test-005");
  }
}
