// Copyright (c) 2018-2020 Pivotal Software, Inc.  All rights reserved.
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

import com.rabbitmq.client.Address;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.RecoveryDelayHandler;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class Utils {

    private static final ConnectionFactory CF = new ConnectionFactory();

    static boolean isRecoverable(Connection connection) {
        return connection instanceof AutorecoveringConnection;
    }

    static synchronized Address extract(String uri) throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
        CF.setUri(uri);
        return new Address(CF.getHost(), CF.getPort());
    }

    /**
     * @param argument
     * @return
     * @since 2.11.0
     */
    static RecoveryDelayHandler getRecoveryDelayHandler(String argument) {
        if (argument == null || argument.trim().isEmpty()) {
            return null;
        }
        argument = argument.trim();
        Pattern pattern = Pattern.compile("(\\d+)(-(\\d+))?");
        Matcher matcher = pattern.matcher(argument);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Incorrect argument for connection recovery interval. Must be e.g. 30 or 30-60.");
        }

        RecoveryDelayHandler handler;
        final long delay = Long.parseLong(matcher.group(1)) * 1000;
        if (matcher.group(2) == null) {
            handler = recoveryAttempts -> delay;
        } else {
            final long maxInput = Long.parseLong(matcher.group(2).replace("-", "")) * 1000;
            if (maxInput <= delay) {
                throw new IllegalArgumentException("Wrong interval min-max values: " + argument);
            }
            final long maxDelay = maxInput + 1000;
            handler = recoveryAttempts -> ThreadLocalRandom.current().nextLong(delay, maxDelay);
        }
        return handler;
    }

    static final Future<?> NO_OP_FUTURE = new Future<Object>() {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    };

}
