// Copyright (c) 2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc.
// and/or its subsidiaries.
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
package com.rabbitmq.perf.it;

import com.rabbitmq.perf.MulticastSet;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.TestInfo;

final class Utils {

  private Utils() {}

  static MulticastSet.CompletionHandler latchCompletionHandler(int count, TestInfo info) {
    return new LatchCompletionHandler(new CountDownLatch(count), info);
  }

  static String queueName(Class<?> testClass, Method testMethod) {
    String uuid = UUID.randomUUID().toString();
    return String.format(
        "%s_%s%s",
        testClass.getSimpleName(), testMethod.getName(), uuid.substring(uuid.length() / 2));
  }

  static String queueName(TestInfo info) {
    return Utils.queueName(info.getTestClass().get(), info.getTestMethod().get());
  }

  static class LatchCompletionHandler implements MulticastSet.CompletionHandler {

    final CountDownLatch latch;

    final String name;

    LatchCompletionHandler(CountDownLatch latch, TestInfo info) {
      this.latch = latch;
      this.name = info.getDisplayName();
    }

    @Override
    public void waitForCompletion() {
      ConnectionRecoveryIT.LOGGER.info("Waiting completion for test [{}]", name);
      try {
        latch.await();
      } catch (InterruptedException e) {
        ConnectionRecoveryIT.LOGGER.info(
            "Completion waiting has been interrupted for test [{}]", name);
      }
    }

    @Override
    public void countDown(String reason) {
      latch.countDown();
    }
  }
}
