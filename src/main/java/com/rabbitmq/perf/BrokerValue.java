// Copyright (c) 2007-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import java.io.IOException;

public class BrokerValue implements VariableValue {
    private final Broker broker;

    public BrokerValue(Broker broker) {
        this.broker = broker;
    }

    public void setup(MulticastParams params) throws IOException {
        broker.start();
    }

    public void teardown(MulticastParams params) {
        broker.stop();
    }

    public String getName() {
        return "broker_type";
    }

    public String getValue() {
        return broker.getName();
    }
}
