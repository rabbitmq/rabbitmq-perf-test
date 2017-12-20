### CONFIG ###
#
export PATH 	:=$(CURDIR):$(CURDIR)/scripts:$(PATH)

### TARGETS ###
#
default: run

binary: clean ## Build the binary distribution
	@mvnw package -P assemblies -Dgpg.skip=true

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

clean: 	## Clean all build artefacts
	@mvnw clean

compile: ## Compile the source code
	@mvnw compile

jar: clean ## Build the JAR file
	@mvnw package

run: compile ## Run PerfTest, pass exec arguments via ARGS
	@mvnw exec:java -Dexec.mainClass="com.rabbitmq.perf.PerfTest" -Dexec.args="${ARGS}"

signed-binary: clean ## Build a GPG signed binary
	@mvnw package -P assemblies

.PHONY: help run
