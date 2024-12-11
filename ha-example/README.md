# Flink cluster HA configuration

In the deployment of Flink cluster, it consists of operators, job managers, and task managers.
By default, the deployment is not a high availability (HA) cluster, we need to configure it to achieve it.

## Flink operator

The Flink operator manages and operates Flink Deployments. It supports high availability through leader election and standby operator instances.
To enable leader election we need to add the following two mandatory operator configuration parameters.

```
kubernetes.operator.leader-election.enabled: true
kubernetes.operator.leader-election.lease-name: flink-operator-lease
```
The lease name must be unique in the current lease namespace.

So, when installing flink operator using helm, we need to add these 2 configs + existing default configs via command like this:
```
helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
--set podSecurityContext=null \
--set defaultConfiguration."log4j-operator\.properties"=monitorInterval\=30 \
--set defaultConfiguration."log4j-console\.properties"=monitorInterval\=30 \
--set replicas=2 \
--set defaultConfiguration."flink-conf\.yaml"="kubernetes.operator.metrics.reporter.slf4j.factory.class\:\ org.apache.flink.metrics.slf4j.Slf4jReporterFactory
kubernetes.operator.metrics.reporter.slf4j.interval\:\ 5\ MINUTE
kubernetes.operator.reconcile.interval:\ 15\ s
kubernetes.operator.observer.progress-check.interval:\ 5\ s
kubernetes.operator.leader-election.enabled:\ true
kubernetes.operator.leader-election.lease-name:\ flink-operator-lease" \
-n flink
```
Note: The `replicas` config is to set the number of operator replicas.

After running this command, you should see there are 2 operator replicas lanuched.
```
kubectl  get pod -n flink | grep flink-kubernetes-operator
NAME                                          READY   STATUS    RESTARTS         AGE
flink-kubernetes-operator-6cd86cc8-fmb2x      2/2     Running   0                3h
flink-kubernetes-operator-6cd86cc8-g298v      2/2     Running   0                3h
```

And when checking the `lease` resource, we can see hte holder(leader) operator's name.
```
kubectl get lease -n flink
NAME                       HOLDER                                      AGE
flink-operator-lease       flink-kubernetes-operator-6cd86cc8-g298v    45h
```

When trying to delete the leader operator pod, we can see the holder will change to the other one (or the newly created one),
which makes the operator highly available.

## job manager

The JobManager ensures consistency during recovery across TaskManagers. For the JobManager itself to recover consistently,
an external service must store a minimal amount of recovery metadata (like “ID of last committed checkpoint”),
as well as help to elect and lock which JobManager is the leader (to avoid split-brain situations).

Configure job manager high availability with kubernetes mode like this in flink configuration:
```
high-availability.type: kubernetes
high-availability.storageDir: s3:///flink/recovery
```

## task manager

Checkpointing is Flink’s primary fault-tolerance mechanism, wherein a snapshot of your job’s state persisted periodically to some durable location.
In the case of failure, Flink will restart from the most recent checkpoint and resume processing.
Although this is not completely related to HA of the flink cluster, it is also important to know it for the Flink fault tolerance.
By default, the checkpointing is not enabled in Flink cluster, you can enable it by setting the checkpointing interval:
```
execution.checkpointing.interval: 1min
state.checkpoints.dir: s3://test/cp
```
In this example, it will checkpoint the process status every 1 minute into the s3 path.

### Run HA in recommendation-app application

Here, we will use [recommendation-app](../recommendation-app) as an example to demonstrate the job manager HA.

1. Follow the [guide](minio-install/README.md) to deploy and create a bucket named `test` in minio
2. Replace the `MINIO_POD_ID` in `recommendation-app-HA/flink-deployment-ha.yaml` with this command output:
   ```
   kubectl get pod minio -n flink --template={{.status.podIP}}
   ```
3. Deploy the flinkDeployment with HA configured
   ```
   kubectl apply -f recommendation-app-HA/flink-deployment-ha.yaml -n flink
   ```
4. There will be 2 recommendation-app (job manager) and 1 task manager pods deployed
   ```
   kubectl  get pod -l app=recommendation-app -n flink
   NAME                                  READY   STATUS    RESTARTS   AGE
   recommendation-app-76b6854f98-4qcz4   1/1     Running   0          3m59s
   recommendation-app-76b6854f98-9zb24   1/1     Running   0          3m59s
   recommendation-app-taskmanager-1-1    1/1     Running   0          2m5s
   ``` 
5. Browse the minio console, to make sure the metadata of the job manager is uploaded to s3://test/ha  
6. Find out which job manager pod is the leader

   Check the pod logs, if it's the standby job manager, the log will stay at these lines, and waiting for becoming the leader.
   ```
   kubectl  logs -n flink recommendation-app-76b6854f98-4qcz4 --tail=2 -f
   2024-12-11 08:31:22,729 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesConfigMapSharedInformer [] - Starting to watch for flink/recommendation-app-cluster-config-map, watching id:3c7e23f2-fcaa-4f47-8623-fa1ebe9609ea
   2024-12-11 08:31:22,729 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesConfigMapSharedInformer [] - Starting to watch for flink/recommendation-app-cluster-config-map, watching id:ea191b50-a1ce-43c6-9bfc-a61f739fa8b3
   ```
7. Delete the leader pod of job manager, and monitor the logs of the standby pod

   Keep the step(5) command running, and delete the leader pod in another terminal
   ```
   kubectl  logs -n flink recommendation-app-76b6854f98-4qcz4 --tail=2 -f
   ...
   2024-12-11 08:50:16,438 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesLeaderElector [] - New leader elected  for recommendation-app-cluster-config-map.
   2024-12-11 08:50:16,518 INFO  org.apache.flink.kubernetes.kubeclient.resources.KubernetesLeaderElector [] - New leader elected 907d6eda-7619-45c5-97c6-e2a6243f56f6 for recommendation-app-cluster-config-map.
   2024-12-11 08:50:16,524 INFO  org.apache.flink.runtime.jobmaster.MiniDispatcherRestEndpoint [] - http://10.244.1.30:8081 was granted leadership with leaderSessionID=87a6a102-c77b-4ec2-b263-b20a60921c9e
   2024-12-11 08:50:16,524 INFO  org.apache.flink.runtime.resourcemanager.ResourceManagerServiceImpl [] - Resource manager service is granted leadership with session id 87a6a102-c77b-4ec2-b263-b20a60921c9e.
   ```
   You should see the leadership is changed to the other pod.
8. Make sure the checkpointing file is successfully uploaded onto s3://test/cp via minio console 9Monitor the sink topic in kafka

9. Run the console consumer to get the result of sink topic:
   ```
   kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
   ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.recommended.products --from-beginning
   user-36,"83,172,77,41,128,168","2024-12-11 08:32:35"
   user-63,"15,141,160,64,23,143","2024-12-11 08:32:35"
   user-0,"83,172,77,41,128,168","2024-12-11 08:32:40"
   ...
   user-93,"14,110","2024-12-11 09:00:20"
   user-75,128,"2024-12-11 09:00:25"
   ```
   It will emit the result by time. Keep this terminal open, we'll check it later.
9. Delete the task manager pod
   ```
   kubectl  delete pod/recommendation-app-taskmanager-1-1 -n flink
   pod "recommendation-app-taskmanager-1-1" deleted
   ```
11. Make sure the newly created task manager pod is loading the checkpoint
    ```
    kubectl  logs recommendation-app-taskmanager-2-1 -n flink -f | grep test/cp
    ...
    2024-12-11 09:02:53,271 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Starting to restore from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/0a34bfc6-188c-405f-8778-114caa6029ec, 0a34bfc6-188c-405f-8778-114caa6029ec [1209621 bytes]}.
    2024-12-11 09:02:54,576 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Starting to restore from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/6b904fdf-d657-4a34-ab72-8f455c1aa578, 6b904fdf-d657-4a34-ab72-8f455c1aa578 [111579 bytes]}.
    2024-12-11 09:02:54,577 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Starting to restore from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/0c1c98c1-c423-4e62-b16c-fba2056e730d, 0c1c98c1-c423-4e62-b16c-fba2056e730d [49189 bytes]}.
    ...
    2024-12-11 09:02:55,280 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Finished restoring from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/d804b45a-d7d3-4fd6-8837-83a1652c447d, d804b45a-d7d3-4fd6-8837-83a1652c447d [48588 bytes]}.
    2024-12-11 09:02:55,572 INFO  org.apache.flink.runtime.state.heap.HeapRestoreOperation     [] - Finished restoring from state handle: KeyGroupsStateHandle{groupRangeOffsets=KeyGroupRangeOffsets{keyGroupRange=KeyGroupRange{startKeyGroup=0, endKeyGroup=127}}, stateHandle=RelativeFileStateHandle State: s3://test/cp/61f9e79ca6a301ed97cc4c1c6197accf/chk-28/ea11d00d-c79b-49d4-b1ff-a73ad01b7197, ea11d00d-c79b-49d4-b1ff-a73ad01b7197 [48167 bytes]}.
    ...
    ```
12. Check the sink topic consumer output, it should continue from minutes ago, not from the beginning:
    ```
    kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.recommended.products --from-beginning
    user-36,"83,172,77,41,128,168","2024-12-11 08:32:35"
    user-63,"15,141,160,64,23,143","2024-12-11 08:32:35"
    ...
    user-75,128,"2024-12-11 09:00:25"
    # pod deleted
    user-69,64,"2024-12-11 08:45:15"
    ...
    ```