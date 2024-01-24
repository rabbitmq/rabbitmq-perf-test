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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ShutdownServiceTest {

  ShutdownService service = new ShutdownService();

  @Test
  public void closeIsLifoAndIdempotent() throws Exception {
    List<AutoCloseable> closeCallbacks = new ArrayList<>();
    List<String> markers = new ArrayList<>();
    closeCallbacks.add(service.wrap(() -> markers.add("1")));
    closeCallbacks.add(service.wrap(() -> markers.add("2")));
    closeCallbacks.add(service.wrap(() -> markers.add("3")));
    service.close();
    assertThat(markers).hasSize(closeCallbacks.size()).contains("3", "2", "1");
    for (AutoCloseable closeCallback : closeCallbacks) {
      closeCallback.close();
    }
    assertThat(markers).hasSize(closeCallbacks.size()).contains("3", "2", "1");
  }
}
