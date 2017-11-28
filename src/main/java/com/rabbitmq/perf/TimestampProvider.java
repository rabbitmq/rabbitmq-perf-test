// Copyright (c) 2007-Present Pivotal Software, Inc.  All rights reserved.
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

public class TimestampProvider {
    private final boolean useMillis;
    private final boolean isTimestampInHeader;

    public TimestampProvider(boolean useMillis, boolean isTimestampInHeader) {
        this.useMillis = useMillis;
        this.isTimestampInHeader = isTimestampInHeader;
    }

    public boolean isTimestampInHeader() {
        return this.isTimestampInHeader;
    }

    public long getCurrentTime() {
        if (useMillis) {
            return System.currentTimeMillis();
        } else {
            return System.nanoTime();
        }
    }

    public long getDifference(long ts1, long ts2) {
        return Math.abs(ts1 - ts2);
    }
}
