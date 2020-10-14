// Copyright (c) 2019-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class RandomJsonMessageBodySourceTest {

    @Test
    public void bodiesAreCorrectJsonDocument() {
        RandomJsonMessageBodySource source = new RandomJsonMessageBodySource(
                1200, 10000, 50
        );
        assertThat(source.bodies()).hasSize(50);
        Gson gson = new Gson();
        for (byte[] body : source.bodies()) {
            gson.fromJson(new String(body), Map.class);
        }
    }

    @Test
    public void bodiesAreDifferent() {
        RandomJsonMessageBodySource source = new RandomJsonMessageBodySource(
                128000, 10000, 50
        );
        assertThat(source.bodies()).hasSize(50);
        Set<String> bodies = new HashSet<>();
        IntStream.range(0, 100).forEach(i -> bodies.add(new String(source.create(0).getBody())));
        assertThat(bodies).hasSizeGreaterThan(1);
    }

    @Test
    public void sizeConstraintIsEnforced() {
        RandomJsonMessageBodySource source = new RandomJsonMessageBodySource(
                12000, 1000, 50000
        );
        assertThat(source.bodies()).hasSize(50000);
        // an extra field could be added at the very end and make the body bigger than expected
        // worst case:
        // ,"max-is-30" : "max-is-200" }
        // which is 240
        int maxSize = 12000 + 240;
        for (byte[] body : source.bodies()) {
            assertThat(body).hasSizeBetween(12000, maxSize);
        }
    }

}
