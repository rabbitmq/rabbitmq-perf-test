### CONFIG ###
#
export PATH 	:=$(CURDIR)/scripts:$(PATH)

### TARGETS ###
#
default: run

binary: clean ## Build the binary distribution
	@mvn package -P assemblies -Dgpg.skip=true

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

clean: 	## Clean all build artefacts
	@mvn clean

compile: ## Compile the source code
	@mvn compile

jar: clean ## Build the JAR file
	@mvn package

run: compile ## Run PerfTest, pass exec arguments via ARGS
	@mvn exec:java -Dexec.mainClass="com.rabbitmq.perf.PerfTest" -Dexec.args="${ARGS}"

signed-binary: clean ## Build a GPG signed binary
	@mvn package -P assemblies

.PHONY: help run
