#!/bin/bash
# usage: setVersions.sh pomVersion imageVersion [gitTagVersion]
# if gitTagVersion is provided then a tag will be created after the changes are committed

if [[ $# -lt 2 ]]; then
    echo "Illegal number of parameters" >&2
    exit 1
fi
if [[ -z "${GITHUB_ACTOR}" ]]; then
    echo "GITHUB_ACTOR env var not set" >&2
    exit 1
fi
if [[ -z "${GITHUB_ACTOR_ID}" ]]; then
    echo "GITHUB_ACTOR_ID env var not set" >&2
    exit 1
fi
if [[ -z "${GIT_CONFIG_SCOPE}" ]]; then
  GIT_CONFIG_SCOPE="--global"
fi

mvn -B versions:set -DgenerateBackupPoms=false -DnewVersion=${1}
mvn -B clean verify
sed --in-place --regexp-extended "s|flink-examples-data-generator:([^[:space:]]*)|flink-examples-data-generator:${2}|g" recommendation-app/data-generator.yaml
sed --in-place --regexp-extended "s|flink-examples-data-generator:([^[:space:]]*)|flink-examples-data-generator:${2}|g" interactive-etl/data-generator.yaml
git config ${GIT_CONFIG_SCOPE} user.name "${GITHUB_ACTOR}"
git config ${GIT_CONFIG_SCOPE} user.email "${GITHUB_ACTOR_ID}+${GITHUB_ACTOR}@users.noreply.github.com"
git add .
git commit -m "Update version to ${1}"
if [[ -z "${3}" ]]; then
  echo "no tag supplied, doing nothing"
else
  echo "tagging commit as ${3}"
  git tag -m "${3}" -a "${3}"
fi
