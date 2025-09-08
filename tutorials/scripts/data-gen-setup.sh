#!/usr/bin/env bash

set -u 
set -e

NAMESPACE=${1:-flink}
KUBE_CMD=${KUBE_CMD:-kubectl}
TIMEOUT=${TIMEOUT:-180}
FLINK_OPERATOR_VERSION="1.12.1"
CERT_MANAGER_VERSION="1.18.2"
KEYCLOAK_OPERATOR_VERSION="26.3.3"
SECURE_KAFKA=${SECURE_KAFKA:-"PLAINTEXT"}

printf "\n\n\e[32mInstalling example components into namespace: %s\e[0m\n\n" "${NAMESPACE}"

# Install CertManager - this is needed by the Flink Kubernetes Operator
printf "\n\e[32mChecking for CertManager install\e[0m\n"
if ${KUBE_CMD} get namespace cert-manager ; then
    printf "\e[32mCertManager is already installed\e[0m\n"
else
    ${KUBE_CMD} create -f https://github.com/jetstack/cert-manager/releases/download/v${CERT_MANAGER_VERSION}/cert-manager.yaml
fi

# Add the Flink operator's helm repo
printf "\n\e[32mChecking for Flink Operator %s helm repo\e[0m\n" ${FLINK_OPERATOR_VERSION}
if helm repo list | grep "flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}" ; then
    printf "\e[32mFlink Operator %s helm repo already exists\e[0m\n" ${FLINK_OPERATOR_VERSION}
else
    printf "\e[32mInstalling the Flink Operator %s helm repo\e[0m\n" ${FLINK_OPERATOR_VERSION}
    helm repo add --force-update flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/
fi

printf "\n\e[32mChecking for %s namespace\e[0m\n" "${NAMESPACE}"
if ${KUBE_CMD} get namespace "${NAMESPACE}" ; then
    printf "\n\e[32m%s namespace already exists\e[0m\n" "${NAMESPACE}"
else
    ${KUBE_CMD} create namespace "${NAMESPACE}"
fi

printf "\n\e[32mWaiting for cert-manager webhook to be ready...\e[0m"
${KUBE_CMD} -n cert-manager wait --for=condition=Available --timeout="${TIMEOUT}"s deployment cert-manager-webhook

printf "\n\e[32mChecking for Flink Operator install\e[0m\n"
if ${KUBE_CMD} -n "${NAMESPACE}" get deployment flink-kubernetes-operator ; then
    printf "\e[32mFlink Operator already installed\e[0m\n"
else
    printf "\e[32mInstalling the Flink Operator\e[0m\n"
    helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
      --set podSecurityContext=null \
      --set defaultConfiguration."log4j-operator\.properties"=monitorInterval\=30 \
      --set defaultConfiguration."log4j-console\.properties"=monitorInterval\=30 \
      --set defaultConfiguration."flink-conf\.yaml"="kubernetes.operator.metrics.reporter.prom.factory.class\:\ org.apache.flink.metrics.prometheus.PrometheusReporterFactory
       kubernetes.operator.metrics.reporter.prom.port\:\ 9249 " \
      -n "${NAMESPACE}"
fi

printf "\n\e[32mChecking for Strimzi Operator install\e[0m\n"
if ${KUBE_CMD} -n "${NAMESPACE}" get deployment strimzi-cluster-operator ; then
    printf "\e[32mStrimzi Operator already installed\e[0m\n"
else
    printf "\e[32mInstalling the Strimzi Operator\e[0m\n"
    ${KUBE_CMD} create -f 'https://strimzi.io/install/latest?namespace=flink' -n "${NAMESPACE}"
fi

printf "\n\e[32mChecking that this script is being run from within the tutorial directory\e[0m\n"
if [ -f "scripts/data-gen-setup.sh" ]; then
    printf "\e[32mFound scripts/data-gen-setup.sh\e[0m\n"
else
    printf "\e[31mError: scripts/data-gen-setup.sh file not found. Please make sure to run this script from the tutorial directory.\e[0m\n"
    exit 1
fi

case $SECURE_KAFKA in
  "PLAINTEXT")
    printf "\n\e[32mCreating Kafka pool and cluster (PLAINTEXT)\e[0m\n"
    ${KUBE_CMD} apply -f https://strimzi.io/examples/latest/kafka/kafka-single-node.yaml -n "${NAMESPACE}"
    ;;

  "TLS")
    printf "\n\e[32mCreating Kafka pool (TLS)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/kafka-pool.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka cluster (TLS)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/TLS/kafka.yaml -n "${NAMESPACE}"
    ;;

  "mTLS")
    printf "\n\e[32mCreating Kafka pool (mTLS)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/kafka-pool.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka cluster (mTLS)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/mTLS/kafka.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka user (mTLS)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/mTLS/kafka-user.yaml -n "${NAMESPACE}"

    printf "\n\e[32mWaiting for kafka user Secret to be generated (mTLS)...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" wait --for=create --timeout="${TIMEOUT}"s secret my-user
    ;;

  "SCRAM")
    printf "\n\e[32mCreating Kafka pool (SCRAM)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/kafka-pool.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka cluster (SCRAM)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/SCRAM/kafka.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka user (SCRAM)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/SCRAM/kafka-user.yaml -n "${NAMESPACE}"

    printf "\n\e[32mWaiting for kafka user Secret to be generated (SCRAM)...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" wait --for=create --timeout="${TIMEOUT}"s secret my-user
    ;;

  "OAuth2")
    printf "\n\e[32mCreating Kafka pool (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/kafka-pool.yaml -n "${NAMESPACE}"

    printf "\n\e[32mInstalling Keycloak Operator CRDs (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f "https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/${KEYCLOAK_OPERATOR_VERSION}/kubernetes/keycloaks.k8s.keycloak.org-v1.yml" -n "${NAMESPACE}"
    ${KUBE_CMD} apply -f "https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/${KEYCLOAK_OPERATOR_VERSION}/kubernetes/keycloakrealmimports.k8s.keycloak.org-v1.yml" -n "${NAMESPACE}"

    printf "\n\e[32mInstalling Keycloak Operator deployment (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f "https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/${KEYCLOAK_OPERATOR_VERSION}/kubernetes/kubernetes.yml" -n "${NAMESPACE}"

    printf "\n\e[32mCreating self-signed CA issuer using cert-manager (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/selfsigned-ca.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating self-signed Keycloak TLS certificate using cert-manager (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/keycloak/keycloak-cert.yaml -n "${NAMESPACE}"

    printf "\n\e[32mWaiting for self-signed Keycloak TLS certificate Secret to be generated (OAuth2)\e[0m\n"
    ${KUBE_CMD} wait --for=create --timeout="${TIMEOUT}"s secret keycloak-cert -n "${NAMESPACE}"

    printf "\n\e[32mCreating Keycloak \"kafka\" client Secret (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/keycloak/keycloak-kafka-client-secret.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Keycloak instance (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/keycloak/keycloak.yaml -n "${NAMESPACE}"

    printf "\n\e[32mImporting Keycloak realm \"kafka-authz-realm\" (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/keycloak/kafka-authz-realm.yaml -n "${NAMESPACE}"

    printf "\n\e[32mWaiting for Keycloak realm \"kafka-authz-realm\" to be done importing (OAuth2)\e[0m\n"
    ${KUBE_CMD} wait --for=condition=Done --timeout="${TIMEOUT}"s keycloakrealmimports kafka-authz-realm -n "${NAMESPACE}"

    printf "\n\e[32mCleaning up done \"kafka-authz-realm\" import resources (OAuth2)\e[0m\n"
    ${KUBE_CMD} delete keycloakrealmimports kafka-authz-realm -n "${NAMESPACE}"

    printf "\n\e[32mWaiting for Keycloak instance to be ready (OAuth2)\e[0m\n"
    ${KUBE_CMD} wait --for=condition=Ready --timeout="${TIMEOUT}"s keycloak keycloak -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka cluster (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/OAuth2/kafka.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka user (OAuth2)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/OAuth2/kafka-user.yaml -n "${NAMESPACE}"
    ;;

  "custom")
    printf "\n\e[32mCreating Kafka pool (custom)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/kafka-pool.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating self-signed CA issuer using cert-manager (custom)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/selfsigned-ca.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating self-signed custom user TLS certificate for using cert-manager (custom)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/custom/my-user-custom-cert.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka cluster (custom)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/custom/kafka.yaml -n "${NAMESPACE}"

    printf "\n\e[32mCreating Kafka user (custom)\e[0m\n"
    ${KUBE_CMD} apply -f secure-kafka/custom/kafka-user.yaml -n "${NAMESPACE}"
    ;;

  *)
    printf "\n\e[31mError: Unknown value passed for SECURE_KAFKA environment variable.\e[0m\n"
    exit 1
    ;;
esac

printf "\n\e[32mWaiting for Kafka to be ready...\e[0m\n"
${KUBE_CMD} -n "${NAMESPACE}" wait --for=condition=Ready --timeout="${TIMEOUT}"s kafka my-cluster

printf "\n\e[32mInstalling Apicurio Registry\e[0m\n"
${KUBE_CMD} apply -f apicurio-registry.yaml -n "${NAMESPACE}"

printf "\n\e[32mWaiting for Apicurio to be ready...\e[0m\n"
${KUBE_CMD} -n "${NAMESPACE}" wait --for=condition=Available --timeout="${TIMEOUT}"s deployment apicurio-registry

case $SECURE_KAFKA in
  "PLAINTEXT")
    printf "\n\e[32mDeploying data generation application...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" apply -f recommendation-app/data-generator.yaml
    ;;

  "TLS" | "mTLS" | "SCRAM" | "OAuth2" | "custom")
    printf "\n\e[32mCreating secure data generation kafka user...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" apply -f secure-kafka/data-generator/kafka-user.yaml

    printf "\n\e[32mWaiting for kafka user Secret to be generated...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" wait --for=create --timeout="${TIMEOUT}"s secret recommendation-app-kafka-user

    printf "\n\e[32mDeploying secure data generation application...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" apply -f secure-kafka/data-generator/data-generator.yaml

    printf "\n\e[32mCreating Role that allows Secret reading (if it doesn't exist)...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" create role secret-getter --dry-run=client -o yaml --verb=get --verb=list --verb=watch --resource=secrets | ${KUBE_CMD} -n "${NAMESPACE}" apply -f -

    printf "\n\e[32mCreating RoleBinding to allow flink service account to read Secrets (if it doesn't exist)...\e[0m\n"
    ${KUBE_CMD} -n "${NAMESPACE}" create rolebinding allow-flink-secret-getting --dry-run=client -o yaml --role=secret-getter --serviceaccount=flink:flink | ${KUBE_CMD} -n "${NAMESPACE}" apply -f -
    ;;

  *)
    printf "\n\e[31mError: Unknown value passed for SECURE_KAFKA environment variable.\e[0m\n"
    exit 1
    ;;
esac

printf "\n\e[32mWaiting for Flink operator to be ready...\e[0m\n"
${KUBE_CMD} -n "${NAMESPACE}" wait --for=condition=Available --timeout="${TIMEOUT}"s deployment flink-kubernetes-operator

printf "\n\e[32mThe data generation environment has been set up successfully\e[0m\n"
