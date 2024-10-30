# Interactive ETL using Flink SQL

[Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/overview/) is a powerful tool for data exploration, manipulation and inter-connection.
It allows you to access the power of Flink's distributed stream processing abilities with a familar interface. 
In this tutorial we go over a simple introduction to using Flink SQL to read from a Kafka topic, perform basic queries, transform and clean data and then load that back into Kafka.

## Dependencies

In order to run this example you will need:

- [Minikube](https://minikube.sigs.k8s.io/docs/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/#kubectl)
- [Helm](https://helm.sh/)
- [Docker](https://www.docker.com/) or [Podman](https://podman.io/)

## Setup 

1. Spin up a [minikube](https://minikube.sigs.k8s.io/docs/) cluster:

    ```shell
    minikube start --cpus 4 --memory 16G
    ```

2. Run the setup script (from inside the `interactive-etl` folder) to spin up the required components:

    ```shell
    ./setup.sh
    ```

    This script will create a Kafka cluster (using [Strimzi](http://strimzi.io)), installs the [Apicurio schema registry](https://www.apicur.io/registry/) and sets up a [Flink session cluster](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments) (used for long running, multi-purpose deployments) using the [Flink Kubernetes Operator](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/). 
    It will then start the data-generator application to populate topics within the Kafka cluster.

    _Note_: 
    - The script assumes you are running against a minikube cluster where you have full access. You can run it against any other Kubernetes cluster but you will need to have the appropriate permissions to install the operator CRDs etc (see the script for more details of what is installed).
    - You can change the kubernetes client command (for example to use `oc` instead of `kubectl`) by setting the `KUBE_CMD` env var:
      ```shell
      KUBE_CMD=oc ./setup.sh
      ```

3. You can verify that the test data is flowing correctly by querying the Kafka topics using the console consumer:

    ```shell
    kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.sales.records
    ```

    Running the command above should show messages flowing after a few seconds.

## Interactive SQL client

The Flink distribution comes with an interactive SQL command line tool ([`sql-client.sh`](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/sqlclient/)) which allows you to submit SQL queries to a running Flink cluster. 
The `setup.sh` script you ran above, creates a long-running session cluster inside minikube which we can use for this purpose.

In order to access the cluster we need to allow access, from your local machine, to the job manager container running inside the minikube cluster:

```shell
kubectl -n flink port-forward <job-manager-pod> 8081:8081
```

The job manager pod will have the name format `session-cluster-<alphanumeric>`, your `kubectl` should tab-complete the name. 
If it doesn't then you can find the job manager name by running `kubectl -n flink get pods`.

The queries in the tutorial below will talk to the Kafka cluster and read messages in Avro format. 
To do this, the steaming queries created by the SQL client need to have certain plugins (Flink SQL connector jars) available in the cluster. 
The Flink session cluster deployed by the `setup.sh` script is based on the [Flink SQL Runner](https://github.com/streamshub/flink-sql) image, which already contains the Kafka and Avro Flink SQL connectors.

The interactive SQL client also need access to these plugin libraries, you could download the Flink distribution locally and add them manually. 
However, you can run the Flink SQL Runner container locally using the command below (make sure to add the `--net=host` flag so the container can see the forwarded job-manager port):

```shell
podman run -it --rm --net=host quay.io/streamshub/flink-sql-runner:v0.0.1 /opt/flink/bin/sql-client.sh embedded
```

If you use docker, you should be able to replace `podman` with `docker` in the command above.

## Tutorial

This tutorial will walk through some basic data exploration and ETL (Extract Transform Load) queries, using Flink SQL.
The [Flink SQL documentation](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/sql/queries/overview/) contains detailed breakdowns of the various SQL commands and query functions available.

### Source Data Table

The data generator application creates a topic (`flink.sales.records`) containing sales records. 
The schema for this topic can be seen in the `data-generator/src/main/resources/sales.avsc`:

```avroschema
{
   "namespace": "com.github.streamshub.kafka.data.generator.schema",
   "type": "record",
   "name": "Sales",
   "fields": [
      {"name": "user_id", "type": "string"},
      {"name": "product_id",  "type": "string"},
      {"name": "invoice_id",  "type": "string"},
      {"name": "quantity",  "type": "string"},
      {"name": "unit_cost",  "type": "string"}
   ]
}
```

In order to interact with the contents of this topic we need to create a table within Flink SQL:

```sql
CREATE TABLE SalesRecordTable ( 
    invoice_id STRING, 
    user_id STRING, 
    product_id STRING, 
    quantity STRING, 
    unit_cost STRING, 
    `purchase_time` TIMESTAMP(3) METADATA FROM 'timestamp', 
    WATERMARK FOR purchase_time AS purchase_time - INTERVAL '1' SECOND 
) WITH ( 
    'connector' = 'kafka',
    'topic' = 'flink.sales.records', 
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092', 
    'properties.group.id' = 'sales-record-group', 
    'value.format' = 'avro-confluent', 
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6', 
    'scan.startup.mode' = 'latest-offset'
); 
```

The [`CREATE`](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/sql/create/) statement will set up a table with the defined fields within the Flink SQL client. 
Keep in mind that nothing has actually been sent to the Flink cluster at this point, we have just setup where data will go once we run a query.
The [`WITH`](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/sql/queries/with/) clause allows us to pass required configuration to Flink to allow it to connect to external sources.
In the query above we are telling Flink to use the Kafka connector to pull data from the specified Kafka cluster and topic and also to talk to Apicurio to find the Apache Avro schema for the values in the messages of that topic.

### Querying Sales Data

With the table set up we can have a look at the data it holds.

```sql
SELECT * 
FROM SalesRecordTable;
```

When you submit this command, Flink will now create the connector instance and talk to the Kafka cluster. 
The SQL client will then create the Flink operators internally to gather the data and will open a live results window to display them.
After a few seconds you should see rows appearing in the table as messages arrive from the Kafka topic.

Once you are done you can press `q` to exit the live results view.
You have just run your first Flink SQL Job and can see that by running `SHOW JOBS;` (you should see the last job as `CANCELLED`).

Now lets try a more useful query, lets find all the User IDs for users who buy 3 or more items:

```sql
SELECT 
    DISTINCT(user_id), 
    quantity 
FROM SalesRecordTable 
WHERE quantity >= 3;
```

This might take a little while to show, there aren't many people buying lots of units.
Ok, lets find the true big spenders. 
User IDs for users who buy 3 or more items over 500 GBP:

```sql
SELECT 
    DISTINCT(product_id), 
    unit_cost, 
    quantity 
FROM SalesRecordTable 
WHERE 
    quantity >= 3 AND unit_cost > 500;
```

But there is a problem with this query:
```shell
[ERROR] Could not execute SQL statement. Reason:
java.lang.NumberFormatException: For input string: '£684'. Invalid character found.

```

Who put that `£` sign in there!?! 
Why is `unit_cost` a string? 
Indeed, if we check the Avro schema (`data-generator/src/main/resources/sales.avsc`) for the topic we can see: 

```json
    {"name": "unit_cost",  "type": "string"}
```

Now, we could go and talk to whoever owns this schema and get them to change it.
But that would require a redeployment of the producing application and maybe a downstream application expects that currency string and we would be breaking other pipelines if we changed it.

## Cleaning the cost field

All is not lost though, we can work around this formatting issue using Flink SQL.

There are a large number of [built-in functions](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/functions/systemfunctions/) that you can call as part of your queries, including [string manipulation](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/functions/systemfunctions/#string-functions) and [type conversion](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/dev/table/functions/systemfunctions/#type-conversion-functions).
We can use those to convert the currency string as part of the query.

In the query below, we add a sub-query that does the conversion of the original values and then select from the output of that:

```sql
SELECT 
    DISTINCT(product_id), cost_gbp, quantity 
FROM (
    SELECT 
        product_id, 
        CAST(TRIM(LEADING '£' from unit_cost) AS INT) AS cost_gbp, 
        quantity 
    FROM SalesRecordTable
) 
WHERE quantity >= 3 AND cost_gbp > 500;
```

The query above allows us to treat cost as a proper numeric value, but we are likely to need it in this form for more than just this query.
If we only need this transformed value for this session we can create a temporary view:

```sql
CREATE TEMPORARY VIEW CleanedSalesRecords ( 
    invoice_id, 
    user_id, 
    product_id, 
    quantity, 
    unit_cost_gbp, 
    purchase_time
) AS (
    SELECT 
        invoice_id, 
        user_id,
        product_id,
        CAST(quantity AS INT), 
        CAST(TRIM(LEADING '£' from unit_cost) AS INT), 
        purchase_time
    FROM SalesRecordTable
);
```

We can then write queries against this view and use the properly formatted cost without having to write the sub-query over again:

```sql
SELECT 
    DISTINCT(product_id), 
    unit_cost_gbp, 
    quantity 
FROM CleanedSalesRecords
WHERE 
    quantity >= 3 AND unit_cost_gbp > 500;
```

### Persisting back to Kafka

But what if we are going to be running a lot of queries using the properly formatted cost? 
What if we don't want to have to create the view every session? 
For that we need to persist the output of the formatting query. 
We can create a new Table which uses the `upsert-kafka` connector.
This will send any rows added to the table to a Kafka topic. 

First we define the table, the column types and the connection configuration. For `upsert-kafka` based tables you need to provide the `PRIMARY_KEY` as this will be used as the key for the message (each new row) sent to Kafka:

```sql
CREATE TABLE CleanedSalesRecordTable ( 
    invoice_id STRING, 
    user_id STRING, 
    product_id STRING, 
    quantity INT, 
    unit_cost_gbp INT, 
    purchase_time TIMESTAMP(3),
    PRIMARY KEY (`user_id`) NOT ENFORCED
) WITH ( 
    'connector' = 'upsert-kafka', 
    'topic' = 'flink.cleaned.sales.records.interactive', 
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092', 
    'properties.client.id' = 'sql-cleaning-client', 
    'properties.transaction.timeout.ms' = '800000', 
    'key.format' = 'csv', 
    'value.format' = 'csv', 
    'value.fields-include' = 'ALL' 
);
```

For the `upsert-kafka` connector you are providing the Kafka producer configuration and so you need to tell the connector what format the keys and values will be in. 
If you wanted to use Avro via Apicurio (like the source table we used above) you would need to first define the schema in Apicurio Registry and then reference that in the connector configuration.
For simplicity, we are using a simple CSV format for the key and the values.

Now you have the output table defined you can insert the results of the formatting query into it:

```sql
INSERT INTO CleanedSalesRecordTable
SELECT 
    invoice_id, 
    user_id,
    product_id,
    CAST(quantity AS INT), 
    CAST(TRIM(LEADING '£' from unit_cost) AS INT), 
    purchase_time
FROM SalesRecordTable;
```

Issuing this query will submit a Flink streaming Job to the session cluster.

```log
[INFO] Submitting SQL update statement to the cluster...
[INFO] SQL update statement has been successfully submitted to the cluster:
Job ID: 2a8c1677c64854482f22ca96295eec65
```

However, this doesn't mean everything is working correctly.
This just means you have successfully sent the jar containing the query code to the Flink cluster.
You can check if the job is actually running using `SHOW JOBS;`:

```
+----------------------------------+-------------------------+----------+-------------------------+
|                           job id |                job name |   status |              start time |
+----------------------------------+-------------------------+----------+-------------------------+
| 198e2bb14fffda57b303179d994d9ac1 |                 collect | CANCELED | 2024-10-04T13:21:30.660 |
| 3030185a27ea5a826d1cabef84e70b41 |                 collect | CANCELED | 2024-10-04T13:18:02.888 |
| 2a8c1677c64854482f22ca96295eec65 | CleanedSalesRecordTable |  RUNNING | 2024-10-04T14:02:45.643 |
+----------------------------------+-------------------------+----------+-------------------------+
```

Once you know that is running you can see the cleaned data in Kafka by querying the new output topic:

```shell
kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.cleaned.sales.records.interactive
```

### Conclusion

So, at the end of this short tutorial you have queried a Kafka topic of live sales data (extracted), performed data exploration and cleaning (transformed) and loaded the data back into Kafka for other pipelines to use.
All from an interactive SQL terminal.

## Converting to a stand alone Flink job

The ETL query (deployed above) will run like any other Flink streaming job and can be monitored, controlled and scaled through the UI or CLI.
However, your session Flink cluster might primarily be for data exploration and development, which means your ETL job would be competing for resources with other queries. 
If your transformed data is needed in production, it would be better to deploy the query as a stand-alone Flink Job independent of the session Flink cluster.

There is an example FlinkDeployment CR (`standalone-etl-deployment.yaml`) in this directory that will deploy the queries above in Flink's application mode. 
This will deploy the ETL query in a self-contained Flink cluster that can be managed like any other FlinkDeployment.

```shell
kubectl -n flink apply -f standalone-etl-deployment.yaml
```

Once you know that is running (`kubectl -n flink get pods`), you can see the cleaned data in Kafka by querying the new output topic (this has a different name to the one used in the interactive demo):

```shell
kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.cleaned.sales.records
```