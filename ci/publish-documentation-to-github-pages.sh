#!/usr/bin/env bash

MESSAGE=$(git log -1 --pretty=%B)

# Concourse does shallow clones, so need the next 2 commands to have the gh-pages branch
git remote set-branches origin 'gh-pages'
git fetch -v
git checkout gh-pages
mkdir -p snapshot/htmlsingle
cp documentation-output/index.html snapshot/htmlsingle
if [ -z "$(git status --porcelain)" ];
then
  echo "Nothing to commit"
  git checkout main
else
  git add snapshot/
  git commit -m "$MESSAGE"
  git push origin gh-pages
  git checkout main
fi