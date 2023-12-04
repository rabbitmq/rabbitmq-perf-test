// Copyright (c) 2007-2023 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rabbitmq.client.ConnectionFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PerfTestMulti {

  private static final Logger LOGGER = LoggerFactory.getLogger(PerfTestMulti.class);

  private static final ConnectionFactory factory = new ConnectionFactory();

  private static final Map<String, Object> results = new HashMap<>();

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.out.println("Usage: PerfTestMulti input-json-file output-json-file");
      System.exit(1);
    }
    Log.configureLog();
    String inJSON = args[0];
    String outJSON = args[1];

    String json = null;
    try {
      json = readFile(inJSON);
    } catch (FileNotFoundException e) {
      System.out.println("Input json file " + inJSON + " could not be found");
      System.exit(1);
    }
    Scenario[] scenarios = scenarios(json, status -> System.exit(status));
    try {
      runStaticBrokerTests(scenarios);
      writeJSON(outJSON);
      System.exit(0);
    } catch (Exception e) {
      LOGGER.error("Error during test execution", e);
      System.exit(1);
    }
  }

  @SuppressWarnings("unchecked")
  static Scenario[] scenarios(String json, PerfTest.SystemExiter systemExiter) {
    Gson gson = new Gson();
    List<Map> scenariosJSON = gson.fromJson(json, List.class);
    if (scenariosJSON == null) {
      System.out.println("Input json file could not be parsed");
      systemExiter.exit(1);
    }
    Scenario[] scenarios = new Scenario[scenariosJSON.size()];
    for (int i = 0; i < scenariosJSON.size(); i++) {
      scenarios[i] = ScenarioFactory.fromJSON(scenariosJSON.get(i), factory);
    }
    return scenarios;
  }

  private static String readFile(String path) throws IOException {
    final char[] buf = new char[4096];
    StringBuilder out = new StringBuilder();
    Reader in = new InputStreamReader(new FileInputStream(path), "UTF-8");
    try {
      int chars;
      while ((chars = in.read(buf, 0, buf.length)) > 0) {
        out.append(buf, 0, chars);
      }
    } finally {
      in.close();
    }
    return out.toString();
  }

  private static void writeJSON(String outJSON) throws IOException {
    try (FileWriter outFile = new FileWriter(outJSON);
        PrintWriter out = new PrintWriter(outFile)) {
      out.println(toJson(results));
    }
  }

  static String toJson(Object object) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    return gson.toJson(object);
  }

  private static void runStaticBrokerTests(Scenario[] scenarios) throws Exception {
    runTests(scenarios);
  }

  private static void runTests(Scenario[] scenarios) throws Exception {
    for (Scenario scenario : scenarios) {
      System.out.print("Running scenario '" + scenario.getName() + "' ");
      scenario.run();
      System.out.println();
      results.put(scenario.getName(), scenario.getStats().results());
    }
  }
}
