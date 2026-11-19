#!/usr/bin/env bash

source ./release-versions.txt
git checkout $RELEASE_BRANCH

./mvnw release:clean release:prepare -DdryRun=true -Darguments="-DskipTests -DskipITs" --no-transfer-progress \
  --batch-mode -Dtag="v$RELEASE_VERSION" \
  -DreleaseVersion=$RELEASE_VERSION \
  -DdevelopmentVersion=$DEVELOPMENT_VERSION \

./mvnw release:clean release:prepare -Darguments="-DskipTests -DskipITs" --no-transfer-progress \
  --batch-mode -Dtag="v$RELEASE_VERSION" \
  -DreleaseVersion=$RELEASE_VERSION \
  -DdevelopmentVersion=$DEVELOPMENT_VERSION

git checkout "v$RELEASE_VERSION"
