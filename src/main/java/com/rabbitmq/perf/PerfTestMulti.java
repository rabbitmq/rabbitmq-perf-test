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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rabbitmq.client.ConnectionFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
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

  public static void main(String[] args, PerfTest.PerfTestOptions perfTestOptions)
      throws Exception {
    PrintStream consoleOut = perfTestOptions.consoleOut();
    PerfTest.SystemExiter systemExiter = perfTestOptions.systemExiter();
    if (args.length != 2) {
      consoleOut.println("Usage: PerfTestMulti input-json-file output-json-file");
      systemExiter.exit(1);
    }
    String inJSON = args[0];
    String outJSON = args[1];

    String json = null;
    try {
      json = readFile(inJSON);
    } catch (FileNotFoundException e) {
      consoleOut.println("Input json file " + inJSON + " could not be found");
      systemExiter.exit(1);
    }
    Scenario[] scenarios = scenarios(json, systemExiter, consoleOut);
    try {
      runStaticBrokerTests(scenarios, consoleOut);
      writeJSON(outJSON);
      systemExiter.exit(0);
    } catch (Exception e) {
      LOGGER.error("Error during test execution", e);
      systemExiter.exit(1);
    }
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Log.configureLog();
    PerfTest.PerfTestOptions perfTestOptions = new PerfTest.PerfTestOptions();
    main(
        args,
        perfTestOptions.setSystemExiter(new PerfTest.JvmSystemExiter()).setConsoleOut(System.out));
  }

  @SuppressWarnings("unchecked")
  static Scenario[] scenarios(String json, PerfTest.SystemExiter systemExiter, PrintStream out) {
    Gson gson = new Gson();
    List<Map> scenariosJSON = gson.fromJson(json, List.class);
    if (scenariosJSON == null) {
      out.println("Input json file could not be parsed");
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

  private static void runStaticBrokerTests(Scenario[] scenarios, PrintStream out) throws Exception {
    runTests(scenarios, out);
  }

  private static void runTests(Scenario[] scenarios, PrintStream out) throws Exception {
    for (Scenario scenario : scenarios) {
      out.print("Running scenario '" + scenario.getName() + "' ");
      scenario.run();
      out.println();
      results.put(scenario.getName(), scenario.getStats().results());
    }
  }
}
