// Copyright (c) 2020 Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
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

import com.rabbitmq.client.RecoveryDelayHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.rabbitmq.perf.Utils.getRecoveryDelayHandler;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;


public class UtilsTest {

    @ValueSource(strings = {"a", "ab", "ab1", "1-", "10-", "1-a", "10-a", "1-1a", "10-1a", "5-4", "5-5", "10-9", "10-10"})
    @ParameterizedTest
    void getRecoveryDelayHandlerIncorrectArguments(String argument) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> getRecoveryDelayHandler(argument));
    }

    @ValueSource(strings = {"6", "15"})
    @ParameterizedTest
    void getRecoveryDelayHandlerFixed(String argument) {
        RecoveryDelayHandler recoveryDelayHandler = getRecoveryDelayHandler(argument);
        assertThat(recoveryDelayHandler.getDelay(1)).isEqualTo(Long.parseLong(argument) * 1000);
        assertThat(recoveryDelayHandler.getDelay(2)).isEqualTo(Long.parseLong(argument) * 1000);
    }

    @ValueSource(strings = {"5-10", "10-20"})
    @ParameterizedTest
    void getRecoveryDelayHandlerRandom(String argument) {
        long min = Long.parseLong(argument.split("-")[0]) * 1000;
        long max = (Long.parseLong(argument.split("-")[1]) + 1) * 1000;
        RecoveryDelayHandler recoveryDelayHandler = getRecoveryDelayHandler(argument);
        range(0, 10).forEach(attempt -> assertThat(recoveryDelayHandler.getDelay(attempt)).isBetween(min, max));
    }

}
