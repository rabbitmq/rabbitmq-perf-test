#!/usr/bin/env bash

VERSION="$(grep -oPm1 '(?<=<version>)[^<]+' pom.xml)"
TAG="$(grep -oPm1 '(?<=<tag>)[^<]+' pom.xml)"
ASSETS_URL="$(curl -s https://api.github.com/repos/rabbitmq/rabbitmq-perf-test/releases/tags/$TAG | python release/get_assets_upload_url_from_json.py)"

# URL ends with /assets{?name,label}, removing {?name,label}
ASSETS_URL=${ASSETS_URL%{*}

curl -X POST -u "$1:$2" \
     --header 'Content-Type: application/gzip' \
     --data-binary @target/rabbitmq-perf-test-$VERSION-bin.tar.gz \
     $ASSETS_URL?name=rabbitmq-perf-test-$VERSION-bin.tar.gz

curl -X POST -u "$1:$2" \
     --header 'Content-Type: application/zip' \
     --data-binary @target/rabbitmq-perf-test-$VERSION-bin.zip \
     $ASSETS_URL?name=rabbitmq-perf-test-$VERSION-bin.zip

curl -X POST -u "$1:$2" \
     --header 'Content-Type: application/gzip' \
     --data-binary @target/rabbitmq-perf-test-$VERSION-src.tar.gz \
     $ASSETS_URL?name=rabbitmq-perf-test-$VERSION-src.tar.gz

curl -X POST -u "$1:$2" \
     --header 'Content-Type: application/zip' \
     --data-binary @target/rabbitmq-perf-test-$VERSION-src.zip \
     $ASSETS_URL?name=rabbitmq-perf-test-$VERSION-src.zip

