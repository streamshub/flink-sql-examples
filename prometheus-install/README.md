# Integrate Prometheus into Flink cluster in non-openshift cluster

After deploying Flink cluster, you can then deploy Prometheus to monitor the metrics inside job manager and task manager by following steps:

1. Update the prometheus configuration with the ip address of flink pods to scrape from:

   **Linux:**
   ```
   sed -i s/JOB_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f1)/g prometheus-install/prometheus-config.yaml
   sed -i s/TASK_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f2)/g prometheus-install/prometheus-config.yaml
   ```
   **MacOS**
   ```
   sed -i '' s/JOB_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f1)/g prometheus-install/prometheus-config.yaml
   sed -i '' s/TASK_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f2)/g prometheus-install/prometheus-config.yaml
   ```
   Note: Here we assume there's only 1 job manager and 1 task manager. If you deployed more than that, please update the `prometheus-config.yaml` file.   

2. Install prometheus, configuration, and service:
   ```
   kubectl apply -f prometheus-install -n flink
   ```

3. Expose the prometheus UI with port-forward rule:
   ```
   kubectl port-forward svc/prometheus-service -n flink 9090
   ```
4. Now you can monitor the metrics in job manager or task manager via the prometheus UI is accessible at localhost:9090.
![img.png](job_metric.png)
![img.png](task_metric.png)

# Integrate Prometheus into Flink cluster in openshift cluster

Since Openshift already has built-in Prometheus installed and configured, we can integrate with it by deploying `PodMonitor` CR for flink cluster:

1. Install the pre-configured `PodMonitor` CR:
   ```
   oc apply -f prometheus-install/flink-monitor.yaml -n flink
   ```
   Note: This CR is using `recommendation-app` as an example. Please update it if it's for other application.

2. It takes around 5 minutes to wait for prometheus operator to update the config for prometheus server. After that, you can query the metrics in the Openshift UI as described [here](https://docs.openshift.com/container-platform/4.16/observability/monitoring/managing-metrics.html#querying-metrics-for-all-projects-as-an-administrator_managing-metrics).
![img.png](openshift_jobmanager.png)
![img.png](openshift_taskmanager.png)

       
