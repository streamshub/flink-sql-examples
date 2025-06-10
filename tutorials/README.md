# Flink SQL Tutorials

This directory contains assets for running Flink SQL examples that start with one or more streams of
data in Apache Kafka topics, run a Flink SQL job and result in one or more streams of data sent
to Apache Kafka topics.

The `data-generator` module contains an application that can provide the streams of data for the different examples.

## Setting up the environment for running an example

### Prerequisites

* Kubernetes cluster
  * If you are using minikube we recommend the following resources as a minimum:
     ```
     MINIKUBE_CPUS=4
     MINIKUBE_MEMORY=16384
     MINIKUBE_DISK_SIZE=25GB
     ```
* [`kubectl`](https://kubernetes.io/docs/reference/kubectl/) CLI or equivalent
  * For example, if you are using RedHat OpenShift you can substitute `kubectl` for [`oc`](https://docs.openshift.com/container-platform/4.16/cli_reference/openshift_cli/getting-started-cli.html) in the instructions
* [`docker`](https://docs.docker.com/install/) or [`podman`](https://podman.io/docs/installation)

### Building the data-generator application

The container image for the data-generator application is in [quay.io](https://quay.io/repository/streamshub/flink-examples-data-generator), however you can build the image yourself.
If you choose to do this make sure you update the `data-generator.yaml` file for the example to point to your new image.

1. Build the application:
   ```
   mvn clean package
   ```
2. Build the image:
   ```
   ${BUILD_COMMAND} -t flink-examples-data-generator:latest data-generator
   ```

### Installing Apache Kafka, Apache Flink and Apicurio Registry

You can install the required components by running:
```bash
./scripts/data-gen-setup.sh
```

Alternatively, you can follow the steps below to install Apache Kafka, Apache Flink and Apicurio Registry manually.

1. Create a `flink` namespace:
   ```
   kubectl create namespace flink
   ```
2. Apply the Strimzi QuickStart:
   ```
   kubectl create -f 'https://strimzi.io/install/latest?namespace=flink' -n flink
   ```
3. Create an Apache Kafka cluster:
   ```
   kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-single-node.yaml -n flink 
   ```
4. Install Apicurio Registry:
   ```
   kubectl apply -f apicurio-registry.yaml -n flink
   ```
5. Install cert-manager (this creates cert-manager in a namespace called `cert-manager`):
   ```
   kubectl create -f https://github.com/jetstack/cert-manager/releases/download/v1.17.2/cert-manager.yaml
   kubectl wait deployment --all  --for=condition=Available=True --timeout=300s -n cert-manager
   ```
6. Deploy Flink Kubernetes Operator 1.12.0 (the latest stable version):
   ```
   helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.12.0/
   helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
   --set podSecurityContext=null \
   --set defaultConfiguration."log4j-operator\.properties"=monitorInterval\=30 \
   --set defaultConfiguration."log4j-console\.properties"=monitorInterval\=30 \
   --set defaultConfiguration."flink-conf\.yaml"="kubernetes.operator.metrics.reporter.prom.factory.class\:\ org.apache.flink.metrics.prometheus.PrometheusReporterFactory
    kubernetes.operator.metrics.reporter.prom.port\:\ 9249 " \
   -n flink
   ```
   Note:<br>
   (1) Set `podSecurityContext` to null so that we can run in OpenShift environment<br>
   (2) Set `monitorInterval` to log4j properties file so that we can dynamically change log level for operator and job/task manager.
   (3) Set the metrics reporter as prometheus for [further integration](../deployment-examples/prometheus-install/README.md).

### Running an example

The steps to run each example are described in their own README.
Make sure you have already [installed Apache Kafka, Apache Flink and Apicurio Registry](#installing-apache-kafka-apache-flink-and-apicurio-registry).

### Running an example in other namespace

By default, we assume all the jobs are running in `flink` namespace, but it is also possible to run jobs in other namespaces.
Please see [here](../deployment-examples/flink-role/README.md) for more information.

### Viewing the Apicurio Registry UI

The source topics for the example will contain Avro records.
You can view the Apicurio Registry UI by running `kubectl port-forward service/apicurio-registry-service 8080 -n flink` and visiting http://localhost:8080/ui in a browser.

### Changing log level dynamically

Since the `monitorInterval` has been set via `helm install` command above, we can change the log level via updating/patching
the config map directly. To change the operator log level, run `kubectl edit cm flink-operator-config -n flink` and update
the `log4j-operator.properties` section. To change the job/task manager log level, find the config map for the application and edit it.
For example: run `kubectl edit cm flink-config-recommendation-app -n flink` for recommendation-app example and
update the `log4j-console.properties` section.

# Running the data-generator without a schema registry

It is possible to run the `data-generator` so that it produces CSV records, rather than Avro records.
In the `<EXAMPLE_DIRECTORY>/data-generator.yaml` file change the `USE_APICURIO_REGISTRY` to false.
In the SQL statements supplied in the `args` in the `<EXAMPLE_DIRECTORY>/flink-deployment.yaml`, switch to using CSV:
  - Change `'value.format' = 'avro-confluent'` to `'format' = 'csv'`.
  - Remove `value.avro-confluent.schema-registry.url`.

# Releasing

See [RELEASING.md](../RELEASING.md) for instructions on how to release.
