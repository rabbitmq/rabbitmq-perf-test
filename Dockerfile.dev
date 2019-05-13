FROM ubuntu:18.04 as builder

RUN set -eux ; \
    apt-get update ; \
    apt-get install --yes --no-install-recommends \
      ca-certificates \
      make \
      bash \
      unzip \
      wget

# change the JAVA_URL variable below as well
ENV JAVA_VERSION="11.0.3"
# https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.3%2B7/OpenJDK11U-jdk_x64_linux_hotspot_11.0.3_7.tar.gz.sha256.txt
ENV JAVA_SHA256="23cded2b43261016f0f246c85c8948d4a9b7f2d44988f75dad69723a7a526094"
ENV JAVA_URL="https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.3%2B7/OpenJDK11U-jdk_x64_linux_hotspot_11.0.3_7.tar.gz"

ENV JAVA_PATH="/jdk-${JAVA_VERSION}"
ENV JAVA_HOME="${JAVA_PATH}"

RUN set -eux ; \
    wget --progress dot:giga --output-document "$JAVA_PATH.tar.gz" "$JAVA_URL" ; \
    echo "$JAVA_SHA256 *$JAVA_PATH.tar.gz" | sha256sum --check --strict - ; \
    mkdir -p "$JAVA_PATH" ; \
    tar --extract --file "$JAVA_PATH.tar.gz" --directory "$JAVA_PATH" --strip-components 1 ; \
    $JAVA_PATH/bin/jlink --compress=2 --output /jre --add-modules java.base,java.management,java.xml,java.naming,java.sql ; \
    /jre/bin/java -version

RUN mkdir -p /perf_test_dev
WORKDIR /perf_test_dev
# If we COPY ., the layer will be rebuilt whenever a file has changed in the project directory
# We only care about the files used in the binary
COPY .mvn .mvn/
COPY bin bin/
COPY html html/
COPY images images/
COPY scripts scripts/
COPY src src/
COPY Makefile mvnw mvnw.cmd pom.xml ./

# Keep this as a self-contained step
# If any of the following steps fail, the Maven deps will be cached
RUN make binary

RUN set -eux ; \
    unzip target/rabbitmq-perf-test-*-SNAPSHOT-bin.zip ; \
    mv rabbitmq-perf-test-*-SNAPSHOT /perf_test ; \
    cd /perf_test ; \
    bin/runjava com.rabbitmq.perf.PerfTest --help




FROM ubuntu:18.04

ENV JAVA_HOME=/jre
COPY --from=builder /jre $JAVA_HOME/
RUN ln -svT $JAVA_HOME/bin/java /usr/local/bin/java

COPY --from=builder /perf_test /perf_test
WORKDIR /perf_test
RUN bin/runjava com.rabbitmq.perf.PerfTest --help

ENTRYPOINT ["bin/runjava", "com.rabbitmq.perf.PerfTest"]
