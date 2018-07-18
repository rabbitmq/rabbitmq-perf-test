## RabbitMQ Performance Testing Tool

[![Travis CI](https://travis-ci.org/rabbitmq/rabbitmq-jms-client.svg?branch=master)](https://travis-ci.org/rabbitmq/rabbitmq-perf-test)

This repository contains source code of the RabbitMQ Performance Testing Tool.
The client is maintained by the [RabbitMQ team at Pivotal](http://github.com/rabbitmq/).

## Installation

This is a standalone tool that is distributed in binary form using
[Bintray](https://bintray.com/rabbitmq/java-tools/perf-test) or
[GitHub releases](https://github.com/rabbitmq/rabbitmq-perf-test/releases)
and as a JAR file on Maven Central (see below).

## Documentation

 * [Latest stable release](https://rabbitmq.github.io/rabbitmq-perf-test/stable/htmlsingle/)
 * [Latest milestone release](https://rabbitmq.github.io/rabbitmq-perf-test/milestone/htmlsingle/)
 * [Latest development build](https://rabbitmq.github.io/rabbitmq-perf-test/snapshot/htmlsingle/)

## Usage

### Running Performance Tests

Assuming the current directory is the root directory of the binary distribution,
to launch a performance test with 1 producer and 1 consumer:

```
bin/runjava com.rabbitmq.perf.PerfTest
```

Use

```
bin/runjava com.rabbitmq.perf.PerfTest --help
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

To build the JAR file:

```
./mvnw clean package
```

Files are then in the `target` directory.

To build the JAR file, source and binary distributions:

```
./mvnw clean package -P assemblies -Dgpg.skip=true
```

Files are then in the `target` directory.

## Maven Artifact

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.rabbitmq/perf-test/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.rabbitmq/perf-test)

[perf-test search.maven.org](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22perf-test%22)

## Logging

`PerfTest` depends transitively on SLF4J for logging (through RabbitMQ Java Client). `PerfTest` binary distribution
ships with Logback as a SLF4J binding and uses Logback default configuration (printing logs to the console). If
for any reason you need to use a specific Logback configuration file, you can do it this way:

```
bin/runjava -Dlogback.configurationFile=/path/to/logback.xml com.rabbitmq.perf.PerfTest
```

If you use `PerfTest` as a standalone JAR in your project, please note it doesn't depend on any SLF4J binding,
you can use your favorite one.

## License

This package, the RabbitMQ Performance Testing Tool library, is triple-licensed under
the Mozilla Public License 1.1 ("MPL"), the GNU General Public License
version 2 ("GPL") and the Apache License version 2 ("ASL").
