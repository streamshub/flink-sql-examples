+++
title = 'Simple User Defined Functions'
+++

> Note: This tutorial is mainly focused on creating a simple [Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/overview/) [User Defined Function (UDF)](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/). For detailed information on working with [Flink ETL Jobs](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/learn-flink/etl/) and [Session Clusters](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments), look at the [Interactive ETL example](../interactive-etl/index.md).

[Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/overview/) is a powerful tool for data exploration, manipulation and inter-connection.
It allows you to access the power of Flink's distributed stream processing abilities with a familiar interface.
In this tutorial we show how to write a simple [User Defined Function (UDF)](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/) in Java and use it to manipulate data in a Flink SQL query running on a [Flink session cluster](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments).

The tutorial is based on the StreamsHub [Flink SQL Examples](https://github.com/streamshub/flink-sql-examples) repository and the code can be found under the `tutorials/user-defined-functions` and `tutorials/currency-converter` directories.

## Scenario

### Source Data Table

The data generator application creates a topic (`flink.international.sales.records`) containing international sales records.
The schema for this topic can be seen in the `data-generator/src/main/resources/internationalSales.avsc`:

```avroschema
{
  "namespace": "com.github.streamshub.kafka.data.generator.schema",
  "type": "record",
  "name": "InternationalSales",
  "fields": [
    {"name": "user_id", "type": "string"},
    {"name": "product_id",  "type": "string"},
    {"name": "invoice_id",  "type": "string"},
    {"name": "quantity",  "type": "string"},
    {"name": "unit_cost",  "type": "string"}
  ]
}
```

However, it looks like the person who owns this schema repeated the same mistake he did for the Sales schema we looked at in the [Interactive ETL example](../interactive-etl/index.md), and decided to once again include the currency symbol at the start of the `unit_cost` field! (at least he's consistent...).

```shell
$ kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic flink.international.sales.records

user-82130&53972644729678620433
                               ₹192
user-1619&74772726277194883031
                              ฿638
...
```

Instead of having to deal with [Unicode currency symbols](https://www.unicode.org/charts/nameslist/n_20A0.html) in our Flink SQL queries, we can create a simple UDF to strip the currency symbol from the `unit_cost` field and add an equivalent [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) currency code to the end of the field instead e.g. "€192" will become "192 EUR".

We will basically be replacing a cumbersome to maintain query e.g.:

```sql
// Think about how complex this query would become if the unit_cost field
// required more complex parsing logic
SELECT 
    invoice_id,
    CASE
        WHEN LEFT(unit_cost, 1) = '€' THEN CONCAT(SUBSTRING(unit_cost, 2), ' EUR')
        WHEN LEFT(unit_cost, 1) = '₹' THEN CONCAT(SUBSTRING(unit_cost, 2), ' INR')
        WHEN LEFT(unit_cost, 1) = '₺' THEN CONCAT(SUBSTRING(unit_cost, 2), ' TRY')
        WHEN LEFT(unit_cost, 1) = '฿' THEN CONCAT(SUBSTRING(unit_cost, 2), ' THB')
        WHEN LEFT(unit_cost, 1) = '₴' THEN CONCAT(SUBSTRING(unit_cost, 2), ' UAH')
        WHEN LEFT(unit_cost, 1) = '₮' THEN CONCAT(SUBSTRING(unit_cost, 2), ' MNT')
        // ... imagine many more currency symbols here ...
        ELSE CONCAT(SUBSTRING(unit_cost, 2), ' ???')
    END AS iso_unit_cost,
    unit_cost,
    quantity
FROM InternationalSalesRecordTable;
```

with this:

```sql
SELECT
    invoice_id,
    currency_convert(unit_cost) AS iso_unit_cost,
    unit_cost,
    quantity
FROM InternationalSalesRecordTable;
```

## Creating the User Defined Function

> Note: You can find the full completed code for the UDF in the `tutorials/currency-converter` directory.

### Creating the Maven project

First, we will create a Maven project in our home directory, and `cd` into it:

```shell
cd ~

mvn archetype:generate \
    -DgroupId=com.github.example \
    -DartifactId=currency-converter \
    -DarchetypeArtifactId=maven-archetype-quickstart \
    -DarchetypeVersion=1.5 \
    -DinteractiveMode=false

cd ~/currency-converter
```

> Note: Flink provides a [Maven Archetype and quickstart script](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/overview/#getting-started) for getting started. However, it includes a lot of dependencies and boilerplate we don't need for this tutorial, so we will start with a minimal Maven project instead.

We can remove the provided tests and their dependencies, as we don't need them for this tutorial:

```shell
rm -r src/test

sed -i -e '/<dependencyManagement>/,/<\/dependencyManagement>/d' pom.xml

sed -i -e '/<dependencies>/,/<\/dependencies>/d' pom.xml
```

Your `pom.xml` should now look something like this:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.github.example</groupId>
  <artifactId>currency-converter</artifactId>
  <version>1.0-SNAPSHOT</version>

  <name>currency-converter</name>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>17</maven.compiler.release>
  </properties>

  <build>
    <pluginManagement><!-- lock down plugins versions to avoid using Maven defaults (may be moved to parent pom) -->
      <plugins>
        <!-- clean lifecycle, see https://maven.apache.org/ref/current/maven-core/lifecycles.html#clean_Lifecycle -->
        <plugin>
          <artifactId>maven-clean-plugin</artifactId>
          <version>3.4.0</version>
        </plugin>
        <!-- default lifecycle, jar packaging: see https://maven.apache.org/ref/current/maven-core/default-bindings.html#Plugin_bindings_for_jar_packaging -->
        <plugin>
          <artifactId>maven-resources-plugin</artifactId>
          <version>3.3.1</version>
        </plugin>
        <plugin>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version>
        </plugin>
        <plugin>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.3.0</version>
        </plugin>
        <plugin>
          <artifactId>maven-jar-plugin</artifactId>
          <version>3.4.2</version>
        </plugin>
        <plugin>
          <artifactId>maven-install-plugin</artifactId>
          <version>3.1.2</version>
        </plugin>
        <plugin>
          <artifactId>maven-deploy-plugin</artifactId>
          <version>3.1.2</version>
        </plugin>
        <!-- site lifecycle, see https://maven.apache.org/ref/current/maven-core/lifecycles.html#site_Lifecycle -->
        <plugin>
          <artifactId>maven-site-plugin</artifactId>
          <version>3.12.1</version>
        </plugin>
        <plugin>
          <artifactId>maven-project-info-reports-plugin</artifactId>
          <version>3.6.1</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

### Renaming the `App` class

Next, we will rename the `App` class to `CurrencyConverter` and rename the file accordingly:

```shell
sed -i -e 's/App/CurrencyConverter/g' src/main/java/com/github/example/App.java

mv src/main/java/com/github/example/App.java src/main/java/com/github/example/CurrencyConverter.java
```

The project should still build and run successfully at this point, we can run the following commands to verify:

```shell
mvn clean package

java -cp target/currency-converter-1.0-SNAPSHOT.jar com.github.example.CurrencyConverter
# Should print "Hello World!"
```

### Adding the core Flink API dependency

If we look back at the [Scenario](#scenario) section, we can see that all we want to do is map one string (e.g. "€100") into a new string (e.g. "100 EUR"). We can do this by writing a [Scalar Function](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/#scalar-functions).

To make our UDF, we will need to extend the [`ScalarFunction`](https://nightlies.apache.org/flink/flink-docs-release-2.0/api/java/org/apache/flink/table/functions/ScalarFunction.html) base class. Let's add a dependency to our `pom.xml` so we can do that:

```xml
<name>currency-converter</name>

<!-- This is the only dependency we need -->
<dependencies>
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-table-api-java</artifactId>
        <version>2.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

> Note: Notice how we should specify the `provided` scope, in order to exclude the dependency from our JAR. We should to do this for any core Flink API dependencies we add. Otherwise, the core Flink API dependencies in our JAR [could clash with some of our other dependency versions](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/maven/#adding-dependencies-to-the-project).

We don't need any external dependencies in our JAR (apart from Flink). But, if we did want to add some, we would need to either [shade them into an uber/fat JAR or add them to the classpath of the distribution](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/maven/#packaging-the-application). If you want to do the former, the [Flink docs provide a template](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/maven/#template-for-creating-an-uberfat-jar-with-dependencies) on how to use the [Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin/index.html) to do so.

### Extending the `ScalarFunction` base class

Now that we have added the only dependency we need, we can implement our `CurrencyConverter` UDF.

Let's start by making our `CurrencyConverter` class extend the `ScalarFunction` base class. We can also remove the `main` method since we won't need it:

```java
// ~/currency-converter/src/main/java/com/github/example/CurrencyConverter.java
package com.github.example;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {}
```

This function doesn't do anything yet. For that, we need it to declare a public `eval` method.

Since we'll only be passing it one argument (the `unit_cost` field), we can declare that the method takes in a single `String` argument and also returns a `String`:

```java
package com.github.example;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // (You can name the parameter whatever you like)
   // e.g. currencyAmount = "€100"
   public String eval(String currencyAmount) {
       // logic will go here
   }
}
```

Flink's [Automatic Type Inference](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/#automatic-type-inference) will use reflection to derive SQL data types for the argument and result of our UDF. If you want to override this behavior, you can [explicitly specify the types]((https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/#automatic-type-inference)), but in this case we will keep it simple and let Flink decide for us.

If we look at the [Data Generator](https://github.com/streamshub/flink-sql-examples/blob/main/data-generator/src/main/java/com/github/streamshub/kafka/data/generator/examples/InternationalSalesData.java) in the StreamsHub [Flink SQL Examples](https://github.com/streamshub/flink-sql-examples) repository, we can see the possible currency symbols that can appear in the `unit_cost` field:

```java
public class InternationalSalesData implements Data {
    private static final char[] CURRENCY_SYMBOLS = {'€', '₹', '₺', '฿', '₴', '₮'};
    // ... other fields and methods
}
```

In our UDF, we can create a `Map` of these symbols to their corresponding [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) currency codes. We will use these when converting the `unit_cost` field into our desired format.

```java
package com.github.example;

import java.util.Map;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // https://www.unicode.org/charts/nameslist/n_20A0.html
   // https://www.iso.org/iso-4217-currency-codes.html
   private static final Map<Character, String> CURRENCY_SYMBOL_ISO_MAP = Map.of(
      '€', "EUR",
      '₹', "INR",
      '₺', "TRY",
      '฿', "THB",
      '₴', "UAH",
      '₮', "MNT"
   );

   // e.g. currencyAmount = "€100"
   public String eval(String currencyAmount) {
       // logic will go here
   }
}
```

### Implementing the function logic

Now, we can begin implementing our function logic in the `eval` method.

As a reminder, we want to convert a string like "€100" into "100 EUR". To do this, we can use the following steps:

1. Get the first character of the string, which is the currency symbol (e.g. '€').
2. Get the rest of the string, which is the amount (e.g. "100").
3. Look up the currency symbol in our `Map` to get the corresponding currency code (e.g. '€' => "EUR").
4. If the lookup returned `null` (currency symbol was not found in the `Map`), we can return "???" as the currency code.
5. Concatenate the currency code to the amount, and return the result (e.g. "100 EUR").

A possible implementation could look like this:

```java
package com.github.example;

import java.util.Map;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // https://www.unicode.org/charts/nameslist/n_20A0.html
   // https://www.iso.org/iso-4217-currency-codes.html
   private static final Map<Character, String> CURRENCY_SYMBOL_ISO_MAP = Map.of(
      '€', "EUR",
      '₹', "INR",
      '₺', "TRY",
      '฿', "THB",
      '₴', "UAH",
      '₮', "MNT"
   );

   // Value of passed field (e.g. "unit_cost") is passed in e.g. "€100"
   public String eval(String currencyAmount) {
      // 1. Get the first character of the string, which is the currency symbol (e.g. '€').
      char currencySymbol = currencyAmount.charAt(0);

      // 2. Get the rest of the string, which is the amount (e.g. "100").
      String amount = currencyAmount.substring(1);

      // 3. Look up the currency symbol in our Map to get the corresponding currency code (e.g. '€' => "EUR").
      String currencyIsoCode = CURRENCY_SYMBOL_ISO_MAP.get(currencySymbol);

      // 4. If the currency symbol is not found in the Map, we can return "???" as the currency code.
      if (currencyIsoCode == null) {
         currencyIsoCode = "???";
      }

      // 5. Concatenate the currency code to the amount, and return the result (e.g. "100 EUR").
      return amount + " " + currencyIsoCode;
   }
}
```

### Building the JAR

After implementing the logic, we can build our JAR:

```shell
mvn clean package
```

There should be no compilation errors, and there should be a JAR in the `target` directory.

```shell
$ ls -lh ~/currency-converter/target/currency-converter-1.0-SNAPSHOT.jar
-rw-r--r--. 1 <user> <user> 3.6K Jun 11 16:23 /home/<user>/currency-converter/target/currency-converter-1.0-SNAPSHOT.jar
```

If you want be extra sure everything is there, you can check if the `CurrencyConverter` class is in the JAR and if running the JAR fails as expected:

```shell
$ jar tf ~/currency-converter/target/currency-converter-1.0-SNAPSHOT.jar | grep "CurrencyConverter"
com/github/example/CurrencyConverter.class

$ java -cp ~/currency-converter/target/currency-converter-1.0-SNAPSHOT.jar com.github.example.CurrencyConverter
Error: Could not find or load main class com.github.example.CurrencyConverter
Caused by: java.lang.NoClassDefFoundError: org/apache/flink/table/functions/ScalarFunction
```

We can now try out our new UDF!

## Using the User Defined Function

### Dependencies

In order to try the UDF you will need:

- [Minikube](https://minikube.sigs.k8s.io/docs/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/#kubectl)
- [Helm](https://helm.sh/)
- [Docker](https://www.docker.com/) or [Podman](https://podman.io/)
- [Maven](https://maven.apache.org/install.html)

### Setup

> Note: If you want more information on what the steps below are doing, look at the [Interactive ETL example](../interactive-etl/index.md) setup which is almost identical.

1. Spin up a [minikube](https://minikube.sigs.k8s.io/docs/) cluster:

    ```shell
    minikube start --cpus 4 --memory 16G
    ```

2. From the main `tutorials` directory, run the data generator setup script:

    ```shell
    ./scripts/data-gen-setup.sh
    ```

3. (Optional) Verify that the test data is flowing correctly (wait a few seconds for messages to start flowing):

    ```shell
    kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.international.sales.records
    ```

4. Deploy a [Flink session cluster](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments):

    ```shell
    kubectl -n flink apply -f user-defined-functions/flink-session-udf.yaml
    ```

### Running the UDF

Since we want to try the UDF in different scenarios, we will create a container containing the Flink SQL CLI to run our queries (see the [Interactive ETL example](../interactive-etl/index.md) for details on the CLI).

First, we need to port forward the Flink Job Manager pod so the Flink SQL CLI can access it:

```shell
kubectl -n flink port-forward <job-manager-pod> 8081:8081
```

The job manager pod will have the name format `session-cluster-udf-<alphanumeric>`, your `kubectl` should tab-complete the name. 
If it doesn't then you can find the job manager name by running `kubectl -n flink get pods`.

Next, we will create a container with our JAR mounted into it:

```shell
podman run -it --rm --net=host \
    -v ~/currency-converter/target/currency-converter-1.0-SNAPSHOT.jar:/opt/currency-converter-1.0-SNAPSHOT.jar:Z \
    quay.io/streamshub/flink-sql-runner:0.2.0 \
        /opt/flink/bin/sql-client.sh embedded
```

> Note: Flink [ships optional dependencies in `/opt`](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/advanced/#anatomy-of-the-flink-distribution), so that's a good place to mount our JAR.

> Note: Don't forget the `:Z` at the end of the volume mount if using a system with SELinux! Otherwise, you will get a permission error when trying to use the JAR later.

Once we're in the Flink SQL CLI, we will first create a table for the generated international sales records:

```sql
CREATE TABLE InternationalSalesRecordTable ( 
    invoice_id STRING, 
    user_id STRING, 
    product_id STRING, 
    quantity STRING, 
    unit_cost STRING, 
    `purchase_time` TIMESTAMP(3) METADATA FROM 'timestamp', 
    WATERMARK FOR purchase_time AS purchase_time - INTERVAL '1' SECOND 
) WITH ( 
    'connector' = 'kafka',
    'topic' = 'flink.international.sales.records', 
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092', 
    'properties.group.id' = 'international-sales-record-group', 
    'value.format' = 'avro-confluent', 
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6', 
    'scan.startup.mode' = 'latest-offset'
);
```

We can do a simple query to verify that the table was created correctly and that the data is flowing (give it a few seconds to start receiving data):

```sql
SELECT * FROM InternationalSalesRecordTable;
```

If that worked, we can now register our UDF as a [temporary catalog function](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/sql/create/#create-function):

```sql
CREATE TEMPORARY FUNCTION currency_convert
AS 'com.github.example.CurrencyConverter'
USING JAR '/opt/currency-converter-1.0-SNAPSHOT.jar';
```

> Note: Temporary catalog functions [only live as long as the current session](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/overview/#types-of-functions). You can omit the `TEMPORARY` keyword to create a catalog function that persists across sessions.

> Note: This statement may succeed even if the JAR was not found or has insufficient permissions. You will likely only find this out when you try to use the UDF in a query.

Now, we can use our UDF in a query:

```sql
SELECT
    invoice_id,
    currency_convert(unit_cost) AS iso_unit_cost,
    unit_cost,
    quantity
FROM InternationalSalesRecordTable;
```

You should start seeing results with both a `unit_cost` field and an `iso_unit_cost` field containing the output of our UDF!

We can also use the UDF in more complex queries e.g. to filter for records with a specific currency and quantity:

```sql
SELECT 
    DISTINCT(product_id), 
    iso_unit_cost, 
    quantity 
  FROM (
      SELECT 
          invoice_id, 
          user_id,
          product_id,
          CAST(quantity AS INT) AS quantity, 
          currency_convert(unit_cost) AS iso_unit_cost,
          unit_cost,
          purchase_time
      FROM InternationalSalesRecordTable
    )
WHERE 
    RIGHT(iso_unit_cost, 3) = 'EUR' AND quantity > 1;
```

> Note: This query might take a while to return results, since there are many currencies used in the data!

### Persisting back to Kafka

Just like in the [Interactive ETL example](../interactive-etl/index.md), we can create a new table to persist the output of our query back to Kafka (look at that example for an explanation of the steps below). This way we don't have to run the query every time we want to access the formatted cost.

First, let's define the table, and specify `csv` as the format so we don't have to provide a schema:

```sql
CREATE TABLE IsoInternationalSalesRecordTable ( 
    invoice_id STRING, 
    user_id STRING, 
    product_id STRING, 
    quantity INT, 
    iso_unit_cost STRING, 
    purchase_time TIMESTAMP(3),
    PRIMARY KEY (`user_id`) NOT ENFORCED
) WITH ( 
    'connector' = 'upsert-kafka', 
    'topic' = 'flink.iso.international.sales.records.interactive', 
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092', 
    'properties.client.id' = 'sql-cleaning-client', 
    'properties.transaction.timeout.ms' = '800000', 
    'key.format' = 'csv', 
    'value.format' = 'csv', 
    'value.fields-include' = 'ALL' 
);
```

Next, let's insert the results of the formatting query into it:

```sql
INSERT INTO IsoInternationalSalesRecordTable
SELECT 
    invoice_id, 
    user_id,
    product_id,
    CAST(quantity AS INT), 
    currency_convert(unit_cost), 
    purchase_time
FROM InternationalSalesRecordTable;
```

Finally, we can verify the data is being written to the new topic by running the following command in a new terminal:

```shell
$ kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
  ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic flink.iso.international.sales.records.interactive

5688844959819606179,user-96,0,1,"448 INR","2025-06-13 11:28:29.722"
7208742491425008088,user-87,106,3,"587 UAH","2025-06-13 11:28:32.725"
8796404564173987612,user-70,105,1,"399 EUR","2025-06-13 11:28:35.728"
```
