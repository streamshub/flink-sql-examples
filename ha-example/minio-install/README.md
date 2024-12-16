# minio installation

This folder contains an example minio deployment yaml and the instructions are based on the [minio documentation](https://min.io/docs/minio/kubernetes/upstream/index.html).

1. Deploy the minio with default configurations
   ```
   kubectl apply -f minio.yaml -n flink
   ```
2. Access the MinIO S3 Console
   ```
   kubectl port-forward deployment/minio 9000 9090 -n flink
   ```
3. Create a bucket via MinIO S3 Console

   Open `http://localhost:9090`, and login with `minioadmin/minioadmin`, then go to `Buckets` -> `Create Bucket` to create a bucket.
4. Monitor the files in the bucket

   Click on the `Object Browser` to view the files in the buckets.

After minio is deployed and bucket is created, the flink configuration can be set like this in the `FlinkDeployment` CR:
```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: recommendation-app
spec:
  image: quay.io/streamshub/flink-sql-runner:v0.0.1
  flinkVersion: v1_19
  flinkConfiguration:
    # minio setting
    s3.access-key: minioadmin
    s3.secret-key: minioadmin
    s3.endpoint: http://MINIO_POD_ID:9000
    s3.path.style.access: "true"
    high-availability.storageDir: s3://test/ha
    state.checkpoints.dir: s3://test/cp
```
Note:
1. Suppose there is a bucket named `test` in minio.
2. The `MINIO_POD_ID` can be found via:
   ```
   kubectl get pod minio -n flink --template={{.status.podIP}}
   ```
