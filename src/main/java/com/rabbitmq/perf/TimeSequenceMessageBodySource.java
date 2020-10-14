// Copyright (c) 2017-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;

/**
 *
 */
public class TimeSequenceMessageBodySource implements MessageBodySource {

    private static final byte[] EMPTY = new byte[0];

    private final TimestampProvider tsp;

    private final BiFunction<ByteArrayOutputStream, Long, MessageEnvelope> messageCreator;

    public TimeSequenceMessageBodySource(TimestampProvider tsp, int minMsgSize) {
        this(tsp, new FixedValueIndicator<>(minMsgSize));
    }

    public TimeSequenceMessageBodySource(TimestampProvider tsp, ValueIndicator<Integer> sizeIndicator) {
        this.tsp = tsp;
        if (sizeIndicator.isVariable()) {
            List<Integer> possibleSizes = sizeIndicator.values();
            final byte[][] messages = new byte[possibleSizes.size()][];
            for (int i = 0; i < possibleSizes.size(); i++) {
                messages[i] = new byte[possibleSizes.get(i)];
            }

            this.messageCreator = (acc, time) -> {
                int size = sizeIndicator.getValue();
                byte[] message = EMPTY;
                for (byte[] m : messages) {
                    if (m.length == size) {
                        message = m;
                        break;
                    }
                }
                byte[] m = acc.toByteArray();
                if (m.length <= message.length) {
                    System.arraycopy(m, 0, message, 0, m.length);
                    return new MessageEnvelope(message, null, time);
                } else {
                    return new MessageEnvelope(m, null, time);
                }
            };
        } else {
            final byte[] message = new byte[sizeIndicator.getValue()];
            this.messageCreator = (acc, time) -> {
                byte[] m = acc.toByteArray();
                if (m.length <= message.length) {
                    System.arraycopy(m, 0, message, 0, m.length);
                    return new MessageEnvelope(message, null, time);
                } else {
                    return new MessageEnvelope(m, null, time);
                }
            };
        }

    }

    @Override
    public MessageEnvelope create(int sequenceNumber) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(acc);
        long time = tsp.getCurrentTime();
        d.writeInt(sequenceNumber);
        d.writeLong(time);
        d.flush();
        acc.flush();
        return this.messageCreator.apply(acc, time);
    }
}
