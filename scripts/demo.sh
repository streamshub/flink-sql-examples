#!/usr/bin/env bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NO_COLOUR='\033[0m' # No Color

EXAMPLES_DIR=${SCRIPT_DIR}/..

resolveCommand () {
  local targetCommand=${1}
  local resolvedCommand
  resolvedCommand=$(command -v "${targetCommand}")
  if [[ -z ${resolvedCommand} ]]; then
    echo -e "${RED}Unable to resolve path to ${targetCommand}${NO_COLOUR}" >&2
    exit 127
  else
    echo "${resolvedCommand}"
  fi
}

buildDataGenerator() {
  echo -e "${GREEN}Building data generator${NO_COLOUR}"
  ${MAVEN_COMMAND} -f "${EXAMPLES_DIR}/pom.xml" clean package
  ${CONTAINER_ENGINE} build -f "${EXAMPLES_DIR}/data-generator/Dockerfile" -t flink-examples-data-generator:latest data-generator
}

installPrerequisites() {
  echo -e "${GREEN}installing ${OPERATORS}${NO_COLOUR}"
  ${KUBE_COMMAND} apply -k "${EXAMPLES_DIR}/kubernetes-samples/supporting-infrastructure/overlays/${OPERATORS}_operators"
  if [[ ${OPERATORS} == "Red_Hat" ]]; then
    local AMQ_STREAMS_VERSION=""
    ${KUBE_COMMAND} wait --for=jsonpath="{.status.state}='AtLatestVersion'" subscription amq-streams -n openshift-operators >&2 > /dev/null
    AMQ_STREAMS_VERSION=$( ${KUBE_COMMAND} get subscription amq-streams -n openshift-operators -o=jsonpath='{.status.installedCSV}' )
    if ${KUBE_COMMAND} wait --for=jsonpath="{.status.phase}='Succeeded'" csv "${AMQ_STREAMS_VERSION}" >&2 > /dev/null ; then
      echo -e "${GREEN}streams for Apache Kafka ${AMQ_STREAMS_VERSION} installed${NO_COLOUR}"
    else
      echo -e "${RED}There was a problem installing streams for Apache Kafka ${NO_COLOUR}"
    fi
  else
    echo "upstream"
  fi
  ${KUBE_COMMAND} apply -k "${EXAMPLES_DIR}/kubernetes-samples/supporting-infrastructure/base/"
  if [[ ${OPERATORS} == "Red_Hat" ]]; then
    if ${KUBE_COMMAND} wait --for=condition=Ready ApicurioRegistry kafkasql-registry -n apicurio ; then
      local REGISTRY_URL=""
      REGISTRY_URL=$(oc get ApicurioRegistry kafkasql-registry -n apicurio -o=jsonpath='{.spec.deployment.host}')
      echo -e "${GREEN}Apicurio Registry is accesable as ${REGISTRY_URL} ${NO_COLOUR}"
    else
      echo -e "${RED}Apicurio Registry does not have a deployment host ${NO_COLOUR}"
    fi
  fi
}

installFlink() {
  ${KUBE_COMMAND} create namespace flink --save-config 2> /dev/null || true
  helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.9.0/
  helm upgrade --install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator --set podSecurityContext=null -n flink
}

installDemoApp() {
  ${KUBE_COMMAND} apply -k "${EXAMPLES_DIR}/kubernetes-samples/recommendations-demo/base"
}

# User customisations
CONTAINER_ENGINE=$(resolveCommand "${CONTAINER_ENGINE:-docker}")
KUBE_COMMAND=$(resolveCommand "${KUBE_COMMAND:-kubectl}")
MAVEN_COMMAND=$(resolveCommand "${MAVEN_COMMAND:-mvn}")
OPERATORS=${OPERATORS:-upstream}

pushd .
cd "${EXAMPLES_DIR}" || exit

buildDataGenerator

installPrerequisites

installFlink

installDemoApp

popd >&2 > /dev/null  || exit
