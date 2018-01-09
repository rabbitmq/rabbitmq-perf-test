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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;

public class SequenceTopologyHandlerTest {

    MulticastParams.SequenceTopologyHandler handler;

    @Test
    public void sequence() {
        handler = new MulticastParams.SequenceTopologyHandler(null, 1, 5, "test-%d");
        assertThat(handler.getQueueNames(), allOf(iterableWithSize(5), hasItems("test-1", "test-2", "test-3", "test-4", "test-5")));

        assertThat(handler.getRoutingKey(), is("test-1"));
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-1")));
        assertThat(handler.getRoutingKey(), is("test-1"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-2")));
        assertThat(handler.getRoutingKey(), is("test-2"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-3")));
        assertThat(handler.getRoutingKey(), is("test-3"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-4")));
        assertThat(handler.getRoutingKey(), is("test-4"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-5")));
        assertThat(handler.getRoutingKey(), is("test-5"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-1")));
        assertThat(handler.getRoutingKey(), is("test-1"));
    }

    @Test
    public void reset() {
        handler = new MulticastParams.SequenceTopologyHandler(null, 1, 100, "test-%d");
        assertThat(handler.getQueueNames(), hasSize(100));

        assertThat(handler.getRoutingKey(), is("test-1"));
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-1")));
        assertThat(handler.getRoutingKey(), is("test-1"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-2")));
        assertThat(handler.getRoutingKey(), is("test-2"));
        handler.next();

        handler.reset();

        assertThat(handler.getRoutingKey(), is("test-1"));
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-1")));
        assertThat(handler.getRoutingKey(), is("test-1"));
        handler.next();
        assertThat(handler.getQueueNamesForClient(), allOf(iterableWithSize(1), hasItem("test-2")));
        assertThat(handler.getRoutingKey(), is("test-2"));
    }

    @Test
    public void format() {
        handler = new MulticastParams.SequenceTopologyHandler(null, 1, 5, "test-%03d");
        assertThat(handler.getQueueNames(), allOf(iterableWithSize(5), hasItems("test-001", "test-002", "test-003", "test-004", "test-005")));
    }
}
