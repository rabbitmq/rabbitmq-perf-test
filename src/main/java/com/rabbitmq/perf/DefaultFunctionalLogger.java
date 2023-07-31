// Copyright (c) 2023 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.AMQBasicProperties;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DefaultFunctionalLogger implements FunctionalLogger {

  private static final PropertyExtractor[] PROPERTY_EXTRACTORS =
      new PropertyExtractor[] {
        propertyExtractor("content-type", BasicProperties::getContentType),
        propertyExtractor("content-encoding", BasicProperties::getContentEncoding),
        new HeadersPropertyExtractor(),
        propertyExtractor("delivery-mode", BasicProperties::getDeliveryMode),
        propertyExtractor("priority", BasicProperties::getPriority),
        propertyExtractor("correlation-id", BasicProperties::getCorrelationId),
        propertyExtractor("reply-to", BasicProperties::getReplyTo),
        propertyExtractor("expiration", BasicProperties::getExpiration),
        propertyExtractor("message-id", BasicProperties::getMessageId),
        propertyExtractor("timestamp", BasicProperties::getTimestamp),
        propertyExtractor("type", BasicProperties::getType),
        propertyExtractor("user-id", BasicProperties::getUserId),
        propertyExtractor("app-id", BasicProperties::getAppId),
        propertyExtractor("cluster-id", BasicProperties::getClusterId)
      };

  private final PrintStream out;
  private final boolean verbose;

  public DefaultFunctionalLogger(PrintStream out, boolean verbose) {
    this.out = out;
    this.verbose = verbose;
  }

  static String details(long timestamp, BasicProperties properties, byte[] body) {
    String propertiesString;
    if (properties == null) {
      propertiesString = "properties = {}";
    } else {
      List<String> values = new ArrayList<>();
      for (PropertyExtractor extractor : PROPERTY_EXTRACTORS) {
        String label = extractor.label(properties);
        if (label != null) {
          values.add(label);
        }
      }
      if (values.isEmpty()) {
        propertiesString = "properties = {}";
      } else {
        propertiesString = "properties = {" + String.join(", ", values) + "}";
      }
    }
    String bodyString = null;
    if (body == null) {
      bodyString = "body = null";
    } else if (isStringContentType(properties)) {
      bodyString = "body = " + toUtf8String(body);
    } else {
      if (body.length >= 12) {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(body));
        try {
          int sequence = d.readInt();
          long bodyTimestamp = d.readLong();
          if (timestamp != Long.MAX_VALUE && timestamp == bodyTimestamp) {
            bodyString = format("body = [sequence = %d, timestamp = %d]", sequence, timestamp);
          }
        } catch (IOException ignored) {

        }
      }
    }
    bodyString = bodyString == null ? "body = " + toUtf8String(body) : bodyString;
    return propertiesString + ", " + bodyString;
  }

  @Override
  public void published(
      int producerId,
      long timestamp,
      long publishingId,
      BasicProperties messageProperties,
      byte[] body) {
    wrap(
        () ->
            print(
                "publisher %d: message published, timestamp = %d, publishing ID = %d%s",
                producerId,
                timestamp,
                publishingId,
                maybeDetails(timestamp, messageProperties, body)));
  }

  @Override
  public void receivedPublishConfirm(
      int producerId, boolean confirmed, long publishingId, int confirmCount) {
    wrap(
        () ->
            print(
                "publisher %d: publish confirm, type = %s, publishing ID = %d, confirm count = %d",
                producerId, confirmed ? "ack" : "nack", publishingId, confirmCount));
  }

  @Override
  public void publishConfirmed(
      int producerId, boolean confirmed, long publishingId, long timestamp) {
    wrap(
        () ->
            print(
                "publisher %d: message confirmed, type = %s, timestamp = %d, publishing ID = %d",
                producerId, confirmed ? "ack" : "nack", timestamp, publishingId));
  }

  @Override
  public void received(
      int consumerId,
      long timestamp,
      Envelope envelope,
      BasicProperties messageProperties,
      byte[] body) {
    wrap(
        () ->
            print(
                "consumer %d: received message, timestamp = %d, delivery tag = %d%s",
                consumerId,
                timestamp,
                envelope.getDeliveryTag(),
                maybeDetails(timestamp, messageProperties, body)));
  }

  @Override
  public void acknowledged(int consumerId, long timestamp, Envelope envelope, int ackedCount) {
    wrap(
        () ->
            print(
                "consumer %d: acknowledged message(s), timestamp = %d, delivery tag = %d, message count = %d",
                consumerId, timestamp, envelope.getDeliveryTag(), ackedCount));
  }

  private void print(String format, Object... args) {
    this.out.printf(format + "%n", args);
  }

  private String maybeDetails(long timestamp, BasicProperties properties, byte[] body) {
    if (this.verbose) {
      return ", " + details(timestamp, properties, body);
    } else {
      return "";
    }
  }

  private static PropertyExtractor propertyExtractor(
      String name, Function<BasicProperties, Object> f) {
    return new SimpleTypePropertyExtractor(name, f);
  }

  private interface PropertyExtractor {
    String label(BasicProperties p);
  }

  private static class SimpleTypePropertyExtractor implements PropertyExtractor {

    private final String name;
    private final Function<BasicProperties, Object> f;

    private SimpleTypePropertyExtractor(String name, Function<BasicProperties, Object> f) {
      this.name = name;
      this.f = f;
    }

    @Override
    public String label(BasicProperties p) {
      Object value = this.f.apply(p);
      if (value == null) {
        return null;
      } else {
        return this.name + " = " + value;
      }
    }
  }

  private static class HeadersPropertyExtractor implements PropertyExtractor {

    @Override
    public String label(BasicProperties p) {
      Map<String, Object> headers = p.getHeaders();
      if (headers == null) {
        return null;
      } else if (headers.isEmpty()) {
        return "headers = {}";
      } else {
        return "headers = {"
            + headers.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining(", "))
            + "}";
      }
    }
  }

  private static boolean isStringContentType(AMQBasicProperties properties) {
    if (properties == null || properties.getContentType() == null) {
      return false;
    } else {
      String contentType = properties.getContentType().toLowerCase();
      if (contentType.startsWith("text/")) {
        return true;
      } else
        return contentType.startsWith("application/javascript")
            || contentType.startsWith("application/xhtml")
            || contentType.startsWith("application/json")
            || contentType.startsWith("application/ld+json")
            || contentType.startsWith("application/xml");
    }
  }

  private static String toUtf8String(byte[] body) {
    try {
      return new String(body, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "<not UTF-8>";
    }
  }

  private void wrap(Runnable action) {
    try {
      action.run();
    } catch (Exception e) {
      print("Verbose mode error: %s", e.getMessage());
    }
  }
}
