# Integrate Prometheus into Flink cluster

After deploying Flink cluster, you can then deploy Prometheus to monitor the metrics inside job manager and task manager by these steps:

1. Update the prometheus configuration with the ip address of flink pods to scrape from:

   **Linux:**
   ```
   sed -i s/JOB_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f1)/g prometheus-config.yaml
   sed -i s/TASK_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f2)/g prometheus-config.yaml
   ```
   **MacOS**
   ```
   sed -i '' s/JOB_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f1)/g prometheus-config.yaml
   sed -i '' s/TASK_MANAGER/$(kubectl get pods -lapp=recommendation-app -n flink -o=jsonpath="{range .items[*]}{.status.podIP}{','}{end}" | cut -d ',' -f2)/g prometheus-config.yaml
   ```
   Note: Here we assume there's only 1 job manager and 1 task manager. If you deployed more than that, please update the `prometheus-config.yaml` file.   

2. Install prometheus, configuration, and service:
   ```
   kubectl apply -f . -n flink
   ```

3. Expose the prometheus UI with port-forward rule:
   ```
   kubectl port-forward svc/prometheus-service -n flink 9090
   ```
4. Now you can monitor the metrics in job manager or task manager via the prometheus UI is accessible at localhost:9090.
![img.png](job_metric.png)
![img.png](task_metric.png)

       
