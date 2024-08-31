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

import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Current version of the tool. Tries to get version information from a specific property file and
 * falls back to manifest information if the file cannot be loaded.
 */
public class Version {

  public static final String VERSION, BUILD, BUILD_TIMESTAMP;

  private static final Logger LOGGER = LoggerFactory.getLogger(Version.class);

  static {
    VERSION = getVersion();
    BUILD = getBuild();
    BUILD_TIMESTAMP = getBuildTimestamp();
  }

  private static String getVersion() {
    String version;
    try {
      version = getValueFromPropertyFile("com.rabbitmq.perf.version");
    } catch (Exception e1) {
      LOGGER.warn("Version unavailable from property file", e1);
      try {
        version = getVersionFromPackage();
      } catch (Exception e2) {
        LOGGER.warn("Couldn't get version with Package#getImplementationVersion", e1);
        version = getDefaultVersion();
      }
    }
    return version;
  }

  private static String getBuild() {
    String build;
    try {
      build = getValueFromPropertyFile("com.rabbitmq.perf.build");
    } catch (Exception e) {
      LOGGER.warn("Couldn't get build from property file", e);
      build = getDefaultBuild();
    }
    return build;
  }

  private static String getBuildTimestamp() {
    String build;
    try {
      build = getValueFromPropertyFile("com.rabbitmq.perf.build.timestamp");
    } catch (Exception e) {
      LOGGER.warn("Couldn't get build timestamp from property file", e);
      build = getDefaultBuildTimestamp();
    }
    return build;
  }

  private static String getValueFromPropertyFile(String key) throws Exception {
    InputStream inputStream =
        Version.class.getClassLoader().getResourceAsStream("rabbitmq-perf-test.properties");
    Properties version = new Properties();
    try {
      version.load(inputStream);
    } finally {
      if (inputStream != null) {
        inputStream.close();
      }
    }
    if (version.getProperty(key) == null) {
      throw new IllegalStateException("Coulnd't find " + key + " property in property file");
    }
    return version.getProperty(key);
  }

  private static String getVersionFromPackage() {
    if (Version.class.getPackage().getImplementationVersion() == null) {
      throw new IllegalStateException("Couldn't get version with Package#getImplementationVersion");
    }
    return Version.class.getPackage().getImplementationVersion();
  }

  private static String getDefaultVersion() {
    return "0.0.0";
  }

  private static String getDefaultBuild() {
    return "unknown";
  }

  private static String getDefaultBuildTimestamp() {
    return "unknown";
  }
}
