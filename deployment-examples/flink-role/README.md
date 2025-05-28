# Run Flink jobs in other namespace

Flink operator by default will watch all namespaces in the kubernetes cluster, but to run Flink jobs in a namespace 
other than the operator namespace, users are responsible for creating a flink service account in that namespace.

```shell
kubectl apply -f flink-role/flink-role.yaml -n NAMESPACE
```

After creating the service account in the NAMESPACE, you can follow each example to deploy FlinkDeployment CR to 
the NAMESPACE to run the jobs. For example:
```shell
kubectl apply -f recommendation-app/flink-deployment.yaml -n NAMESPACE
```