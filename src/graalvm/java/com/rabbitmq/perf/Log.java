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

import java.io.*;
import java.util.Map;

/**
 * Stub to skip logback configuration.
 * <p>
 * Logback doesn't play well with a GraalVM native image, so
 * we use SL4J simple. Configuration happens at native image
 * generation time in {@link NativeImageFeature}.
 *
 * @since 2.4.0
 */
public class Log {

    public static void configureLog() throws IOException {
    }

    /**
     * Empty placeholder to make test compile.
     *
     * @param configurationFile
     * @param loggers
     * @return
     * @throws IOException
     * @since 2.11.0
     */
    static String processConfigurationFile(InputStream configurationFile, Map<String, Object> loggers) throws IOException {
        return null;
    }

}
