// Copyright (c) 2025 Broadcom. All Rights Reserved.
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
package com.rabbitmq.perf.it;

import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.perf.PerfTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class PerfTestIT {

  static ExecutorService executor = Executors.newSingleThreadExecutor();
  volatile ByteArrayOutputStream out, err;
  volatile RecordingSystemExiter systemExiter;

  @BeforeAll
  static void initAll() {
    executor = Executors.newSingleThreadExecutor();
  }

  @BeforeEach
  void init() {
    out = new ByteArrayOutputStream();
    err = new ByteArrayOutputStream();
    systemExiter = new RecordingSystemExiter();
  }

  @AfterAll
  static void tearDown() {
    executor.shutdownNow();
  }

  @Test
  void runWithMessageCountLimitsShouldStop() throws Exception {
    int messageCount = 1000;
    run(builder().pmessages(messageCount).cmessages(messageCount));
    waitRunEnds();
    assertThat(consoleOutput()).contains("starting", "stopped", "avg");
  }

  @Test
  void netty() throws Exception {
    int messageCount = 1000;
    run(builder().pmessages(messageCount).cmessages(messageCount).nettyThreads(1));
    waitRunEnds();
    assertThat(consoleOutput()).contains("starting", "stopped", "avg");
  }

  @ParameterizedTest
  @EnumSource
  @Utils.DisabledIfTlsNotEnabled
  void tlsShouldConnect(IoLayer ioLayer) throws Exception {
    int messageCount = 1000;
    run(
        builder(ioLayer)
            .pmessages(messageCount)
            .cmessages(messageCount)
            .uri("amqps://localhost")
            .sni("localhost"));
    waitRunEnds();
    assertThat(consoleOutput()).contains("starting", "stopped", "avg");
  }

  @ParameterizedTest
  @EnumSource
  @Utils.DisabledIfTlsNotEnabled
  void tlsWithDefaultSslContextShouldFail(IoLayer ioLayer) throws Exception {
    int messageCount = 1000;
    run(
        builder(ioLayer)
            .pmessages(messageCount)
            .cmessages(messageCount)
            .uri("amqps://localhost")
            .useDefaultSslContext());
    waitRunEnds(1);
    assertThat(consoleOutput()).contains("test stopped");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  @EnabledIfSystemProperty(named = "os.arch", matches = "amd64")
  void nativeEpollWorksOnLinux() throws Exception {
    int messageCount = 1000;
    run(builder().pmessages(messageCount).cmessages(messageCount).nettyThreads(1).nettyEpoll());
    waitRunEnds();
    assertThat(consoleOutput()).contains("starting", "stopped", "avg");
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @EnabledIfSystemProperty(named = "os.arch", matches = "aarch64")
  void nativeKqueueWorksOnMacOs() throws Exception {
    int messageCount = 1000;
    run(builder().pmessages(messageCount).cmessages(messageCount).nettyThreads(1).nettyKqueue());
    waitRunEnds();
    assertThat(consoleOutput()).contains("starting", "stopped", "avg");
  }

  private static void waitOneSecond() throws InterruptedException {
    wait(Duration.ofSeconds(1));
  }

  private static void wait(Duration duration) throws InterruptedException {
    Thread.sleep(duration.toMillis());
  }

  Future<?> run(ArgumentsBuilder builder) {
    String[] args = builder.build().split(" ");
    return executor.submit(
        () -> {
          PerfTest.PerfTestOptions options = new PerfTest.PerfTestOptions();
          options.setConsoleOut(new PrintStream(out));
          options.setConsoleErr(new PrintStream(err));
          options.setSystemExiter(systemExiter);
          PerfTest.main(args, options);
        });
  }

  ArgumentsBuilder builder(IoLayer ioLayer) {
    ArgumentsBuilder builder = builder();
    if (ioLayer == IoLayer.NETTY) {
      builder.nettyThreads(1);
    }
    return builder;
  }

  ArgumentsBuilder builder() {
    return new ArgumentsBuilder();
  }

  static class ArgumentsBuilder {

    private final Map<String, String> arguments = new HashMap<>();

    ArgumentsBuilder pmessages(int pmessages) {
      return intArgument("pmessages", pmessages);
    }

    ArgumentsBuilder cmessages(int cmessages) {
      return intArgument("cmessages", cmessages);
    }

    ArgumentsBuilder nettyThreads(int threads) {
      return intArgument("netty-threads", threads);
    }

    ArgumentsBuilder uri(String uri) {
      return this.argument("uri", uri);
    }

    ArgumentsBuilder sni(String sni) {
      return argument("server-name-indication", sni);
    }

    ArgumentsBuilder useDefaultSslContext() {
      return argument("use-default-ssl-context", "true");
    }

    ArgumentsBuilder nettyEpoll() {
      return argument("netty-epoll", "true");
    }

    ArgumentsBuilder nettyKqueue() {
      return argument("netty-kqueue", "true");
    }

    ArgumentsBuilder rate(int rate) {
      return intArgument("rate", rate);
    }

    private ArgumentsBuilder argument(String key, String value) {
      this.arguments.put(key, value);
      return this;
    }

    private ArgumentsBuilder intArgument(String key, int value) {
      this.arguments.put(key, String.valueOf(value));
      return this;
    }

    String build() {
      return this.arguments.entrySet().stream()
          .map(e -> "--" + e.getKey() + (e.getValue().isEmpty() ? "" : (" " + e.getValue())))
          .collect(Collectors.joining(" "));
    }
  }

  private void waitRunEnds() throws Exception {
    waitRunEnds(0);
  }

  private void waitRunEnds(int expectedExitCode) throws Exception {
    waitAtMost(
        (int) Duration.ofSeconds(30).getSeconds(),
        () -> systemExiter.status() == expectedExitCode,
        () -> "Expected " + expectedExitCode + " exit code, got " + systemExiter.status());
  }

  private String consoleOutput() {
    return out.toString();
  }

  private String consoleErrorOutput() {
    return err.toString();
  }

  private static class RecordingSystemExiter implements PerfTest.SystemExiter {

    private AtomicInteger exitCount = new AtomicInteger(0);
    private AtomicInteger lastStatus = new AtomicInteger(-1);

    @Override
    public void exit(int status) {
      exitCount.incrementAndGet();
      lastStatus.set(status);
    }

    boolean notCalled() {
      return exitCount.get() == 0;
    }

    int status() {
      return lastStatus.get();
    }
  }

  private enum IoLayer {
    NETTY,
    SOCKET
  }
}
