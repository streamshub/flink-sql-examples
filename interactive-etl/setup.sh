#!/usr/bin/env bash

set -u 
set -e
set -o errexit

NAMESPACE=${1:-flink}
KUBE_CMD=${KUBE_CMD:-kubectl}
TIMEOUT=${TIMEOUT:-180}
FLINK_OPERATOR_VERSION="1.10.0"

printf "\n\n\e[32mInstalling example components into namespace: %s\e[0m\n\n" ${NAMESPACE}

# Install CertManager - this is needed by the Flink Kubernetes Operator
printf "\n\e[32mChecking for CertManager install\e[0m\n"
if ${KUBE_CMD} get namespace cert-manager ; then
    printf "\e[32mCertManager is already installed\e[0m\n"
else
    ${KUBE_CMD} create -f https://github.com/jetstack/cert-manager/releases/download/v1.8.2/cert-manager.yaml
fi


# Add the Flink operator's helm repo
printf "\n\e[32mChecking for Flink Operator ${FLINK_OPERATOR_VERSION} helm repo\e[0m\n" 
if helm repo list | grep "flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}" ; then
    printf "\e[32mFlink Operator ${FLINK_OPERATOR_VERSION} helm repo already exists\e[0m\n"
else
    printf "\e[32mInstalling the Flink Operator ${FLINK_OPERATOR_VERSION} helm repo\e[0m\n"
    helm repo add --force-update flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/
fi

printf "\n\e[32mChecking for %s namespace\e[0m\n" ${NAMESPACE}
if ${KUBE_CMD} get namespace ${NAMESPACE} ; then
    printf "\n\e[32m$NAMESPACE namespace already exists\e[0m\n"
else
    ${KUBE_CMD} create namespace ${NAMESPACE}
fi

printf "\n\e[32mWaiting for cert-manager webhook to be ready...\e[0m"
${KUBE_CMD} -n cert-manager wait --for=condition=Available --timeout=${TIMEOUT}s deployment cert-manager-webhook

printf "\n\e[32mChecking for Flink Operator install\e[0m\n"
if ${KUBE_CMD} -n ${NAMESPACE} get deployment flink-kubernetes-operator ; then
    printf "\e[32mFlink Operator already installed\e[0m\n"
else
    printf "\e[32mInstalling the Flink Operator\e[0m\n"
    helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator --set podSecurityContext=null -n ${NAMESPACE}
fi

printf "\n\e[32mChecking for Strimzi Operator install\e[0m\n"
if ${KUBE_CMD} -n ${NAMESPACE} get deployment strimzi-cluster-operator ; then
    printf "\e[32mStrimzi Operator already installed\e[0m\n"
else
    printf "\e[32mInstalling the Strimzi Operator\e[0m\n"
    ${KUBE_CMD} create -f 'https://strimzi.io/install/latest?namespace=flink' -n ${NAMESPACE}
fi

printf "\n\e[32mCreating Kafka cluster\e[0m\n"
${KUBE_CMD} apply -f https://strimzi.io/examples/latest/kafka/kraft/kafka-single-node.yaml -n ${NAMESPACE}

printf "\n\e[32mWaiting for Kafka to be ready...\e[0m\n"
${KUBE_CMD} -n ${NAMESPACE} wait --for=condition=Ready --timeout=${TIMEOUT}s kafka my-cluster

printf "\n\e[32mInstalling Apicurio Registry\e[0m\n"
${KUBE_CMD} apply -f https://raw.githubusercontent.com/streamshub/flink-sql-examples/refs/heads/main/apicurio-registry.yaml -n ${NAMESPACE}

printf "\n\e[32mWaiting for Apicurio to be ready...\e[0m\n"
${KUBE_CMD} -n ${NAMESPACE} wait --for=condition=Available --timeout=${TIMEOUT}s deployment apicurio-registry

printf "\n\e[32mDeploying data generation application...\e[0m\n"
${KUBE_CMD} -n ${NAMESPACE} apply -f data-generator.yaml

printf "\n\e[32mWaiting for Flink operator to be ready...\e[0m\n"
${KUBE_CMD} -n ${NAMESPACE} wait --for=condition=Available --timeout=${TIMEOUT}s deployment flink-kubernetes-operator

printf "\n\e[32mCreating flink session cluster...\e[0m\n"
${KUBE_CMD} -n ${NAMESPACE} apply -f flink-session.yaml
