## RabbitMQ Performance Testing Tool

This repository contains source code of the [RabbitMQ Performance Testing Tool](https://www.rabbitmq.com/java-tools.html).
The client is maintained by the [RabbitMQ team at Pivotal](http://github.com/rabbitmq/).

## Using

### Running performance tests

Assuming the current directory is the root directory of the binary distribution,
to launch a performance test with 1 producer and 1 consumer:

```
bin/runjava com.rabbitmq.perf.PerfTest
```

### Using the HTML performance tools

The HTML Performance Tools are a set of tools that can help you run 
automated benchmarks by wrapping around the `PerfTest` benchmarking 
framework. You can provide benchmark specs, and the tool will take care
of running the benchmark, collecting results and displaying them in an 
HTML page. Learn more [here](html/README.md).

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for an overview of the development process.

## Building

To build the JAR file:

```
mvn clean package
```

Files are then in the `target` directory.

To build the JAR file, source and binary distributions:

```
mvn clean package -P assemblies
```

## Maven Artifact
[MVN Repository](http://mavenrepository.com/artifact/com.rabbitmq/perf-test)

## License

This package, the RabbitMQ Performance Testing Tool library, is triple-licensed under
the Mozilla Public License 1.1 ("MPL"), the GNU General Public License
version 2 ("GPL") and the Apache License version 2 ("ASL").
