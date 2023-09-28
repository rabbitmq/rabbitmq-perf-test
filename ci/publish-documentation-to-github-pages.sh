#!/usr/bin/env bash

POM_VERSION=$(cat pom.xml | grep -oPm1 '(?<=<version>)[^<]+')

# shellcheck disable=SC2102
if [[ $POM_VERSION == *[SNAPSHOT]* ]]
then
  DIRECTORY="snapshot"
elif [[ $POM_VERSION == *[RCM]* ]]; then
  DIRECTORY="milestone"
else
  DIRECTORY="release"
fi

MESSAGE=$(git log -1 --pretty=%B)

# Concourse does shallow clones, so need the next 2 commands to have the gh-pages branch
git remote set-branches origin 'gh-pages'
git fetch -v
git checkout gh-pages
mkdir -p $DIRECTORY/htmlsingle
cp target/generated-docs/index.html $DIRECTORY/htmlsingle
if [ -z "$(git status --porcelain)" ];
then
  echo "Nothing to commit"
  git checkout main
else
  git add $DIRECTORY/
  git commit -m "$MESSAGE"
  git push origin gh-pages
  git checkout main
fi