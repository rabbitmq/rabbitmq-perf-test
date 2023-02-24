// Copyright (c) 2018-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.impl.DefaultExceptionHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relaxed version of the {@link DefaultExceptionHandler} that logs network exceptions as info
 * instead of warn.
 *
 * <p>Connections opened by PerfTest can be blocked, especially producer connections. On shutdown,
 * they may take some time to close, so PerfTest closes the connection with a timeout. This can
 * result in noisy logs, which are not useful here, so this exception handler is a bit more relaxed
 * for those logs.
 *
 * <p>This exception handler is also less noisy with {@link AlreadyClosedException}, which can
 * typically happen on shutdown or during connection recovery.
 *
 * @since 2.5.0
 */
public class RelaxedExceptionHandler extends DefaultExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(RelaxedExceptionHandler.class);

  private static boolean isSocketClosedOrConnectionReset(Throwable e) {
    return (e instanceof IOException
            && ("Connection reset".equals(e.getMessage())
                || "Socket closed".equals(e.getMessage())
                || "Connection reset by peer".equals(e.getMessage())))
        || (e instanceof AlreadyClosedException
            && e.getMessage() != null
            && e.getMessage()
                .contains("connection is already closed due to clean connection shutdown"));
  }

  @Override
  protected void log(String message, Throwable e) {
    if (isSocketClosedOrConnectionReset(e)) {
      // we don't want to get too dramatic about those
      LOGGER.info("{} (Exception message: {})", message, e.getMessage());
    } else {
      LOGGER.error(message, e);
    }
  }
}
