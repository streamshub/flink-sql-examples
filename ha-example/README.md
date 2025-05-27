# Flink cluster High Availability configuration

A Flink cluster deployment consists of operators, job managers, and task managers.
However, the deployment is not configured to ensure high availability (HA) by default, additional configuration is needed to achieve it.

## Flink Kubernetes Operator

The Flink Kubernetes operator manages the Flink cluster Deployments.
It supports high availability through adding a standby operator instance and using leader election functionality to ensure that only one instance is _in charge_ of the Flink cluster Deployments at any one time.
To enable leader election, we need to add the following two mandatory parameters to the Kubernetes operator's configuration.

```
kubernetes.operator.leader-election.enabled: true
kubernetes.operator.leader-election.lease-name: flink-operator-lease
```
The lease name must be unique in the deployed namespace.

When installing the Flink Kubernetes operator using `helm`, we need to add these 2 parameters into the existing default configuration string.
This can be done via the command line using the `--set` flag:
```
helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
--set podSecurityContext=null \
--set defaultConfiguration."log4j-operator\.properties"=monitorInterval\=30 \
--set defaultConfiguration."log4j-console\.properties"=monitorInterval\=30 \
--set replicas=2 \
--set defaultConfiguration."flink-conf\.yaml"="kubernetes.operator.leader-election.enabled:\ true
kubernetes.operator.leader-election.lease-name:\ flink-operator-lease" \
-n flink
```
Note: The `replicas` configuration is to set to the total number of Flink Kubernetes operator replicas you want.
This will include the leader plus the number of standby instances you want.
One standby (two replicas in total) is usually sufficient.

After running this command, you should see that there are two instances of the Flink Kubernetes operator running.
```
kubectl  get pod -n flink | grep flink-kubernetes-operator
NAME                                          READY   STATUS    RESTARTS         AGE
flink-kubernetes-operator-6cd86cc8-fmb2x      2/2     Running   0                3h
flink-kubernetes-operator-6cd86cc8-g298v      2/2     Running   0                3h
```

And when checking the `lease` resource, we can see the holder(leader) operator's name.
```
kubectl get lease -n flink
NAME                       HOLDER                                      AGE
flink-operator-lease       flink-kubernetes-operator-6cd86cc8-g298v    45h
```

You can test the high availability fail-over between the instances, by deleting the leader operator pod.
You should then see that the holder will change to the other instance (or the newly created instance if the creation happens quickly).

## Flink Job Manager

The Job Manager ensures consistency during recovery across Task Managers.
For the Job Manager itself to recover consistently,
an external service must store a minimal amount of recovery metadata (like “ID of last committed checkpoint”),
as well as information needed to elect and lock which Job Manager is the leader (to avoid split-brain situations).

In order to configure Job Managers in your Flink Cluster for high availability you need to add the following settings to the configuration in your `FlinkDeployment` CR like this:
```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: recommendation-app
spec:
  image: quay.io/streamshub/flink-sql-runner:0.2.0
  flinkVersion: v2_0
  flinkConfiguration:
    # job manager HA settings
    high-availability.type: KUBERNETES
    high-availability.storageDir: s3://test/ha
```

## Task Manager

[Checkpointing](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/datastream/fault-tolerance/checkpointing/) is Flink’s primary fault-tolerance mechanism, wherein a snapshot of your job’s state is persisted periodically to some durable location.
In the case of failure, of a Task running your job's code, Flink will restart the Task from the most recent checkpoint and resume processing.
Although not strictly related to HA of the Flink cluster, it is important to enable checkpointing in production deployments to ensure fault tolerance.
By default, the checkpointing is not enabled.
You can enable it by setting the checkpointing interval in the `FlinkDeployment` CR like this:
```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: recommendation-app
spec:
   image: quay.io/streamshub/flink-sql-runner:0.2.0
   flinkVersion: v2_0
   flinkConfiguration:
    # job manager HA settings
    execution.checkpointing.interval: 1min
    state.checkpoints.dir: s3://test/cp
```
The settings above will checkpoint the Task state every 1 minute under the s3 path provided.

## Example: Making the `recommendation-app` fault-tolerant and highly available

Here, we will use the [recommendation-app](../recommendation-app) as an example to demonstrate the job manager HA.


1. If you haven't already, install cert-manager (this creates cert-manager in a namespace called `cert-manager`):
   ```
   kubectl create -f https://github.com/jetstack/cert-manager/releases/download/v1.17.2/cert-manager.yaml
   kubectl wait deployment --all  --for=condition=Available=True --timeout=300s -n cert-manager
   ```
1. Installing Flink Kubernetes Operator with leader election enabled like this:
   ```shell
   helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
   --set podSecurityContext=null \
   --set defaultConfiguration."log4j-operator\.properties"=monitorInterval\=30 \
   --set defaultConfiguration."log4j-console\.properties"=monitorInterval\=30 \
   --set replicas=2 \
   --set defaultConfiguration."flink-conf\.yaml"="kubernetes.operator.leader-election.enabled:\ true
   kubernetes.operator.leader-election.lease-name:\ flink-operator-lease" \
   -n flink
   ```
1. Follow the [guide](minio-install/README.md) to deploy and create a local S3 compatible storage service using minio and add a bucket named `test`.
1. Deploy the `FlinkDeployment` CR with HA configured
   ```shell
   kubectl -n flink apply -f recommendation-app-HA/flink-deployment-ha.yaml
   ```
1. There should be 2 recommendation-app job manager and 1 task manager pod deployed
   ```shell
   kubectl -n flink get pod -l app=recommendation-app
   ```
   ```
   NAME                                  READY   STATUS    RESTARTS   AGE
   recommendation-app-76b6854f98-4qcz4   1/1     Running   0          3m59s
   recommendation-app-76b6854f98-9zb24   1/1     Running   0          3m59s
   recommendation-app-taskmanager-1-1    1/1     Running   0          2m5s
   ``` 
1. Browse the minio console, to make sure the metadata of the job manager is uploaded to `s3://test/ha` 
1. Find out which job manager pod is the leader

   To do this, check the pod logs.
   If a job manager is the standby, then the log will indicate that it is watching and waiting to becoming the leader.
   ```shell
   kubectl -n flink logs recommendation-app-76b6854f98-4qcz4 --tail=2 -f
   ```
   ```
   2024-12-11 08:31:22,729 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesConfigMapSharedInformer [] - Starting to watch for flink/recommendation-app-cluster-config-map, watching id:3c7e23f2-fcaa-4f47-8623-fa1ebe9609ea
   2024-12-11 08:31:22,729 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesConfigMapSharedInformer [] - Starting to watch for flink/recommendation-app-cluster-config-map, watching id:ea191b50-a1ce-43c6-9bfc-a61f739fa8b3
   ```
1. Delete the leader pod of job manager, and monitor the logs of the standby pod

   Keep the step(7) command running, and delete the leader pod in another terminal
   ```shell
   kubectl -n flink logs recommendation-app-76b6854f98-4qcz4 --tail=2 -f
   ```
   ```
   ...
   2024-12-11 08:50:16,438 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesLeaderElector [] - New leader elected  for recommendation-app-cluster-config-map.
   2024-12-11 08:50:16,518 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesLeaderElector [] - New leader elected 907d6eda-7619-45c5-97c6-e2a6243f56f6 for recommendation-app-cluster-config-map.
   2024-12-11 08:50:16,524 INFO  org.apache.flink.runtime.jobmaster.MiniDispatcherRestEndpoint [] - http://10.244.1.30:8081 was granted leadership with leaderSessionID=87a6a102-c77b-4ec2-b263-b20a60921c9e
   2024-12-11 08:50:16,524 INFO  org.apache.flink.runtime.resourcemanager.ResourceManagerServiceImpl [] - Resource manager service is granted leadership with session id 87a6a102-c77b-4ec2-b263-b20a60921c9e.
   ```
   You should see the leadership is changed to the other pod.
1. Make sure the checkpointing file is successfully uploaded to `s3://test/cp` via the minio console. 
1. Monitor the sink topic in kafka
   Run the console consumer to get the result of sink topic:
   ```shell
   kubectl -n flink exec -it my-cluster-dual-role-0 -- /bin/bash \
   ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.recommended.products --from-beginning
   ```
   ```
   user-36,"83,172,77,41,128,168","2024-12-11 08:32:35"
   user-63,"15,141,160,64,23,143","2024-12-11 08:32:35"
   user-0,"83,172,77,41,128,168","2024-12-11 08:32:40"
   ...
   user-93,"14,110","2024-12-11 09:00:20"
   user-75,128,"2024-12-11 09:00:25"
   ```
   It will emit the result by time. Keep this terminal open, we'll check it later.
1. Delete the task manager pod
   ```shell
   kubectl -n flink delete pod recommendation-app-taskmanager-1-1
   ```
   ```
   pod "recommendation-app-taskmanager-1-1" deleted
   ```
1.  Make sure the newly created task manager pod is loading the checkpoint
    ```shell
    kubectl -n flink logs recommendation-app-taskmanager-2-1 -f | grep test/cp
    ```
    ```
    ...
    2024-12-11 09:02:53,271 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Starting to restore from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/0a34bfc6-188c-405f-8778-114caa6029ec, 0a34bfc6-188c-405f-8778-114caa6029ec [1209621 bytes]}.
    2024-12-11 09:02:54,576 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Starting to restore from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/6b904fdf-d657-4a34-ab72-8f455c1aa578, 6b904fdf-d657-4a34-ab72-8f455c1aa578 [111579 bytes]}.
    2024-12-11 09:02:54,577 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Starting to restore from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/0c1c98c1-c423-4e62-b16c-fba2056e730d, 0c1c98c1-c423-4e62-b16c-fba2056e730d [49189 bytes]}.
    ...
    2024-12-11 09:02:55,280 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Finished restoring from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/d804b45a-d7d3-4fd6-8837-83a1652c447d, d804b45a-d7d3-4fd6-8837-83a1652c447d [48588 bytes]}.
    2024-12-11 09:02:55,572 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Finished restoring from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/ea11d00d-c79b-49d4-b1ff-a73ad01b7197, ea11d00d-c79b-49d4-b1ff-a73ad01b7197 [48167 bytes]}.
    ...
    ```
1.  Check the sink topic consumer output, it should continue from minutes ago, not from the beginning:
    ```shell
    kubectl -n flink exec -it my-cluster-dual-role-0 -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.recommended.products --from-beginning
    ```
    ``` 
    user-36,"83,172,77,41,128,168","2024-12-11 08:32:35"
    user-63,"15,141,160,64,23,143","2024-12-11 08:32:35"
    ...
    user-75,128,"2024-12-11 09:00:25"
    # pod deleted
    user-69,64,"2024-12-11 08:45:15"
    ...
    ```