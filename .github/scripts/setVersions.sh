#!/bin/bash
# usage: setVersions.sh pomVersion imageVersion

if [[ $# != 2 ]]; then
    echo "Illegal number of parameters" >&2
    exit 1
fi
mvn -B versions:set -DgenerateBackupPoms=false -DnewVersion=${1}
sed --in-place --regexp-extended "s|flink-examples-data-generator:([^[:space:]]*)|flink-examples-data-generator:${2}|g" tutorials/recommendation-app/data-generator.yaml
sed --in-place --regexp-extended "s|flink-sql-runner-with-flink-udf-currency-converter:([^[:space:]]*)|flink-sql-runner-with-flink-udf-currency-converter:${2}|g" tutorials/user-defined-functions/standalone-etl-udf-deployment.yaml
