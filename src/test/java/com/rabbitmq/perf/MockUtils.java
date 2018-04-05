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

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 *
 */
public class MockUtils {

    private static final AtomicInteger PROXY_COUNTER = new AtomicInteger(0);

    @SuppressWarnings("unchecked")
    public static <T> T proxy(Class<T> clazz, ProxyCallback... callbacks) {
        final int id = PROXY_COUNTER.incrementAndGet();
        ProxyCallback toString = new ProxyCallback("toString", (proxy, method, args) -> clazz.getSimpleName() + " " + id);
        ProxyCallback[] proxyCallbacks = Arrays.copyOf(callbacks, callbacks.length + 1);
        proxyCallbacks[callbacks.length] = toString;
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, (proxy, method, args) -> {
            for (ProxyCallback callback : proxyCallbacks) {
                if (method.getName().equals(callback.method)) {
                    return callback.handler.invoke(proxy, method, args);
                }
            }
            // FIXME would be useful to inspect return type and use reasonable default
            // (e.g. false for boolean, 0 for numbers, etc)
            // this can be useful for future changes of code
            if (long.class.equals(method.getReturnType()) || Long.class.equals(method.getReturnType())) {
                return 0L;
            } else if (boolean.class.equals(method.getReturnType()) || Boolean.class.equals(method.getReturnType())) {
                return false;
            }
            return null;
        });
    }

    public static ProxyCallback callback(String method, InvocationHandler handler) {
        return new ProxyCallback(method, handler);
    }

    public static ConnectionFactory connectionFactoryThatReturns(Connection c) {
        return new ConnectionFactory() {

            @Override
            public Connection newConnection(String name) {
                return c;
            }
        };
    }

    public static ConnectionFactory connectionFactoryThatReturns(Supplier<Connection> supplier) {
        return new ConnectionFactory() {
            @Override
            public Connection newConnection(String name) {
                return supplier.get();
            }
        };
    }

    public static class ProxyCallback {

        final String method;
        final InvocationHandler handler;

        private ProxyCallback(String method, InvocationHandler handler) {
            this.method = method;
            this.handler = handler;
        }
    }
}
