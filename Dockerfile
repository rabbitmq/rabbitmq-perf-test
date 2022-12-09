FROM ubuntu:22.04 as builder

ARG perf_test_binary="target/perf-test.jar"

RUN set -eux; \
	\
	apt-get update; \
	apt-get -y upgrade; \
	apt-get install --yes --no-install-recommends \
		ca-certificates \
		wget \
		gnupg \
		jq

ARG JAVA_VERSION="17"

RUN if [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ] ; then echo "ARM"; ARCH="arm"; BUNDLE="jdk"; else echo "x86"; ARCH="x86"; BUNDLE="jdk"; fi \
    && wget "https://api.azul.com/zulu/download/community/v1.0/bundles/latest/?java_version=$JAVA_VERSION&ext=tar.gz&os=linux&arch=$ARCH&hw_bitness=64&release_status=ga&bundle_type=$BUNDLE" -O jdk-info.json
RUN wget --progress=bar:force:noscroll -O "jdk.tar.gz" $(cat jdk-info.json | jq --raw-output .url)
RUN echo "$(cat jdk-info.json | jq --raw-output .sha256_hash) *jdk.tar.gz" | sha256sum --check --strict -

RUN set -eux; \
    if [ "$(uname -m)" = "x86_64" ] ; then JAVA_PATH="/usr/lib/jdk-$JAVA_VERSION"; \
    mkdir $JAVA_PATH && \
    tar --extract  --file jdk.tar.gz --directory "$JAVA_PATH" --strip-components 1; \
    $JAVA_PATH/bin/jlink --compress=2 --output /jre --add-modules java.base,java.management,java.xml,java.naming,java.sql,jdk.crypto.cryptoki,jdk.httpserver; \
	  /jre/bin/java -version; \
	  fi

RUN set -eux; \
    if [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ] ; then JAVA_PATH="/jre"; \
    mkdir $JAVA_PATH && \
    tar --extract  --file jdk.tar.gz --directory "$JAVA_PATH" --strip-components 1; \
	  fi

RUN rm jdk.tar.gz

RUN wget --progress=bar:force:noscroll https://raw.githubusercontent.com/rabbitmq/rabbitmq-server/main/deps/rabbitmq_management/bin/rabbitmqadmin -O /usr/local/bin/rabbitmqadmin

ENV PERF_TEST_HOME="/perf_test"
ENV PERF_TEST_PATH="/usr/local/src/perf-test"

ADD $perf_test_binary /

RUN mkdir $PERF_TEST_HOME; \
    mv /*.jar "$PERF_TEST_HOME/perf-test.jar"

FROM ubuntu:22.04

# we need locales support for characters like µ to show up correctly in the console
RUN set -eux; \
	apt-get update; \
	apt-get -y upgrade; \
	apt-get install -y --no-install-recommends \
		locales python3 \
	; \
	rm -rf /var/lib/apt/lists/*; \
	locale-gen en_US.UTF-8

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk/jre
RUN mkdir -p $JAVA_HOME
COPY --from=builder /jre $JAVA_HOME/
RUN ln -svT $JAVA_HOME/bin/java /usr/local/bin/java

RUN mkdir -p /perf_test
WORKDIR /perf_test
COPY --from=builder /perf_test ./

RUN set -eux; \
    if [ "$(uname -m)" = "x86_64" ] ; then java -jar /perf_test/perf-test.jar --version ; \
	  fi

RUN set -eux; \
    if [ "$(uname -m)" = "x86_64" ] ; then java -jar /perf_test/perf-test.jar --help ; \
	  fi

COPY --from=builder /usr/local/bin/rabbitmqadmin /usr/local/bin/rabbitmqadmin
RUN chmod 755 /usr/local/bin/rabbitmqadmin

RUN groupadd --gid 1000 perf-test;\
    useradd --uid 1000 --gid perf-test --comment "perf-test user" perf-test; \
    chown -R 'perf-test':'perf-test' /perf_test

USER perf-test:perf-test

ENTRYPOINT ["java", "-jar", "/perf_test/perf-test.jar"]
