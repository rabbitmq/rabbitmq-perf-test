// Copyright (c) 2007-2023 Broadcom. All Rights Reserved.
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

import com.rabbitmq.client.ConnectionFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScenarioFactory {
  public static Scenario fromJSON(Map json, ConnectionFactory factory) {
    String uri = "amqp://localhost";
    String type = read("type", json, String.class);
    String name = read("name", json, String.class);
    Integer interval = read("interval", json, Double.class, 1000.0).intValue();
    List paramsJSON = read("params", json, List.class);

    try {
      uri = read("uri", json, String.class, uri);
      factory.setUri(uri);
    } catch (Exception e) {
      throw new RuntimeException(
          "scenario: " + name + " with malformed uri: " + uri + " - " + e.getMessage());
    }

    MulticastParams[] params = new MulticastParams[paramsJSON.size()];
    for (int i = 0; i < paramsJSON.size(); i++) {
      params[i] = paramsFromJSON((Map) paramsJSON.get(i));
    }

    if (type.equals("simple")) {
      return new SimpleScenario(name, factory, interval, params);
    } else if (type.equals("rate-vs-latency")) {
      return new RateVsLatencyScenario(name, factory, params[0]); // TODO
    } else if (type.equals("varying")) {
      List variablesJSON = read("variables", json, List.class);
      Variable[] variables = new Variable[variablesJSON.size()];
      for (int i = 0; i < variablesJSON.size(); i++) {
        variables[i] = variableFromJSON((Map) variablesJSON.get(i));
      }

      return new VaryingScenario(name, factory, params, variables);
    }

    throw new RuntimeException("Type " + type + " was not simple or varying.");
  }

  private static <T> T read(String key, Map map, Class<T> clazz) {
    if (map.containsKey(key)) {
      return read0(key, map, clazz);
    } else {
      throw new RuntimeException("Key " + key + " not found.");
    }
  }

  private static <T> T read(String key, Map map, Class<T> clazz, T def) {
    if (map.containsKey(key)) {
      return read0(key, map, clazz);
    } else {
      return def;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T read0(String key, Map map, Class<T> clazz) {
    Object o = map.get(key);
    if (clazz.isAssignableFrom(o.getClass())) {
      return (T) o;
    } else {
      throw new RuntimeException(
          "Object under key " + key + " was a " + o.getClass() + ", not a " + clazz + ".");
    }
  }

  static MulticastParams paramsFromJSON(Map json) {
    MulticastParams params = new MulticastParams();
    params.setAutoDelete(true);
    for (Object key : json.keySet()) {
      PerfUtil.setValue(params, mapJsonFieldToPropertyName((String) key), json.get(key));
    }
    return params;
  }

  private static final Map<String, String> JSON_FIELDS_TO_PROPERTY_NAMES =
      new HashMap<String, String>() {
        {
          put("body", "bodyFiles");
          put("rate", "producerRateLimit");
          put("consumer-rate", "consumerRateLimit");
        }
      };

  static String mapJsonFieldToPropertyName(String jsonField) {
    if (JSON_FIELDS_TO_PROPERTY_NAMES.containsKey(jsonField)) {
      return JSON_FIELDS_TO_PROPERTY_NAMES.get(jsonField);
    } else {
      return hyphensToCamel(jsonField);
    }
  }

  private static Variable variableFromJSON(Map json) {
    String type = read("type", json, String.class, "multicast");
    String name = read("name", json, String.class);
    Object[] values = read("values", json, List.class).toArray();

    if (type.equals("multicast")) {
      return new MulticastVariable(mapJsonFieldToPropertyName(name), values);
    }

    throw new RuntimeException("Type " + type + " was not multicast");
  }

  private static String hyphensToCamel(String name) {
    String out = "";
    for (String part : name.split("-")) {
      out += part.substring(0, 1).toUpperCase() + part.substring(1);
    }
    return out.substring(0, 1).toLowerCase() + out.substring(1);
  }
}
