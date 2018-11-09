.ONESHELL:# single shell invocation for all lines in the recipe

.DEFAULT_GOAL = help

### VARIABLES ###
#
export PATH 	:=$(CURDIR):$(CURDIR)/scripts:$(PATH)
OS              := $(shell uname -s | tr '[:upper:]' '[:lower:]')
HARDWARE        := $(shell uname -m | tr '[:upper:]' '[:lower:]')
GPG_KEYNAME     := $(shell cat pom.xml | grep -oPm1 "(?<=<gpg.keyname>)[^<]+")

### TARGETS ###
#

binary: clean ## Build the binary distribution
	@mvnw package -P assemblies -Dgpg.skip=true -Dmaven.test.skip

native-image: clean ## Build the native image
	@mvnw -q package -DskipTests -P native-image -P '!java-packaging'
	native-image -jar target/perf-test.jar -H:Features="com.rabbitmq.perf.NativeImageFeature"


package-native-image: native-image ## Package the native image
	cp perf-test target/perf-test_$(OS)_$(HARDWARE)
	@mvnw -q checksum:files -P native-image
	gpg --armor --local-user $(GPG_KEYNAME) --detach-sign target/perf-test_$(OS)_$(HARDWARE)

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

clean: 	## Clean all build artefacts
	@mvnw clean

compile: ## Compile the source code
	@mvnw compile

install: clean ## Create and copy the binaries into the local Maven repository
	@mvnw install -Dmaven.test.skip

jar: clean ## Build the JAR file
	@mvnw package -Dmaven.test.skip

run: compile ## Run PerfTest, pass exec arguments via ARGS, e.g. ARGS="-x 1 -y 1 -r 1"
	@mvnw exec:java -Dexec.mainClass="com.rabbitmq.perf.PerfTest" -Dexec.args="$(ARGS)"

signed-binary: clean ## Build a GPG signed binary
	@mvnw package -P assemblies

doc: ## Generate PerfTest documentation
	@mvnw asciidoctor:process-asciidoc

.PHONY: binary help clean compile jar run signed-binary
