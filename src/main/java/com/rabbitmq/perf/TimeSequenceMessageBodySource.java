// Copyright (c) 2017-Present Pivotal Software, Inc.  All rights reserved.
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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 *
 */
public class TimeSequenceMessageBodySource implements MessageBodySource {

    private final TimestampProvider tsp;
    private final byte[] message;

    public TimeSequenceMessageBodySource(TimestampProvider tsp, int minMsgSize) {
        this.tsp = tsp;
        this.message = new byte[minMsgSize];
    }

    @Override
    public MessageBodyAndContentType create(int sequenceNumber) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(acc);
        long time = tsp.getCurrentTime();
        d.writeInt(sequenceNumber);
        d.writeLong(time);
        d.flush();
        acc.flush();
        byte[] m = acc.toByteArray();
        if (m.length <= message.length) {
            System.arraycopy(m, 0, message, 0, m.length);
            return new MessageBodyAndContentType(message, null);
        } else {
            return new MessageBodyAndContentType(m, null);
        }
    }
}
