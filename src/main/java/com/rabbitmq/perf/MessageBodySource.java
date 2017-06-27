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

import java.io.IOException;

/**
 * Sources produce message bodies and content type
 * used by publishers.
 */
public interface MessageBodySource {

    MessageBodyAndContentType create(int sequenceNumber) throws IOException;

    class MessageBodyAndContentType {
        private final byte [] body;
        private final String contentType;

        public MessageBodyAndContentType(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        public byte[] getBody() {
            return body;
        }

        public String getContentType() {
            return contentType;
        }
    }

}
