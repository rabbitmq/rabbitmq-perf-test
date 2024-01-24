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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class CachingRoutingKeyGeneratorTest {

  Supplier<String> generator;

  @ParameterizedTest
  @ValueSource(ints = {1, 10, 100})
  public void cacheValues(int cacheSize) {
    generator = new Producer.CachingRoutingKeyGenerator(cacheSize);
    Set<String> keys = new HashSet<>();
    IntStream.range(0, 1000).forEach(i -> keys.add(generator.get()));
    assertThat(keys).hasSize(cacheSize);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, -10})
  public void cacheSizeMustBeGreaterThanZero(int size) {
    assertThrows(
        IllegalArgumentException.class, () -> new Producer.CachingRoutingKeyGenerator(size));
  }
}
