#!/usr/bin/env bash

POM_VERSION=$(cat pom.xml | grep -oPm1 '(?<=<version>)[^<]+')

# shellcheck disable=SC2102
if [[ $POM_VERSION == *[SNAPSHOT]* ]]
then
  PAGES_BRANCH="pages-snapshot"
elif [[ $POM_VERSION == *[RCM]* ]]; then
  PAGES_BRANCH="pages-milestone"
else
  PAGES_BRANCH="pages-release"
fi

MESSAGE=$(git log -1 --pretty=%B)

# Concourse does shallow clones, so need the next 2 commands to have the gh-pages branch
git remote set-branches origin $PAGES_BRANCH
git fetch -v
git checkout $PAGES_BRANCH
cp target/generated-docs/index.html .
if [ -z "$(git status --porcelain)" ];
then
  echo "Nothing to commit"
  git checkout main
else
  git add .
  git commit -m "$MESSAGE"
  git push origin $PAGES_BRANCH
  git checkout main
fi
