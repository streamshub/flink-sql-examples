#!/bin/bash
# usage: setVersions.sh pomVersion imageVersion

if [[ $# != 2 ]]; then
    echo "Illegal number of parameters" >&2
    exit 1
fi
mvn -B versions:set -DgenerateBackupPoms=false -DnewVersion=${1}
sed --in-place --regexp-extended "s|flink-examples-data-generator:([^[:space:]]*)|flink-examples-data-generator:${2}|g" recommendation-app/data-generator.yaml
sed --in-place --regexp-extended "s|flink-examples-data-generator:([^[:space:]]*)|flink-examples-data-generator:${2}|g" interactive-etl/data-generator.yaml
