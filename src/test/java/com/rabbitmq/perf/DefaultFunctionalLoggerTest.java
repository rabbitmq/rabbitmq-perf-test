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

import static com.rabbitmq.perf.DefaultFunctionalLogger.details;
import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class DefaultFunctionalLoggerTest {

  @Test
  void detailsShouldExtractInternalSequenceAndTimestampFromBody() throws Exception {
    long currentTime = 20203178455988L;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    TimeSequenceMessageBodySource.doCreate(out, currentTime, 42);
    assertThat(details(currentTime, null, out.toByteArray()))
        .isEqualTo("properties = {}, body = [sequence = 42, timestamp = 20203178455988]");
  }

  @Test
  void detailsShouldFallBackToStringBody() {
    assertThat(details(0, null, "foo".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("properties = {}, body = foo");
  }

  @Test
  void detailsShouldDetectTextBody() {
    assertThat(
            details(
                0,
                new AMQP.BasicProperties.Builder().contentType("text/plain").build(),
                "foo".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("properties = {content-type = text/plain}, body = foo");
  }
}
