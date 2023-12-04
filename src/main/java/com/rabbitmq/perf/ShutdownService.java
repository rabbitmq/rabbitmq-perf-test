// Copyright (c) 2018-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom
// Inc. and/or its subsidiaries.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to register callbacks and call them in reverse order. Registered callbacks are made
 * automatically idempotent.
 *
 * <p>This class can be used to register closing callbacks, call them individually, and/or call all
 * of them (in LIFO order) with the {@link #close()} method.
 *
 * @since 2.5.0
 */
public class ShutdownService implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownService.class);

  private final List<AutoCloseable> closeables = Collections.synchronizedList(new ArrayList<>());

  /**
   * Wrap and register the callback into an idempotent {@link AutoCloseable}.
   *
   * @param closeCallback
   * @return the callback as an idempotent {@link AutoCloseable}
   */
  AutoCloseable wrap(CloseCallback closeCallback) {
    AtomicBoolean closingOrAlreadyClosed = new AtomicBoolean(false);
    AutoCloseable idempotentCloseCallback =
        () -> {
          if (closingOrAlreadyClosed.compareAndSet(false, true)) {
            closeCallback.run();
          }
        };
    closeables.add(idempotentCloseCallback);
    return idempotentCloseCallback;
  }

  /** Close all the registered callbacks, in the reverse order of registration. */
  @Override
  public void close() {
    if (closeables.size() > 0) {
      for (int i = closeables.size() - 1; i >= 0; i--) {
        try {
          closeables.get(i).close();
        } catch (Exception e) {
          LOGGER.warn("Could not properly closed {}", closeables.get(i), e);
        }
      }
    }
  }

  @FunctionalInterface
  interface CloseCallback {

    void run() throws Exception;
  }
}
