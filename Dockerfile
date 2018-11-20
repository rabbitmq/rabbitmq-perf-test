FROM openjdk:8u181-jre-alpine3.8

ARG perf_test_version
ENV URL=https://github.com/rabbitmq/rabbitmq-perf-test/releases/download/v$perf_test_version/rabbitmq-perf-test-$perf_test_version-bin.tar.gz

RUN apk add --no-cache bash curl ca-certificates

# Fail fast if URL is invalid
RUN curl --verbose --head --fail $URL 1>/dev/null

# Download into /perf_test & ensure that it works correctly
RUN mkdir -p /perf_test && cd /perf_test && curl --location --silent --fail --output - $URL | tar x -z -v -f - --strip-components 1 && bin/runjava com.rabbitmq.perf.PerfTest --help
WORKDIR /perf_test

ENTRYPOINT ["bin/runjava", "com.rabbitmq.perf.PerfTest"]
