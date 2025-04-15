+++
title = 'Building a recommendation application with Flink SQL'
+++

## Scenario 

Orinoco Inc, a retail sales company, wants to display a widget on product pages of similar products that the user might be interested in buying.
They should recommend:

- Up to 6 highly rated products in the same category as that of the product the customer is currently viewing
- Only products that are in stock
- Products that the customer has bought before should be favoured
- _TODO: Avoid showing suggestions that have already been made in previous page views_

## Data

Input:

- A clickstream (user id, product id, event time) in a Kafka topic called `flink.click.stream`.
- A stream of purchases (user id, product id, purchase date) in Kafka topic called `flink.sales.records`.
- An inventory of products (product id, product name, product category, number in stock, rating)

Output: 

- A stream of recommendations (user id, [list of 6 product ids]) to a Kafka topic called `flink.recommended.products`.

## Walk-through
 
This section walks through the creation of a Flink SQL query to satisfy the scenario requirements. 
The [Running the Application](#running-the-application) section covers running this query in a Kubernetes cluster.

In order to use all the input data sources in Flink SQL, we need to create Tables for each.
In Flink SQL _Tables_ represent external data and allow us to query and manipulate that data.

### Product Inventory Data

In order to recommend products in the same category and know if they are in stock, we need a table linking a product id to its category and stock level.
We also need to know the average rating users give these products to know if they are worth recommending.
We could use a connection to a relational database to do this. 
However, to keep things simple, we can use a CSV file containing the data.

An example of the product inventory data CSV is shown below:

```csv
0,accessories,67,6
1,appliances,21,5
2,health&beauty,56,6
3,home&furniture,59,4
4,home&furniture,23,9
5,toys,90,4
6,toys,17,1
7,accessories,96,5
```

Flink has a file system connector included by default, so we can setup a table that will map each item in the CSV row to a column in the table:

```sql
CREATE TABLE ProductInventoryTable (
    product_id STRING, 
    category STRING, 
    stock STRING, 
    rating STRING 
) WITH (
    'connector' = 'filesystem', 
    'path' = '/opt/flink/data/productInventory.csv', 
    'format' = 'csv', 
    'csv.ignore-parse-errors' = 'true' 
); 
```

In the [Running the Application](#running-the-application) section we see how this CSV can be loaded into a ConfigMap and mounted in the Flink Task Manager Pod.

### Click Stream and Sales Records Kafka Topics

#### Kafka Connectors

In order to perform the recommendation we need to know what customers are clicking on (the clickstream) and what they have bought (sales records).
Both of these are housed in Kafka topics, therefore to access the data they hold we need to create Flink SQL tables.
The StreamsHub [Flink SQL Runner](https://github.com/streamshub/flink-sql) project provides an image that already contains the Flink Kafka connector and its corresponding Flink SQL Table connector. 
Using that image (see the [Running the Application](#running-the-application) section for more details) means we can create tables to read from Kafka topics directly into Flink SQL.

#### Kafka Topic Schemas

Data in Kafka topics are just bytes, Kafka cannot tell us (or Flink SQL) what those bytes mean.
For that we need to know the schema of the messages within the topic.
Thankfully, the thoughtful people who designed the data generation application which publishes the click stream and sales records, have produced [Avro](https://avro.apache.org/) schema files ([click stream schema](https://github.com/streamshub/flink-sql-examples/blob/main/data-generator/src/main/resources/clickStream.avsc), [sales records schema](https://github.com/streamshub/flink-sql-examples/blob/main/data-generator/src/main/resources/sales.avsc)), which tell us what each message on those topics contain.
These schemas have then been registered with an instance of the [Apicurio Registry](https://www.apicur.io/registry/).
Flink's Kafka connector can read Avro Schema from the Confluent Schema Registry (by including the `flink-sql-avro-confluent-registry` library, which the StreamsHub Flink SQL Runner image does) and the Apicurio Registry has a Confluent compatible API. 
Therefore, using the StreamsHub Flink SQL Runner image, we can use `avro-confluent` as the value format and supply the address of the Apicurio Registry in our Flink Kafka Connector configuration and Flink will be able to pull the correct schema for each topic and deserialize the messages automatically.

#### Event time

One final thing to cover when creating Tables from event streams is dealing with time.
The Flink documentation gives a detailed overview of how it [deals with time](https://nightlies.apache.org/flink/flink-docs-stable/docs/concepts/time/).
The key takeaway is that you should define an _event time_ for each message and way to generate a _watermark_ within the stream.

For Flink SQL Tables the [`WATERMARK`](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/sql/create/#watermark) clause can be used to tell Flink how it should inject watermarks into the stream.
Typically you tell Flink which field in the table is used for event time and how you want watermarks to be created. 
If you are sure an event will never be late, or you don't mind dropping ones that are, you could have a new watermark issued every time the event time increases.
However, in most circumstances you will want to allow a small amount of time to account for the latency from event creation to ingestion (entering Flink) and for late messages to arrive. 
To do this you can set the `WATERMARK` clause to base the generation of new watermarks into the stream on the highest seen event time minus some time period.
For example, if you know the latency from event generation to ingestion is usually no more than 2 seconds and you don't care about messages more than 10 seconds old, then you would create your table using a statement like the one below:

```sql
CREATE TABLE <table name> (
  <fieldName> <TYPE>, 
  <fieldName> <TYPE>, 
  <fieldName> <TYPE>, 
  `event_time` TIMESTAMP(3) METADATA FROM '<timestamp field>', 
  WATERMARK FOR event_time AS event_time - INTERVAL '10' SECOND 
);
```

Bear in mind that, watermarks are used to trigger time based operations in Flink. 
Therefore, the longer the delay in issuing watermarks the more likely you are to catch late events, but the longer your windows will wait to close.
This will introduce additional latency and so you should balance the risk of missing late events against that increased processing latency.

#### Click Stream Table 

Putting all the above together results in the following `CREATE TABLE` statement for the Click Stream Table:

```sql
CREATE TABLE ClickStreamTable (
  user_id STRING, 
  product_id STRING, 
  `event_time` TIMESTAMP(3) METADATA FROM 'timestamp', 
  WATERMARK FOR event_time AS event_time - INTERVAL '1' SECOND 
) WITH ( 
  'connector' = 'kafka', 
  'topic' = 'flink.click.streams', 
  'properties.bootstrap.servers' = 
  'my-cluster-kafka-bootstrap.flink.svc:9092', 
  'properties.group.id' = 'click-stream-group', 
  'value.format' = 'avro-confluent', 
  'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6', 
  'scan.startup.mode' = 'latest-offset' 
); 
```

#### Sales Records Table

Similarly, for the sales records table you would use a statement like this:

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

### Making the recommendation

Now we have the external data accessible via Tables we can put together the recommendation pipeline.
Lets remind ourselves of what the pipeline needs to recommend:

- Up to 6 highly rated products in the same category as that of the product the customer is currently viewing
- Only products that are in stock
- Products that the customer has bought before should be favoured

We start with the what the customer is currently viewing, the clickstream.
But that stream only contains the user ID and the product ID, we need to know the category the product belongs to.
To do that, we can join the click stream table with the product inventory table to pull out the category.

```sql
CREATE TEMPORARY VIEW clicked_products AS 
  SELECT DISTINCT 
    c.user_id, c.event_time, 
    p.product_id, 
    p.category 
  FROM ClickStreamTable AS c 
  JOIN ProductInventoryTable AS p ON c.product_id = p.product_id; 
```

The query above constructs a `TEMPORARY` `VIEW`. 
Views provide a way to reference the results of a query.
In the statement above we are assigning the results of the join between the clickstream and product inventory to the `clicked_products` view, so we can reference it in later queries.
The `TEMPORARY` clause in the `CREATE` statement refers to how Flink will persist the metadata about this view and is linked to Flink Catalogs that we won't cover here (you can read more about Catalogs in the [docs](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/catalogs/)).

Now we have the product category information in the clickstream we need to get all products, in that category, that are in stock and mark whether the user purchased them or not.
To do that we can `JOIN` the enriched clickstream on the product inventory table, where the categories match, to get all products in that category. 
This allows us to also pull in the stock level and ratings. 
We can then take this set of products from the same category and `LEFT JOIN` on the sales records table. 
This will pass through all entries from the category, whether they have been purchased or not and allow us to have a new `purchased` column for any products the the user has bought in the past.  
We can then filter out any items that are not in stock.

We do the above operations using the statement below and create a `VIEW` for the results:

```sql
CREATE TEMPORARY VIEW category_products AS 
  SELECT 
    cp.user_id, 
    cp.event_time, 
    p.product_id, 
    p.category, 
    p.stock, 
    p.rating, 
    sr.user_id as purchased 
  FROM clicked_products cp 
  JOIN ProductInventoryTable AS p ON cp.category = p.category 
  LEFT JOIN SalesRecordTable sr ON cp.user_id = sr.user_id AND p.product_id = sr.product_id 
  WHERE p.stock > 0 
  GROUP BY 
    p.product_id, 
    p.category, 
    p.stock, 
    cp.user_id, 
    cp.event_time, 
    sr.user_id, 
    p.rating
; 
```

Now we have a view which, for each clickstream event, emits a list of in-stock products in the same category and also marks those purchased by the user.  
However, these are in no particular order.
To indicate how good of a recommendation each entry might be, we can use the `ROW NUMBER` [aggregate function](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/functions/systemfunctions/#aggregate-functions). 
This will label each item sequentially based on the ordering within the window of items currently being processed.
We can use the [`OVER`](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/sql/queries/over-agg/#over-aggregation) clause combined with a `PARTITION BY` to group results first into partitions by user ID and then `ORDER BY` to order those partitioned products first by whether they have been purchased previously and then by their average rating.

The statement below illustrates the operations described above:

```sql
CREATE TEMPORARY VIEW top_products AS 
  SELECT cp.user_id, 
    cp.event_time, 
    cp.product_id, 
    cp.category, 
    cp.stock, 
    cp.rating, 
    cp.purchased, 
    ROW_NUMBER() OVER (PARTITION BY cp.user_id ORDER BY cp.purchased DESC, cp.rating DESC) AS rn 
  FROM category_products cp
; 
```

Now we have a ranked list of products per user clickstream event.
To recommend the top 6, we simply need to `SELECT` from the view above using a `WHERE rn <= 6` clause and `GROUP BY` the user ID.
To package the results up more cleanly we can use the `LISTAGG` [aggregation function](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/functions/systemfunctions/#aggregate-functions) to combine the product IDs into a single list.
We can also define over what time window we want to source data for the recommendation by defining a [window aggregation](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/sql/queries/window-agg/#group-window-functions) clause below the `GROUP BY` clause. 
The simplest time window is a tumbling window, which collects data over a defined period, performs the aggregation and then moves forward by the same period and so on.
The longer the window, the more clickstream events will be included but the slower the results will be to update and more data you need to aggregate.

A statement to perform the final recommendation query is shown below:

```sql
SELECT 
  user_id, 
  LISTAGG(product_id, ',') AS top_product_ids, 
  TUMBLE_END(event_time, INTERVAL '5' SECOND) as window_end_timestamp
FROM top_products 
WHERE rn <= 6 
GROUP BY user_id, 
TUMBLE(event_time, INTERVAL '5' SECOND)
;
```

### Saving the results

Once we have generated the product recommendations, we need to save that data somewhere. 
The easiest option is to output the results back to Kafka so that some other service can pick them up and present them to the user.
To do this we need to create an output Kafka table.
The Kafka connector included in the Flink SQL Runner image allows us to do this by defining the `connector` as `upsert-kafka` in the `WITH` clause of the `CREATE TABLE` statement.
For simplicity sake, we will just output the recommendations as CSV data (you could also define an Avro schema and upload that to the Apicurio registry).

The statement for creating this "sink" table is shown below:

```sql
CREATE TABLE CsvSinkTable ( 
  user_id STRING, 
  top_product_ids STRING, 
  `event_time` TIMESTAMP(3), 
  PRIMARY KEY(`user_id`) NOT ENFORCED 
) WITH ( 
  'connector' = 'upsert-kafka', 
  'topic' = 'flink.recommended.products', 
  'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092', 
  'properties.client.id' = 'recommended-products-producer-client', 
  'properties.transaction.timeout.ms' = '800000', 
  'key.format' = 'csv', 
  'value.format' = 'csv', 
  'value.fields-include' = 'ALL' 
); 
```

We can then load data into the table by using the recommendation query from above as input to the `INSERT INTO` command:

```sql
INSERT INTO CsvSinkTable 
  SELECT 
    user_id, 
    LISTAGG(product_id, ',') AS top_product_ids, 
    TUMBLE_END(event_time, INTERVAL '5' SECOND) as window_end_timestamp
  FROM top_products 
  WHERE rn <= 6 
  GROUP BY user_id, 
  TUMBLE(event_time, INTERVAL '5' SECOND)
;
```

## Running the application

### Prerequisite:

The steps in the [README.md](../README.md) required to be completed for setting up:

- Kafka cluster
- Flink Kubernetes Operator
- Apicurio Registry
- Flink SQL Runner image
- Data Generator image

### Deploying Flink cluster for recommendation app

From the example repository's root:

1.  Apply the `data-generator` Kubernetes Deployment:
    ```
    kubectl apply -f recommendation-app/data-generator.yaml -n flink
    ```
    It continuously produces sample data to `flink.sales.records` and `flink.click.streams`.
2. Create a ConfigMap that holds product inventory data in CSV format. 
    ```
    kubectl create configmap product-inventory --from-file recommendation-app/productInventory.csv -n flink
    ```
    The ConfigMap will be volume mounted to the recommendation-app pods.
3. Apply the FlinkDeployment for the recommendation-app:
    ```
    kubectl apply -f recommendation-app/flink-deployment.yaml -n flink
    ```
4. In a separate tab, `exec` into the kafka pod and run the console consumer:
    ```
    kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.recommended.products --from-beginning
    ```
5. You should see messages such as the following:
   ```
    user-27,"140,13,137,95,39,138","2024-06-28 13:01:55"
    user-14,"40,146,74,81,37,19","2024-06-28 13:01:55"
    user-36,"42,106,82,153,158,85","2024-06-28 13:02:00"
    user-5,"83,123,77,41,193,136","2024-06-28 13:02:00"
    user-27,"55,77,168","2024-06-28 13:02:05"
    user-44,"140,95,166,134,199,180","2024-06-28 13:02:10"
    user-15,"26,171,1,190,87,32","2024-06-28 13:02:10"
   ```
The expected format of the result is `userId`, `comma separated 6 product ids` and `timestamp` of the window.

6. You can also deploy Prometheus to monitor the metrics inside job manager and task manager following steps [here](../prometheus-install/README.md).

       
