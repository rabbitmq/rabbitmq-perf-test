#!/usr/bin/env bash

source ./release-versions.txt
git checkout $RELEASE_BRANCH

./mvnw release:clean release:prepare -DdryRun=true -Darguments="-DskipTests" --no-transfer-progress \
  --batch-mode -Dtag="v$RELEASE_VERSION" \
  -DreleaseVersion=$RELEASE_VERSION \
  -DdevelopmentVersion=$DEVELOPMENT_VERSION \

./mvnw release:clean release:prepare -Darguments="-DskipTests" --no-transfer-progress \
  --batch-mode -Dtag="v$RELEASE_VERSION" \
  -DreleaseVersion=$RELEASE_VERSION \
  -DdevelopmentVersion=$DEVELOPMENT_VERSION

git checkout "v$RELEASE_VERSION"

if [[ $RELEASE_VERSION == *[RCM]* ]]
then
  MAVEN_PROFILE="milestone"
else
  MAVEN_PROFILE="release"
fi

./mvnw clean deploy -P $MAVEN_PROFILE -DskipTests --no-transfer-progress