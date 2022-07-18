## RabbitMQ Performance Testing Tool

[![Build Status](https://github.com/rabbitmq/rabbitmq-perf-test/workflows/Build%20(Linux)/badge.svg?branch=main)](https://github.com/rabbitmq/rabbitmq-perf-test/actions?query=workflow%3A%22Build+%28Linux%29%22+branch%3Amain)

This repository contains source code of the RabbitMQ Performance Testing Tool.
The client is maintained by the [RabbitMQ team at VMware](https://github.com/rabbitmq/).

PerfTest uses the [AMQP 0.9.1 protocol](https://www.rabbitmq.com/tutorials/amqp-concepts.html) to communicate with a RabbitMQ cluster.
Use [Stream PerfTest](https://rabbitmq.github.io/rabbitmq-stream-java-client/stable/htmlsingle/#the-performance-tool) if you want to test [RabbitMQ Streams](https://rabbitmq.com/streams.html) with the [stream protocol](https://github.com/rabbitmq/rabbitmq-server/blob/master/deps/rabbitmq_stream/docs/PROTOCOL.adoc).

## Installation

This is a standalone tool that is distributed in binary form using
[GitHub releases](https://github.com/rabbitmq/rabbitmq-perf-test/releases)
and as a JAR file on Maven Central (see below). A [Docker image](https://hub.docker.com/r/pivotalrabbitmq/perf-test/) is available as well.

The [latest snapshot](https://github.com/rabbitmq/rabbitmq-java-tools-binaries-dev/releases/tag/v-rabbitmq-perf-test-latest) is also available.

## Documentation

 * [Latest stable release](https://perftest.rabbitmq.com)
 * [Latest milestone release](https://rabbitmq.github.io/rabbitmq-perf-test/milestone/htmlsingle/)
 * [Latest development build](https://perftest-dev.rabbitmq.com)

## Usage

### Running Performance Tests

Download the latest snapshot:

```shell
wget https://github.com/rabbitmq/rabbitmq-java-tools-binaries-dev/releases/download/v-rabbitmq-perf-test-latest/perf-test-latest.jar
```

Launch a performance test with 1 producer and 1 consumer:

```shell
java -jar perf-test-latest.jar
```

Use

```shell
java -jar perf-test-latest.jar --help
```

to see all supported options.


### Producing HTML Output of Runs

The HTML Performance Tools are a set of tools that can help you run 
automated benchmarks by wrapping around the `PerfTest` benchmarking 
framework. You can provide benchmark specs, and the tool will take care
of running the benchmark, collecting results and displaying them in an 
HTML page. Learn more [here](html/README.md).

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for an overview of the development process.


## Building from Source

To build the Uber JAR:

```shell
./mvnw clean package -P uber-jar -Dgpg.skip=true -Dmaven.test.skip
```

The generated file is `target/perf-test.jar`.

To build the JAR file:

```shell
./mvnw clean package -Dmaven.test.skip
```

The file is then in the `target` directory.

### Running tests

The test suite needs to execute `rabbitmqctl` to test connection recovery. You
can specify the path to `rabbitmqctl` like the following:

    ./mvnw clean verify -Drabbitmqctl.bin=/path/to/rabbitmqctl

You need a local running RabbitMQ instance.

### Running tests with Docker

Start a RabbitMQ container:

    docker run -it --rm --name rabbitmq -p 5672:5672 rabbitmq:3.10

Run the test suite:

    ./mvnw clean verify -Drabbitmqctl.bin=DOCKER:rabbitmq

Files are then in the `target` directory.

## Maven Artifact

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.rabbitmq/perf-test/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.rabbitmq/perf-test)

[perf-test search.maven.org](https://search.maven.org/#search%7Cga%7C1%7Ca%3A%22perf-test%22)

## Logging

`PerfTest` depends transitively on SLF4J for logging (through RabbitMQ Java Client). `PerfTest` binary distribution
ships with Logback as a SLF4J binding and uses Logback default configuration (printing logs to the console). If
for any reason you need to use a specific Logback configuration file, you can do it this way:

```shell
java -Dlogback.configurationFile=/path/to/logback.xml -jar perf-test.jar
```

As of PerfTest 2.11.0, it is possible to define loggers directly from the command line. This is less powerful
than using a configuration file, yet simpler to use and useful for quick debugging. Use the `rabbitmq.perftest.loggers`
system property with `name=level` pairs, e.g.:

```shell
java -Drabbitmq.perftest.loggers=com.rabbitmq.perf=debug -jar perf-test.jar
```

It is possible to define several loggers by separating them with commas, e.g.
`-Drabbitmq.perftest.loggers=com.rabbitmq.perf=debug,com.rabbitmq.perf.Producer=info`.

It is also possible to use an environment variable:

```
export RABBITMQ_PERF_TEST_LOGGERS=com.rabbitmq.perf=info
```

The system property takes precedence over the environment variable.

Use the environment variable with the Docker image:

```
docker run -it --rm --network perf-test \
  --env RABBITMQ_PERF_TEST_LOGGERS=com.rabbitmq.perf=debug,com.rabbitmq.perf.Producer=debug \
  pivotalrabbitmq/perf-test:latest --uri amqp://rabbitmq
```

If you use `PerfTest` as a standalone JAR in your project, please note it doesn't depend on any SLF4J binding,
you can use your favorite one.

## Versioning

This tool uses [semantic versioning](https://semver.org/).

## Support

See the [RabbitMQ Java libraries support page](https://www.rabbitmq.com/java-versions.html)
for the support timeline of this library.

## License

This package, the RabbitMQ Performance Testing Tool library, is triple-licensed under
the Mozilla Public License 2.0 ("MPL"), the GNU General Public License
version 2 ("GPL") and the Apache License version 2 ("ASL").
