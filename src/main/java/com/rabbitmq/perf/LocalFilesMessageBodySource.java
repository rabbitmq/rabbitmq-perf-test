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

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class LocalFilesMessageBodySource implements MessageBodySource {

    private final List<byte[]> bodies;

    private final String contentType;

    public LocalFilesMessageBodySource(List<String> filesNames, String contentType) throws IOException {
        bodies = new ArrayList<>(filesNames.size());
        for (String fileName : filesNames) {
            File file = new File(fileName.trim());
            if (!file.exists() || file.isDirectory()) {
                throw new IllegalArgumentException(fileName + " isn't a valid body file.");
            }
            BufferedInputStream inputStream = null;
            try {
                inputStream = new BufferedInputStream(new FileInputStream(file));
                byte [] body = new byte[(int) file.length()];
                inputStream.read(body, 0, body.length);
                bodies.add(body);
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
            }

        }
        this.contentType = contentType;
    }

    public LocalFilesMessageBodySource(List<String> filesNames) throws IOException {
        this(filesNames, null);
    }

    @Override
    public MessageBodyAndContentType create(int sequenceNumber) {
        return new MessageBodyAndContentType(
            bodies.get(sequenceNumber % bodies.size()), contentType
        );
    }
}
