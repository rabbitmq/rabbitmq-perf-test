#!/usr/bin/env bash

# shellcheck disable=SC2102
if [[ $POM_VERSION == *[SNAPSHOT]* ]]
then
  CURRENT_DATE=$(date --utc '+%Y%m%d-%H%M%S')
  RELEASE_VERSION="$(cat pom.xml | grep -oPm1 '(?<=<version>)[^<]+')-$CURRENT_DATE"
  FINAL_NAME="rabbitmq-perf-test-$RELEASE_VERSION"
  SNAPSHOT="true"
else
  source ./release-versions.txt
  FINAL_NAME="rabbitmq-perf-test-$RELEASE_VERSION"
fi

./mvnw package assembly:single checksum:files gpg:sign -P assemblies -DfinalName=$FINAL_NAME -DskipTests --no-transfer-progress

mkdir packages
mkdir packages-latest

cp target/$FINAL_NAME-*.* packages
cp target/$FINAL_NAME-*.* packages-latest

UBER_JAR_FINAL_NAME="perf-test-$RELEASE_VERSION"
./mvnw clean package -Dmaven.test.skip -P uber-jar -DuberJarFinalName=$UBER_JAR_FINAL_NAME --no-transfer-progress

rm -f target/*.original

cp target/$UBER_JAR_FINAL_NAME.jar* packages
cp target/$UBER_JAR_FINAL_NAME.jar* packages-latest

for filename in packages-latest/*; do
    [ -f "$filename" ] || continue
    filename_without_version=$(echo "$filename" | sed -e "s/$RELEASE_VERSION/latest/g")
    mv $filename $filename_without_version
done

if [[ $SNAPSHOT = "true" ]]
then
  echo "release_name=$RELEASE_VERSION" >> $GITHUB_ENV
  echo "release_version=$RELEASE_VERSION" >> $GITHUB_ENV
  echo "tag_name=v-rabbitmq-perf-test-$RELEASE_VERSION" >> $GITHUB_ENV
else
  echo "release_name=$RELEASE_VERSION" >> $GITHUB_ENV
  echo "release_version=$RELEASE_VERSION" >> $GITHUB_ENV
  echo "tag_name=v$RELEASE_VERSION" >> $GITHUB_ENV
  echo "release_branch=$RELEASE_BRANCH" >> $GITHUB_ENV
fi