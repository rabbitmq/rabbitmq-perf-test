FROM ubuntu:20.04 as builder

ARG perf_test_binary="target/rabbitmq-perf-test-*-bin.tar.gz"

RUN set -eux; \
	\
	apt-get update; \
	apt-get install --yes --no-install-recommends \
		ca-certificates \
		wget \
		gnupg \
		jq

ARG JAVA_VERSION="11.0"

RUN if [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ] ; then echo "ARM"; ARCH="arm"; BUNDLE="jdk"; else echo "x86"; ARCH="x86"; BUNDLE="jdk"; fi \
    && wget "https://api.azul.com/zulu/download/community/v1.0/bundles/latest/?java_version=$JAVA_VERSION&ext=tar.gz&os=linux&arch=$ARCH&hw_bitness=64&release_status=ga&bundle_type=$BUNDLE" -O jdk-info.json
RUN wget --progress=bar:force:noscroll -O "jdk.tar.gz" $(cat jdk-info.json | jq --raw-output .url)
RUN echo "$(cat jdk-info.json | jq --raw-output .sha256_hash) *jdk.tar.gz" | sha256sum --check --strict -

RUN set -eux; \
    if [ "$(uname -m)" = "x86_64" ] ; then JAVA_PATH="/usr/lib/jdk-$JAVA_VERSION"; \
    mkdir $JAVA_PATH && \
    tar --extract  --file jdk.tar.gz --directory "$JAVA_PATH" --strip-components 1; \
    $JAVA_PATH/bin/jlink --compress=2 --output /jre --add-modules java.base,java.management,java.xml,java.naming,java.sql,jdk.crypto.cryptoki; \
	  /jre/bin/java -version; \
	  fi

RUN set -eux; \
    if [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ] ; then JAVA_PATH="/jre"; \
    mkdir $JAVA_PATH && \
    tar --extract  --file jdk.tar.gz --directory "$JAVA_PATH" --strip-components 1; \
	  fi

RUN rm jdk.tar.gz

ENV PERF_TEST_HOME="/perf_test"
ENV PERF_TEST_PATH="/usr/local/src/perf-test"

ADD $perf_test_binary /

RUN if ls rabbitmq-perf-test-*.tar.gz 1> /dev/null 2>&1; then \
    set -eux; \
    \
    mv rabbitmq-perf-test-*.tar.gz rabbitmq-perf-test.tar.gz; \
    mkdir -p "$PERF_TEST_HOME"; \
    tar --extract --file "rabbitmq-perf-test.tar.gz" --directory "$PERF_TEST_HOME" --strip-components 1; \
    else \
    mv rabbitmq-perf-test-* "$PERF_TEST_HOME"; \
    fi

FROM ubuntu:20.04

# we need locales support for characters like µ to show up correctly in the console
RUN set -eux; \
	apt-get update; \
	apt-get install -y --no-install-recommends \
		locales \
	; \
	rm -rf /var/lib/apt/lists/*; \
	locale-gen en_US.UTF-8

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

ENV JAVA_HOME=/usr/lib/jvm/java-1.11-openjdk/jre
RUN mkdir -p $JAVA_HOME
COPY --from=builder /jre $JAVA_HOME/
RUN ln -svT $JAVA_HOME/bin/java /usr/local/bin/java

RUN mkdir -p /perf_test
WORKDIR /perf_test
COPY --from=builder /perf_test ./

RUN set -eux; \
    if [ "$(uname -m)" = "x86_64" ] ; then bin/runjava com.rabbitmq.perf.PerfTest --version ; \
	  fi

RUN set -eux; \
    if [ "$(uname -m)" = "x86_64" ] ; then bin/runjava com.rabbitmq.perf.PerfTest --help ; \
	  fi

ENTRYPOINT ["bin/runjava", "com.rabbitmq.perf.PerfTest"]
