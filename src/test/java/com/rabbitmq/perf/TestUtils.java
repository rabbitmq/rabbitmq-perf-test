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

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.fail;

import com.rabbitmq.client.ConnectionFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/** */
public final class TestUtils {

  private TestUtils() {}

  public static ConnectionFactory connectionFactory() {
    return new ConnectionFactory();
  }

  static int randomNetworkPort() throws IOException {
    ServerSocket socket = new ServerSocket();
    socket.bind(null);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }

  public static void waitAtMost(int timeoutInSeconds, BooleanSupplier condition)
      throws InterruptedException {
    waitAtMost(timeoutInSeconds, condition, null);
  }

  public static void waitAtMost(
      int timeoutInSeconds, BooleanSupplier condition, Supplier<String> message)
      throws InterruptedException {
    if (condition.getAsBoolean()) {
      return;
    }
    int waitTime = 100;
    int waitedTime = 0;
    int timeoutInMs = timeoutInSeconds * 1000;
    while (waitedTime <= timeoutInMs) {
      Thread.sleep(waitTime);
      if (condition.getAsBoolean()) {
        return;
      }
      waitedTime += waitTime;
    }
    String msg;
    if (message == null) {
      msg = "Waited " + timeoutInSeconds + " second(s), condition never got true";
    } else {
      msg = "Waited " + timeoutInSeconds + " second(s), " + message.get();
    }
    fail(msg);
  }

  public static ThreadFactory threadFactory(TestInfo info) {
    return new NamedThreadFactory(name(info));
  }

  public static String name(TestInfo info) {
    return info.getTestMethod().get().getName() + "-" + info.getDisplayName() + "-";
  }

  public static String randomName(TestInfo info) {
    return name(info.getTestClass().get(), info.getTestMethod().get());
  }

  private static String name(Class<?> testClass, Method testMethod) {
    String uuid = UUID.randomUUID().toString();
    return format(
        "%s_%s%s",
        testClass.getSimpleName(), testMethod.getName(), uuid.substring(uuid.length() / 2));
  }

  static Condition<String> validXml() {
    return new Condition<>(
        xml -> {
          try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return true;
          } catch (Exception e) {
            return false;
          }
        },
        "Not a valid XML document");
  }

  private static class DisabledOnSemeruCondition
      implements org.junit.jupiter.api.extension.ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      String javaRuntimeName = System.getProperty("java.runtime.name");
      return javaRuntimeName.toLowerCase(Locale.ENGLISH).contains("semeru")
          ? ConditionEvaluationResult.disabled("Test fails on Semeru")
          : ConditionEvaluationResult.enabled("OK");
    }
  }

  @Target({ElementType.TYPE, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @ExtendWith(DisabledOnSemeruCondition.class)
  @interface DisabledOnJavaSemeru {}
}
