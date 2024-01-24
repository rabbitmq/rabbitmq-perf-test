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

import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class ByteCapacityTest {

  static Stream<Arguments> fromOkArguments() {
    return of(
            arguments("100tb", 100_000_000_000_000L),
            arguments("100gb", 100_000_000_000L),
            arguments("100mb", 100_000_000L),
            arguments("100kb", 100_000L),
            arguments("100", 100L))
        .flatMap(
            arguments ->
                Stream.of(
                    arguments,
                    arguments(arguments.get()[0].toString().toUpperCase(), arguments.get()[1])));
  }

  @ParameterizedTest
  @MethodSource("fromOkArguments")
  void fromOk(String value, long expectedValueInBytes) {
    ByteCapacity capacity = ByteCapacity.from(value);
    assertThat(capacity.toBytes()).isEqualTo(expectedValueInBytes);
    assertThat(capacity.toString()).isEqualTo(value);
  }

  @ValueSource(strings = {"0tb", "0gb", "0mb", "0kb", "0"})
  @ParameterizedTest
  void zero(String value) {
    assertThat(ByteCapacity.from(value).toBytes()).isEqualTo(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"100.0gb", "abc", "100.0", "-10gb", "10b"})
  void fromKo(String value) {
    assertThatThrownBy(() -> ByteCapacity.from(value)).isInstanceOf(IllegalArgumentException.class);
  }
}
