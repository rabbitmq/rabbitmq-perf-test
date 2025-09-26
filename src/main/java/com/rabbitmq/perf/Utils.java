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

import static com.rabbitmq.client.ConnectionFactory.computeDefaultTlsProtocol;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.RecoveryDelayHandler;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.SocketChannelConfigurator;
import com.rabbitmq.client.SocketConfigurator;
import com.rabbitmq.client.SocketConfigurators;
import com.rabbitmq.client.SslEngineConfigurator;
import com.rabbitmq.client.SslEngineConfigurators;
import com.rabbitmq.client.impl.OAuth2ClientCredentialsGrantCredentialsProvider;
import com.rabbitmq.client.impl.OAuthTokenManagementException;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
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
        public Object get() {
          return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
          return null;
        }
      };
  private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);

  static boolean isRecoverable(Connection connection) {
    return connection instanceof AutorecoveringConnection;
  }

  static Address extract(String uri)
      throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException {
    ConnectionFactory cf = new ConnectionFactory();
    cf.setUri(uri);
    return new Address(cf.getHost(), cf.getPort());
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
          .map(String::trim)
          .map(SNIHostName::new)
          .collect(Collectors.toList());
    } else {
      return Collections.emptyList();
    }
  }

  static SocketConfigurator socketConfigurator(CommandLineProxy cmd) {
    SocketConfigurator socketConfigurator = SocketConfigurators.defaultConfigurator();
    List<SNIServerName> serverNames = sniServerNames(strArg(cmd, "sni", null));
    if (!serverNames.isEmpty()) {
      socketConfigurator =
          socketConfigurator.andThen(
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
              });
    }
    TcpSettings settings = new TcpSettings(cmd);
    socketConfigurator =
        socketConfigurator.andThen(
            socket -> {
              if (settings.sendBufferSize() > 0) {
                socket.setSendBufferSize(settings.sendBufferSize());
              }
              if (settings.receiveBufferSize() > 0) {
                socket.setReceiveBufferSize(settings.receiveBufferSize());
              }
              socket.setTcpNoDelay(settings.tcpNoDelay());
            });
    return socketConfigurator;
  }

  static Consumer<io.netty.channel.Channel> channelCustomizer(CommandLineProxy cmd) {
    TcpSettings settings = new TcpSettings(cmd);
    List<SNIServerName> servers = sniServerNames(strArg(cmd, "sni", null));
    return ch -> {
      if (settings.sendBufferSize() > 0) {
        ch.setOption(ChannelOption.SO_SNDBUF, settings.sendBufferSize());
      }
      if (settings.receiveBufferSize() > 0) {
        ch.setOption(ChannelOption.SO_RCVBUF, settings.receiveBufferSize());
      }
      ch.setOption(ChannelOption.TCP_NODELAY, settings.tcpNoDelay());
      if (!servers.isEmpty()) {
        SslHandler sslHandler = ch.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
          LOGGER.warn("SNI TLS parameter set but there is no TLS Netty handler");
        } else {
          SSLParameters sslParameters = sslHandler.engine().getSSLParameters();
          sslParameters.setServerNames(servers);
          sslHandler.engine().setSSLParameters(sslParameters);
        }
      }
    };
  }

  static SslContext nettySslContext(SSLContext defaultSslContext)
      throws SSLException, NoSuchAlgorithmException {
    if (defaultSslContext == null) {
      return SslContextBuilder.forClient()
          .protocols(
              computeDefaultTlsProtocol(
                  SSLContext.getDefault().getSupportedSSLParameters().getProtocols()))
          .trustManager(new TrustAllTrustManager())
          .build();
    } else {
      return new JdkSslContext(
          defaultSslContext,
          true,
          null,
          IdentityCipherSuiteFilter.INSTANCE,
          null,
          ClientAuth.NONE,
          null,
          false);
    }
  }

  @SuppressWarnings("deprecation")
  static SocketChannelConfigurator socketChannelConfigurator(CommandLineProxy cmd) {
    TcpSettings settings = new TcpSettings(cmd);
    return socketChannel -> {
      if (settings.sendBufferSize() > 0) {
        socketChannel.socket().setSendBufferSize(settings.sendBufferSize());
      }
      if (settings.receiveBufferSize() > 0) {
        socketChannel.socket().setReceiveBufferSize(settings.receiveBufferSize());
      }
      socketChannel.socket().setTcpNoDelay(settings.tcpNoDelay());
    };
  }

  @SuppressWarnings("deprecation")
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

  static int intArg(CommandLineProxy cmd, String opt, int def) {
    return Integer.parseInt(cmd.getOptionValue(opt, Integer.toString(def)));
  }

  static boolean boolArg(CommandLineProxy cmd, String opt, String def) {
    return Boolean.parseBoolean(cmd.getOptionValue(opt, def));
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
    } catch (IOException e) {
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

  @SuppressWarnings("unchecked")
  static InstanceSynchronization defaultInstanceSynchronization(
      String id, int expectedInstances, String namespace, Duration timeout, PrintStream out) {
    try {
      Class<InstanceSynchronization> defaultClass =
          (Class<InstanceSynchronization>)
              Class.forName("com.rabbitmq.perf.DefaultInstanceSynchronization");
      Constructor<InstanceSynchronization> constructor =
          defaultClass.getDeclaredConstructor(
              String.class, int.class, String.class, Duration.class, PrintStream.class);
      return constructor.newInstance(id, expectedInstances, namespace, timeout, out);
    } catch (ClassNotFoundException e) {
      return () -> {
        if (expectedInstances > 1) {
          throw new IllegalArgumentException("Multi-instance synchronization is not available");
        }
      };
    } catch (NoSuchMethodException
        | InvocationTargetException
        | InstantiationException
        | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  static class GsonOAuth2ClientCredentialsGrantCredentialsProvider
      extends OAuth2ClientCredentialsGrantCredentialsProvider {

    private final Gson gson = new Gson();

    public GsonOAuth2ClientCredentialsGrantCredentialsProvider(
        String tokenEndpointUri,
        String clientId,
        String clientSecret,
        String grantType,
        Map<String, String> parameters,
        HostnameVerifier hostnameVerifier,
        SSLSocketFactory sslSocketFactory) {
      super(
          tokenEndpointUri,
          clientId,
          clientSecret,
          grantType,
          parameters,
          hostnameVerifier,
          sslSocketFactory);
    }

    @Override
    protected Token parseToken(String response) {
      try {
        Map<?, ?> map = gson.fromJson(response, Map.class);
        int expiresIn = ((Number) map.get("expires_in")).intValue();
        Instant receivedAt = Instant.now();
        return new Token(map.get("access_token").toString(), expiresIn, receivedAt);
      } catch (Exception e) {
        throw new OAuthTokenManagementException("Error while parsing OAuth 2 token", e);
      }
    }
  }

  private static class TcpSettings {

    private final int sendBufferSize;
    private final int receiveBufferSize;
    private final boolean tcpNoDelay;

    private TcpSettings(CommandLineProxy cmd) {
      this.sendBufferSize = intArg(cmd, "tsbs", 65_536);
      this.receiveBufferSize = intArg(cmd, "trbs", 65_536);
      this.tcpNoDelay = boolArg(cmd, "tnd", "true");
    }

    private int sendBufferSize() {
      return this.sendBufferSize;
    }

    private int receiveBufferSize() {
      return this.receiveBufferSize;
    }

    private boolean tcpNoDelay() {
      return this.tcpNoDelay;
    }
  }

  static class TrustAllTrustManager implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
