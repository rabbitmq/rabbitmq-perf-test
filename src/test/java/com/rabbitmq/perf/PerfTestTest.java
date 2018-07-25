// Copyright (c) 2018-Present Pivotal Software, Inc.  All rights reserved.
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 *
 */
public class PerfTestTest {

    @Test
    void getNioNbThreadsAndExecutorSize() {
        Object parameters[][] = {
            { 4, -1, 4, Integer.MAX_VALUE, "2 extra threads for executor when only number of threads is specified", true },
            { 4, 2, 4, 6, "2 extra threads for executor when specified number < nb threads", true },
            { -1, 4, 2, 4, "appropriate nb threads (-2) when only executor size is specified", true },
            { -1, 2, -1, -1, "executor should be large enough for IO threads + a couple of extra threads", false },
        };
        for (Object[] parameter : parameters) {
            String message = (String) parameter[4];
            boolean passes = (boolean) parameter[5];
            try {
                int[] nbThreadsAndExecutorSize = PerfTest.getNioNbThreadsAndExecutorSize((int) parameter[0], (int) parameter[1]);
                assertArrayEquals(
                    new int[] { (int) parameter[2], (int) parameter[3] },
                    nbThreadsAndExecutorSize,
                    message
                );
                if (!passes) {
                    fail(message + " (test should fail)");
                }
            } catch (IllegalArgumentException e) {
                if (passes) {
                    fail(message + " (test shouldn't fail)");
                }
            }
        }
    }

    @Test public void longOptionToEnvironmentVariable() {
        String [] [] parameters = {
            {"queue", "QUEUE"},
            {"routing-key", "ROUTING_KEY"},
            {"random-routing-key", "RANDOM_ROUTING_KEY"},
            {"skip-binding-queues", "SKIP_BINDING_QUEUES"},
        };
        for (String[] parameter : parameters) {
            assertEquals(
                parameter[1],
                PerfTest.LONG_OPTION_TO_ENVIRONMENT_VARIABLE.apply(parameter[0])
            );
        }
    }
}
