#!/usr/bin/env bash

set -u 
set -e
set -o errexit

NAMESPACE=${1:-flink}

printf "\n\nInstalling example components into namespace: $NAMESPACE\n\n"

# Install CertManager - this is needed by the Flink Kubernetes Operator
printf "\nChecking for CertManager install\n"
if kubectl get namespace cert-manager ; then
    printf "CertManager is already installed\n"
else
    kubectl create -f https://github.com/jetstack/cert-manager/releases/download/v1.8.2/cert-manager.yaml
fi

# Add the Flink operator's helm repo
helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.9.0/

printf "\nChecking for $NAMESPACE namespace\n"
if kubectl get namespace $NAMESPACE ; then
    echo "$NAMESPACE namespace already exists"
else
    kubectl create namespace $NAMESPACE
fi

printf "\nWaiting for cert-manager webhook to be ready..."
kubectl -n cert-manager wait --for=condition=Available --timeout=60s deployment cert-manager-webhook

printf "\nChecking for Flink Operator install\n"
if kubectl -n $NAMESPACE get deployment flink-kubernetes-operator ; then
    printf "Flink Operator already installed\n"
else
    printf "Installing the Flink Operator\n"
    helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator --set podSecurityContext=null -n $NAMESPACE
fi

printf "\nChecking for Strimzi Operator install\n"
if kubectl -n $NAMESPACE get deployment strimzi-cluster-operator ; then
    printf "Strimzi Operator already installed\n"
else
    printf "Installing the Strimzi Operator\n"
    kubectl create -f 'https://strimzi.io/install/latest?namespace=flink' -n $NAMESPACE
fi

printf "\nCreating Kafka cluster\n"
kubectl apply -f https://strimzi.io/examples/latest/kafka/kraft/kafka-single-node.yaml -n $NAMESPACE

printf "\nWaiting for Kafka to be ready...\n"
kubectl -n $NAMESPACE wait --for=condition=Ready --timeout=120s kafka my-cluster

printf "\nInstalling Apicurio Registry\n"
kubectl apply -f https://raw.githubusercontent.com/streamshub/flink-sql-examples/refs/heads/main/apicurio-registry.yaml -n $NAMESPACE

printf "\nWaiting for Apicurio to be ready...\n"
kubectl -n $NAMESPACE wait --for=condition=Available --timeout=120s deployment apicurio-registry

printf "\nDeploying data generation application...\n"
kubectl -n $NAMESPACE apply -f data-generator.yaml 

printf "\nWaiting for Flink operator to be ready...\n"
kubectl -n $NAMESPACE wait --for=condition=Available --timeout=60s deployment flink-kubernetes-operator

printf "\nCreating flink session cluster...\n"
kubectl -n $NAMESPACE apply -f flink-session.yaml
