FROM openjdk:8u181-jre-alpine3.8

ARG perf_test_distribution
RUN apk add --no-cache bash
VOLUME /tmp
COPY $perf_test_distribution/ /tmp/
ENTRYPOINT ["/tmp/bin/runjava", "com.rabbitmq.perf.PerfTest"]