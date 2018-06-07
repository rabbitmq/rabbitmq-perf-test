// Copyright (c) 2018 Pivotal Software, Inc.  All rights reserved.
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

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 *
 */
public class BaseMetrics implements Metrics {

    @Override
    public Options options() {
        Options options = new Options();
        options.addOption(new Option("mt", "metrics-tags", true, "metrics tags as key-value pairs separated by commas"));

        // TODO add arguments for JVM metrics

        return options;
    }

    @Override
    public String toString() {
        return "Base Metrics";
    }
}
