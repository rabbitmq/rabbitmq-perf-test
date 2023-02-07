// Copyright (c) 2018-2023 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 *
 */
public interface Metrics {

    Options options();

    default void configure(ConfigurationContext context) throws Exception { }

    default boolean isEnabled(CommandLineProxy cmd) {
        for (Object optObj : this.options().getOptions()) {
            Option option = (Option) optObj;
            if (cmd.hasOption(option.getOpt())) {
                return true;
            }
        }
        return false;
    }

    default void start() {
    }

    default void close() throws Exception { }

    class ConfigurationContext {

        private final CommandLineProxy cmd;
        private final CompositeMeterRegistry meterRegistry;
        private final ConnectionFactory factory;
        private final String [] args;
        private final String metricsPrefix;
        private final Options metricsOptions;

        public ConfigurationContext(CommandLineProxy cmd,
            CompositeMeterRegistry meterRegistry, ConnectionFactory factory,
            String[] args, String metricsPrefix,
            Options metricsOptions) {
            this.cmd = cmd;
            this.meterRegistry = meterRegistry;
            this.factory = factory;
            this.args = args;
            this.metricsPrefix = metricsPrefix;
            this.metricsOptions = metricsOptions;
        }

        public CommandLineProxy cmd() {
            return cmd;
        }

        public CompositeMeterRegistry meterRegistry() {
            return meterRegistry;
        }

        public ConnectionFactory factory() {
            return factory;
        }

        public String[] args() {
            return args;
        }

        public String metricsPrefix() {
            return metricsPrefix;
        }

        public Options metricsOptions() {
            return metricsOptions;
        }
    }

}
