# minio installation

This folder contains an example minio deployment yaml and the instructions are based on the [minio documentation](https://min.io/docs/minio/kubernetes/upstream/index.html).

1. Deploy the minio with default configurations:
   ```shell
   kubectl -n flink apply -f minio.yaml
   ```
   This will create the minio deployment and service for the minio API endpoint (that Flink will use to store state).
1. We need to create a storage bucket within minio. We could do this by exposing the minio-api service and using the [minio CLI client](https://min.io/docs/minio/linux/reference/minio-mc.html). However, for convenience, we will use the minio web UI. To do this, we need to expose the web UI's port on the minio deployment:
   ```shell
   kubectl -n flink expose deployment minio --name=minio-ui --type=NodePort --port=9090
   ```
   Depending on how you Kubernetes cluster is set up, you may need to use a different service type (like LoadBalancer) or use an Ingress resource to expose the WebUI. NodePort should be sufficient for local testing with minikube.
1. Get the WebUI address. If you are using minikube, you can use the following command to get the address:
   ```shell
   minikube service -n flink minio-ui --url 
   ```
   or you can find the assigned NodePort via:
   ```shell
   kubectl -n flink get service minio --output='jsonpath="{.spec.ports[0].nodePort}"'
   ```
   and add this to any of the K8s cluster's node IPs to get the address.
1. Create a bucket via MinIO WebUI. To do this, open the address found above and login with username: `minioadmin` and password: `minioadmin`, then go to `Buckets` -> `Create Bucket` to create a bucket called `test`.
1. Monitor the files in the bucket.
   Click on the `Object Browser` to view the files in the buckets.

After minio is deployed and bucket is created, the flink configuration can be set like this in the `FlinkDeployment` CR:
```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: recommendation-app
spec:
  image: quay.io/streamshub/flink-sql-runner:0.2.0
  flinkVersion: v2_0
  flinkConfiguration:
    # minio setting
    s3.access-key: minioadmin
    s3.secret-key: minioadmin
    s3.endpoint: http://minio-api.flink.svc.cluster.local:9000
    s3.path.style.access: "true"
    high-availability.storageDir: s3://test/ha
    state.checkpoints.dir: s3://test/cp
```
