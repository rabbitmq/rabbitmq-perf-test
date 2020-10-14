// Copyright (c) 2018-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;

public class MulticastParamsTest {

    MulticastParams params;

    static Stream<Arguments> hasLimitArguments() {
        return Stream.of(
                of(tc("No limit when time or message count are not limited", -1, -1, -1, false)),
                of(tc("Limited if time limit", 10, -1, -1, true)),
                of(tc("Limited if consumer message count limit", -1, 1000, -1, true)),
                of(tc("Limited if producer message count limit", -1, -1, 1000, true)),
                of(tc("Limited if consumer message and producer message count limit", -1, 1000, 1000, true)),
                of(tc("Limited if time and consumer message count limit", 10, 1000, -1, true)),
                of(tc("Limited if time and producer message count limit", 10, -1, 1000, true)),
                of(tc("Limited if time, consumer message, producer message count limit", 10, 1000, 1000, true))
        );
    }

    static TestConfiguration tc(String description, int timeLimit, int consumerMsgCount, int producerMsgCount, boolean expected) {
        return new TestConfiguration(description, p -> {
            p.setTimeLimit(timeLimit);
            p.setConsumerMsgCount(consumerMsgCount);
            p.setProducerMsgCount(producerMsgCount);
        }, expected);
    }

    @BeforeEach
    public void init() {
        params = new MulticastParams();
    }

    @ParameterizedTest
    @MethodSource("hasLimitArguments")
    public void hasLimit(TestConfiguration tc) {
        tc.configurer.accept(params);
        assertEquals(tc.expected, params.hasLimit(), tc.description);
    }

    static class TestConfiguration {

        String description;
        Consumer<MulticastParams> configurer;
        boolean expected;

        public TestConfiguration(String description, Consumer<MulticastParams> configurer, boolean expected) {
            this.description = description;
            this.configurer = configurer;
            this.expected = expected;
        }
    }

}
