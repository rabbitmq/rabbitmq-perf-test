// Copyright (c) 2018-2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.RecoveryDelayHandler;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.SocketConfigurator;
import com.rabbitmq.client.SocketConfigurators;
import com.rabbitmq.client.SslEngineConfigurator;
import com.rabbitmq.client.SslEngineConfigurators;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class Utils {

  static final Future<?> NO_OP_FUTURE =
      new Future<Object>() {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
          return true;
        }

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public boolean isDone() {
          return true;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
          return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
          return null;
        }
      };
  private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
  private static final ConnectionFactory CF = new ConnectionFactory();

  static boolean isRecoverable(Connection connection) {
    return connection instanceof AutorecoveringConnection;
  }

  static synchronized Address extract(String uri)
      throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
    CF.setUri(uri);
    return new Address(CF.getHost(), CF.getPort());
  }

  /**
   * @param argument
   * @return
   * @since 2.11.0
   */
  static RecoveryDelayHandler getRecoveryDelayHandler(String argument) {
    if (argument == null || argument.trim().isEmpty()) {
      return null;
    }
    argument = argument.trim();
    Pattern pattern = Pattern.compile("(\\d+)(-(\\d+))?");
    Matcher matcher = pattern.matcher(argument);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Incorrect argument for connection recovery interval. Must be e.g. 30 or 30-60.");
    }

    RecoveryDelayHandler handler;
    final long delay = Long.parseLong(matcher.group(1)) * 1000;
    if (matcher.group(2) == null) {
      handler = recoveryAttempts -> delay;
    } else {
      final long maxInput = Long.parseLong(matcher.group(2).replace("-", "")) * 1000;
      if (maxInput <= delay) {
        throw new IllegalArgumentException("Wrong interval min-max values: " + argument);
      }
      final long maxDelay = maxInput + 1000;
      handler = recoveryAttempts -> ThreadLocalRandom.current().nextLong(delay, maxDelay);
    }
    return handler;
  }

  static List<SNIServerName> sniServerNames(String argumentValue) {
    if (argumentValue != null && !argumentValue.trim().isEmpty()) {
      return Arrays.stream(argumentValue.split(","))
          .map(s -> s.trim())
          .map(s -> new SNIHostName(s))
          .collect(Collectors.toList());
    } else {
      return Collections.emptyList();
    }
  }

  static SocketConfigurator socketConfigurator(CommandLineProxy cmd) {
    List<SNIServerName> serverNames = sniServerNames(strArg(cmd, "sni", null));
    if (serverNames.isEmpty()) {
      return SocketConfigurators.defaultConfigurator();
    } else {
      SocketConfigurator socketConfigurator =
          socket -> {
            if (socket instanceof SSLSocket) {
              SSLSocket sslSocket = (SSLSocket) socket;
              SSLParameters sslParameters =
                  sslSocket.getSSLParameters() == null
                      ? new SSLParameters()
                      : sslSocket.getSSLParameters();
              sslParameters.setServerNames(serverNames);
              sslSocket.setSSLParameters(sslParameters);
            } else {
              LOGGER.warn("SNI parameter set on a non-TLS connection");
            }
          };
      return SocketConfigurators.defaultConfigurator().andThen(socketConfigurator);
    }
  }

  static SslEngineConfigurator sslEngineConfigurator(CommandLineProxy cmd) {
    List<SNIServerName> serverNames = sniServerNames(strArg(cmd, "sni", null));
    if (serverNames.isEmpty()) {
      return SslEngineConfigurators.DEFAULT;
    } else {
      SslEngineConfigurator sslEngineConfigurator =
          sslEngine -> {
            SSLParameters sslParameters =
                sslEngine.getSSLParameters() == null
                    ? new SSLParameters()
                    : sslEngine.getSSLParameters();
            sslParameters.setServerNames(serverNames);
            sslEngine.setSSLParameters(sslParameters);
          };
      return SslEngineConfigurators.defaultConfigurator().andThen(sslEngineConfigurator);
    }
  }

  static String strArg(CommandLineProxy cmd, String opt, String def) {
    return cmd.getOptionValue(opt, def);
  }

  static String strArg(CommandLineProxy cmd, char opt, String def) {
      return cmd.getOptionValue(opt, def);
  }

  static void exchangeDeclare(Channel channel, String exchange, String type) throws IOException {
    if ("".equals(exchange) || exchange.startsWith("amq.")) {
      LOGGER.info("Skipping creation of exchange {}", exchange);
    }
    channel.exchangeDeclare(exchange, type, true);
  }

  interface Checker {
    void check(Channel ch) throws IOException;
  }

  static boolean exists(Connection connection, Checker checker) throws IOException {
    try {
      Channel ch = connection.createChannel();
      checker.check(ch);
      ch.abort();
      return true;
    }
    catch (IOException e) {
      ShutdownSignalException sse = (ShutdownSignalException) e.getCause();
      if (!sse.isHardError()) {
        AMQP.Channel.Close closeMethod = (AMQP.Channel.Close) sse.getReason();
        if (closeMethod.getReplyCode() == AMQP.NOT_FOUND) {
          return false;
        }
      }
      throw e;
    }
  }
}
