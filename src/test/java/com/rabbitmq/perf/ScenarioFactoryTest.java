// Copyright (c) 2010 Pivotal Software, Inc.  All rights reserved.
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

import com.rabbitmq.tools.json.JSONReader;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;

public class ScenarioFactoryTest {

    @Test
    public void paramsFromJSON() {
        String spec = "[{'name': 'consume', 'type': 'simple', 'params':\n" +
                "[{'time-limit': 30, 'producer-count': 4, 'consumer-count': 2, " +
                "  'rate': 10, 'exclusive': true, " +
                "  'body': ['file1.json','file2.json'], 'body-content-type' : 'application/json'}]}]";
        List<Map> scenariosJson = (List<Map>) new JSONReader().read(spec);
        Map scenario = scenariosJson.get(0);
        MulticastParams params = ScenarioFactory.paramsFromJSON((Map) ((List) scenario.get("params")).get(0));
        assertThat(params.getTimeLimit(), is(30));
        assertThat(params.getProducerCount(), is(4));
        assertThat(params.getConsumerCount(), is(2));
        assertThat(params.getProducerRateLimit(), is(10.0f));
        assertThat(params.isExclusive(), is(true));
        assertThat(params.getBodyFiles(), hasSize(2));
        assertThat(params.getBodyFiles(), contains("file1.json", "file2.json"));
        assertThat(params.getBodyContentType(), is("application/json"));
    }

}
