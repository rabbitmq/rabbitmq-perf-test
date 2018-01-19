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

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ThreadFactory} that names threads with a prefix and a sequence number.
 * @since 2.1.0
 */
public class NamedThreadFactory implements ThreadFactory {

    private final ThreadFactory backingThreaFactory;

    private final String prefix;

    private final AtomicLong count = new AtomicLong(0);

    public NamedThreadFactory(String prefix) {
        this(Executors.defaultThreadFactory(), prefix);
    }

    public NamedThreadFactory(ThreadFactory backingThreaFactory, String prefix) {
        this.backingThreaFactory = backingThreaFactory;
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = this.backingThreaFactory.newThread(r);
        thread.setName(prefix + count.getAndIncrement());
        return thread;
    }
}
