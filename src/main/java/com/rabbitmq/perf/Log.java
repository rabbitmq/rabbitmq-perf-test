// Copyright (c) 2018-2023 Broadcom. All Rights Reserved.
// The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Configure logback. */
public class Log {

  public static void configureLog() throws IOException {
    if (System.getProperty("logback.configurationFile") == null) {
      String loggers =
          System.getProperty("rabbitmq.perftest.loggers") == null
              ? System.getenv("RABBITMQ_PERF_TEST_LOGGERS")
              : System.getProperty("rabbitmq.perftest.loggers");
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      InputStream configurationFile = PerfTest.class.getResourceAsStream("/logback-perf-test.xml");
      try {
        String configuration =
            processConfigurationFile(configurationFile, PerfTest.convertKeyValuePairs(loggers));
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        context.reset();
        configurator.doConfigure(
            new ByteArrayInputStream(configuration.getBytes(StandardCharsets.UTF_8)));
      } catch (JoranException je) {
        // StatusPrinter will handle this
      } finally {
        configurationFile.close();
      }
      StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }
  }

  /**
   * @param configurationFile
   * @param loggers
   * @return
   * @throws IOException
   * @since 2.11.0
   */
  static String processConfigurationFile(InputStream configurationFile, Map<String, Object> loggers)
      throws IOException {
    StringBuilder loggersConfiguration = new StringBuilder();
    if (loggers != null) {
      for (Map.Entry<String, Object> logger : loggers.entrySet()) {
        loggersConfiguration.append(
            String.format(
                "\t<logger name=\"%s\" level=\"%s\" />%s",
                logger.getKey(),
                logger.getValue().toString(),
                System.getProperty("line.separator")));
      }
    }

    BufferedReader in = new BufferedReader(new InputStreamReader(configurationFile));
    final int bufferSize = 1024;
    final char[] buffer = new char[bufferSize];
    StringBuilder builder = new StringBuilder();
    int charsRead;
    while ((charsRead = in.read(buffer, 0, buffer.length)) > 0) {
      builder.append(buffer, 0, charsRead);
    }

    return builder.toString().replace("${loggers}", loggersConfiguration);
  }
}
