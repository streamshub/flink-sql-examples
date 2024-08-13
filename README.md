# Flink SQL Examples

This repository contains assets for running Flink SQL examples that start with one or more streams of
data in Apache Kafka topics, run a Flink SQL job and result in one or more streams of data sent
to Apache Kafka topics.

The `data-generator` module contains an application that can provide the streams of data for the different examples.

## Setting up the environment for running an example

1. Start minikube with the following resources.

   ```
   MINIKUBE_CPUS=4
   MINIKUBE_MEMORY=16384
   MINIKUBE_DISK_SIZE=25GB
   ```

2. Create a `flink` namespace:
   ```
   kubectl create namespace flink
   ```
3. Apply the Strimzi QuickStart:
   ```
   kubectl create -f 'https://strimzi.io/install/latest?namespace=flink' -n flink
   ```
4. Create a Kafka:
   ```
   kubectl apply -f https://strimzi.io/examples/latest/kafka/kraft/kafka-single-node.yaml -n flink 
   ```
5. Install Apicurio Registry:
   ```
   kubectl apply -f apicurio-registry.yaml -n flink
   ```
6. Install cert-manager (this creates cert-manager in a namespace called `cert-manager`):
   ```
   kubectl create -f https://github.com/jetstack/cert-manager/releases/download/v1.8.2/cert-manager.yaml
   ```
7. Deploy Flink Kubernetes Operator 1.8.0 (the latest stable version):
   ```
   helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.8.0/
   helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator -n flink
   ```
8. Build the `data-generator` image:
   ```
   mvn clean package && minikube image build data-generator -t data-generator:latest
   ```

The steps to run each example are described in their own README.

The source topics for the example will contain Avro records.
You can view the Apicurio Registry UI by running `kubectl port-forward service/apicurio-registry-api 8080 -n flink` and visiting http://localhost:8080/ui in a browser.

# Running the data-generator without a schema registry

It is possible to run the `data-generator` so that it produces CSV records, rather than Avro records.
In the `<EXAMPLE_DIRECTORY>/data-generator.yaml` file change the `USE_APICURIO_REGISTRY` to false.
In the SQL statements supplied in the `args` in the `<EXAMPLE_DIRECTORY>/flink-deployment.yaml`, switch to using CSV:
  - Change `'value.format' = 'avro-confluent'` to `'format' = 'csv'`.
  - Remove `value.avro-confluent.schema-registry.url`.
