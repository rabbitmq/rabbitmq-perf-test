SHELL := bash# we want bash behaviour in all shell invocations

.DEFAULT_GOAL = help

### VARIABLES ###
#
export PATH := $(CURDIR):$(CURDIR)/scripts:$(PATH)

OS := $$(uname -s | tr '[:upper:]' '[:lower:]')
HARDWARE := $$(uname -m | tr '[:upper:]' '[:lower:]')

GPG_KEYNAME := $$(awk -F'[<>]' '/<gpg.keyname>/ { print $$3 }' pom.xml)

### TARGETS ###
#

.PHONY: binary
binary: clean ## Build the binary distribution
	@mvnw package -P assemblies -Dgpg.skip=true -Dmaven.test.skip

.PHONY: native-image
native-image: clean ## Build the native image
	@mvnw -q package -DskipTests -P native-image -P '!java-packaging'
	native-image -jar target/perf-test.jar -H:Features="com.rabbitmq.perf.NativeImageFeature"

docker-image:
	rm -rf rabbitmq-perf-test-$(RELEASE_VERSION)*
	wget https://github.com/rabbitmq/rabbitmq-perf-test/releases/download/v$(RELEASE_VERSION)/rabbitmq-perf-test-$(RELEASE_VERSION)-bin.tar.gz
	tar -xf rabbitmq-perf-test-$(RELEASE_VERSION)-bin.tar.gz
	docker build --build-arg perf_test_distribution=rabbitmq-perf-test-$(RELEASE_VERSION) -t pivotalrabbitmq/perf-test:$(RELEASE_VERSION) -t pivotalrabbitmq/perf-test:latest .
	rm -rf rabbitmq-perf-test-$(RELEASE_VERSION)*

.PHONY: package-native-image
package-native-image: native-image ## Package the native image
	cp perf-test target/perf-test_$(OS)_$(HARDWARE)
	@mvnw -q checksum:files -P native-image
	gpg --armor --local-user $(GPG_KEYNAME) --detach-sign target/perf-test_$(OS)_$(HARDWARE)

.PHONY: help
help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

.PHONY: clean
clean: 	## Clean all build artefacts
	@mvnw clean

.PHONY: compile
compile: ## Compile the source code
	@mvnw compile

.PHONY: install
install: clean ## Create and copy the binaries into the local Maven repository
	@mvnw install -Dmaven.test.skip

.PHONY: jar
jar: clean ## Build the JAR file
	@mvnw package -Dmaven.test.skip

.PHONY: run
run: compile ## Run PerfTest, pass exec arguments via ARGS, e.g. ARGS="-x 1 -y 1 -r 1"
	@mvnw exec:java -Dexec.mainClass="com.rabbitmq.perf.PerfTest" -Dexec.args="$(ARGS)"

.PHONY: signed-binary
signed-binary: clean ## Build a GPG signed binary
	@mvnw package -P assemblies

.PHONY: doc
doc: ## Generate PerfTest documentation
	@mvnw asciidoctor:process-asciidoc
