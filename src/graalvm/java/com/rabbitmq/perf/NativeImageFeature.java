// Copyright (c) 2018 Pivotal Software, Inc.  All rights reserved.
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

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.DoubleRecorder;
import org.HdrHistogram.Histogram;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.RuntimeReflection;
import org.slf4j.impl.StaticLoggerBinder;

/**
 * GraalVM feature to customize image generation.
 *
 * @since 2.4.0
 */
public class NativeImageFeature implements Feature {

    public NativeImageFeature() {

    }

    @Override
    public void beforeAnalysis(Feature.BeforeAnalysisAccess access) {
        /**
         * Register classes and method during native image generation.
         * These classes are used by reflection in some PerfTest dependencies
         * so they need to be registered explicitly when building a native
         * image with GraalVM.
         */
        RuntimeReflection.register(DoubleRecorder[].class);
        RuntimeReflection.register(StaticLoggerBinder.class);
        try {
            RuntimeReflection.register(Histogram.class.getConstructor(long.class, long.class, int.class));
            RuntimeReflection.register(ConcurrentHistogram.class.getConstructor(long.class, long.class, int.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Error while loading methods for native image build", e);
        }
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

}
