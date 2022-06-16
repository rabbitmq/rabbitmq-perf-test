SHELL := bash# we want bash behaviour in all shell invocations

.DEFAULT_GOAL = help

### VARIABLES ###
#
export PATH := $(CURDIR):$(CURDIR)/scripts:$(PATH)

OS := $$(uname -s | tr '[:upper:]' '[:lower:]')
HARDWARE := $$(uname -m | tr '[:upper:]' '[:lower:]')

GPG_KEYNAME := $$(awk -F'[<>]' '/<gpg.keyname>/ { print $$3 }' pom.xml)

TODAY := $(shell date -u +'%Y.%m.%d')

### TARGETS ###
#

.PHONY: binary
binary: clean ## Build the binary distribution
	@mvnw package -Dmaven.test.skip -Dgpg.skip=true
	@mvnw assembly:single -P assemblies -Dmaven.test.skip

.PHONY: native-image
native-image: clean ## Build the native image
	@mvnw -q package -DskipTests -P native-image -P '!java-packaging'
	native-image -jar target/perf-test.jar -H:Features="com.rabbitmq.perf.NativeImageFeature" \
	    --initialize-at-build-time=io.micrometer \
	    --initialize-at-build-time=com.rabbitmq.client \
	    --initialize-at-build-time=org.eclipse.jetty \
	    --initialize-at-build-time=javax.servlet \
	    --initialize-at-build-time=org.slf4j \
	    --no-fallback \
	    -H:IncludeResources="rabbitmq-perf-test.properties"

.PHONY: docker-image-dev
docker-image-dev: binary ## Build Docker image with the local PerfTest version
	@docker build \
	  --file Dockerfile \
	  --tag pivotalrabbitmq/perf-test:dev-$(TODAY) \
	  .

.PHONY: test-docker-image-dev
test-docker-image-dev: ## Test the Docker image with the local PerfTest version
	@docker run -it --rm pivotalrabbitmq/perf-test:dev-$(TODAY) --version

.PHONY: push-docker-image-dev
push-docker-image-dev: ## Push the Docker image with the local PerfTest version
	@docker push pivotalrabbitmq/perf-test:dev-$(TODAY)

.PHONY: docker-image
docker-image: binary ## Build Ubuntu-based Docker image
	@docker build \
	  --file Dockerfile \
	  --tag pivotalrabbitmq/perf-test:latest \
	  .

.PHONY: test-docker-image
test-docker-image: ## Test the Ubuntu-based Docker image
	@docker run -it --rm pivotalrabbitmq/perf-test:latest --version

.PHONY: push-docker-image
push-docker-image: ## Push docker image to Docker Hub
	@docker push pivotalrabbitmq/perf-test:latest

.PHONY: delete-docker-image
delete-docker-image: ## Delete the created Docker image from the local machine
	@docker rmi pivotalrabbitmq/perf-test:latest

.PHONY: package-native-image
package-native-image: native-image ## Package the native image
	cp perf-test target/perf-test_$(OS)_$(HARDWARE)
	@mvnw -q checksum:files -P native-image
	gpg --armor --local-user $(GPG_KEYNAME) --detach-sign target/perf-test_$(OS)_$(HARDWARE)

.PHONY: help
help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-24s\033[0m %s\n", $$1, $$2}'

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
	@mvnw package -Dmaven.test.skip -Dgpg.skip=false
	@mvnw assembly:single -P assemblies -Dmaven.test.skip

.PHONY: doc
doc: ## Generate PerfTest documentation
	@mvnw asciidoctor:process-asciidoc
