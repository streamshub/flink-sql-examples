+++
title = 'Simple User Defined Functions'
+++

> Note: This tutorial is mainly focused on creating a simple [Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/overview/) [User Defined Function (UDF)](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/). For detailed information on working with [Flink ETL Jobs](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/learn-flink/etl/) and [Session Clusters](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments), look at the [Interactive ETL example](../interactive-etl/_index.md).

[Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/overview/) is a powerful tool for data exploration, manipulation and inter-connection.
Flink SQL has many [built-in functions](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/systemfunctions/#system-built-in-functions), that allow you to extract and manipulate data from the many sources that Flink supports. 
However, sometimes you need to be able to do operations not covered by these built-in functions.
In that situation Flink gives you the option of creating your own functions.
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

However, it looks like the person who owns this schema repeated the same mistake they did for the Sales schema we looked at in the [Interactive ETL example](../interactive-etl/_index.md), and decided to once again include the currency symbol at the start of the `unit_cost` field (at least they're consistent...)!

*(Assuming you have the data generator up and running as per the instructions in the [Setup](#setup) section, you can verify this by running the following command):*

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

Because of that mistake, we currently have to deal with [Unicode currency symbols](https://www.unicode.org/charts/nameslist/n_20A0.html) in our Flink SQL queries, and are forced to make long and complex queries to convert the `unit_cost` field into a more usable format.

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

Instead, we can create a simple UDF to strip the currency symbol from the `unit_cost` field and add an equivalent [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) currency code to the end of the field instead e.g. "€192" will become "192 EUR".

We will be replacing that cumbersome to maintain query above with something simple like this:

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

First, we will create a blank Maven project:

```shell
mvn archetype:generate \
    -DgroupId=com.github.streamshub \
    -DartifactId=flink-udf-currency-converter \
    -DarchetypeArtifactId=maven-archetype-quickstart \
    -DarchetypeVersion=1.5 \
    -DinteractiveMode=false

cd ~/flink-udf-currency-converter
```

> Note: Flink provides a [Maven Archetype and quickstart script](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/overview/#getting-started) for getting started. However, it includes a lot of dependencies and boilerplate we don't need for this tutorial, so we will start with a minimal Maven project instead.

### Renaming `App` and `AppTest`

Next, we will rename the `App` and `AppTest` classes to `CurrencyConverter` and `CurrencyConverterTest` respectively. We will also rename the files accordingly and move them to a new package called `com.github.streamshub.flink.functions`:

```shell
# Rename the classes
sed -i -e 's/App/CurrencyConverter/g' src/main/java/com/github/streamshub/App.java
sed -i -e 's/AppTest/CurrencyConverterTest/g' src/test/java/com/github/streamshub/AppTest.java

# Create new package directories
mkdir -p src/main/java/com/github/streamshub/flink/functions
mkdir -p src/test/java/com/github/streamshub/flink/functions

# Move classes to the new package
mv src/main/java/com/github/streamshub/App.java src/main/java/com/github/streamshub/flink/functions/CurrencyConverter.java
mv src/test/java/com/github/streamshub/AppTest.java src/test/java/com/github/streamshub/flink/functions/CurrencyConverterTest.java

# Update package declarations
sed -i -e 's/com.github.streamshub;/com.github.streamshub.flink.functions;/' src/main/java/com/github/streamshub/flink/functions/CurrencyConverter.java
sed -i -e 's/com.github.streamshub;/com.github.streamshub.flink.functions;/' src/test/java/com/github/streamshub/flink/functions/CurrencyConverterTest.java
```

The project should still build and run successfully at this point, we can run the following commands to verify:

```shell
mvn clean package

java -cp target/flink-udf-currency-converter-1.0-SNAPSHOT.jar com.github.streamshub.flink.functions.CurrencyConverter
# Should print "Hello World!"
```

### Adding the core Flink API dependency

If we look back at the [Scenario](#scenario) section, we can see that all we want to do is map one string (e.g. "€100") into a new string (e.g. "100 EUR"). We can do this by writing a [Scalar Function](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/#scalar-functions).

To make our UDF, we will need to extend the [`ScalarFunction`](https://nightlies.apache.org/flink/flink-docs-release-2.0/api/java/org/apache/flink/table/functions/ScalarFunction.html) base class. Let's add a dependency to our `pom.xml` so we can do that:

```xml
<name>currency-converter</name>

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

We don't need any external dependencies in our JAR (apart from Flink). 
But, if we did want to add some, we would need to either [shade them into an uber/fat JAR or add them to the classpath of the distribution](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/maven/#packaging-the-application). 
If you want to do the former, the [Flink docs provide a template](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/maven/#template-for-creating-an-uberfat-jar-with-dependencies) on how to use the [Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin/index.html) to do so.

### Extending the `ScalarFunction` base class

Now that we have added the only dependency we need, we can implement our `CurrencyConverter` UDF.

Let's start by making our `CurrencyConverter` class extend the `ScalarFunction` base class. We can also remove the `main` method since we won't need it:

```java
package com.github.streamshub.flink.functions;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {}
```

This function doesn't do anything yet. For that, we need it to declare a public `eval` method.

Since we'll only be passing it one argument (the `unit_cost` field), we can declare that the method takes in a single `String` argument and also returns a `String`:

```java
package com.github.streamshub.flink.functions;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // (You can name the parameter whatever you like)
   // e.g. unicodeAmount = "€100"
   public String eval(String unicodeAmount) {
       // logic will go here
   }
}
```

Flink's [Automatic Type Inference](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/#automatic-type-inference) will use reflection to derive SQL data types for the argument and result of our UDF. 
If you want to override this behaviour, you can [explicitly specify the types]((https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/udfs/#automatic-type-inference)), but in this case we will keep it simple and let Flink decide for us.

### Implementing the logic

By speaking to authors of the upstream services, we should be able to obtain a list of currency symbols that can potentially appear in the `unit_cost` field:

> Here is list of currency symbols that can potentially appear in the `unit_cost` field:
>
> `'€', '₹', '₺', '฿', '₴', '₮'`
>
> — authors of the upstream services

We can create an `enum` that maps these symbols to their corresponding [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) currency codes.

```shell
mkdir -p src/main/java/com/github/streamshub/flink/enums

touch src/main/java/com/github/streamshub/flink/enums/Currency.java
```

As a reminder, we want to convert a string like "€100" into "100 EUR". To do this, we can use the following steps:

1. Get the first character of the string, which is the currency symbol (e.g. "€").
2. Look up the currency symbol in our `enum` to get the corresponding currency code (e.g. "€" => "EUR").
3. If the lookup failed (e.g. currency symbol was not found), we can return "ERR" as the currency code.
4. Get the rest of the string, which is the amount (e.g. "100").
5. Concatenate the currency code to the amount, and return the result (e.g. "100 EUR").

A possible implementation could look like this:

```java
package com.github.streamshub.flink.enums;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// https://www.unicode.org/charts/nameslist/n_20A0.html
// https://www.iso.org/iso-4217-currency-codes.html
public enum Currency {
    EUR("€", "EUR"),
    INR("₹", "INR"),
    TRY("₺", "TRY"),
    THB("฿", "THB"),
    UAH("₴", "UAH"),
    MNT("₮", "MNT"),
    ERR("?", "ERR");

    public static final String SEPARATOR = " ";
    private static final Map<String, Currency> SYMBOL_TO_CURRENCY = Stream.of(Currency.values())
            .collect(Collectors.toMap(Currency::getSymbol, c -> c));

    private final String symbol;
    private final String isoCode;

    Currency(String symbol, String isoCode) {
        this.symbol = symbol;
        this.isoCode = isoCode;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getIsoCode() {
        return isoCode;
    }

    public static Currency fromUnicodeAmount(String unicodeAmount) {
        String currencySymbol = unicodeAmount.substring(0, 1); // "€100" -> "€"
        try {
            return SYMBOL_TO_CURRENCY.getOrDefault(currencySymbol, ERR); // "€100" -> EUR
        } catch (Exception e) {
            return ERR; // "]100" -> ERR
        }
    }

    public String concatIsoCodeToAmount(String amount) {
        return amount + SEPARATOR + isoCode; // "100" + EUR -> "100 EUR"
    }

    public static String unicodeAmountToIsoAmount(String unicodeAmount) {
        String trimmedUnicodeAmount = unicodeAmount.trim();

        Currency currency = fromUnicodeAmount(trimmedUnicodeAmount); // "€100" -> EUR
        String amount = trimmedUnicodeAmount.substring(1); // "€100" -> "100"

        return currency.concatIsoCodeToAmount(amount); // "100" + EUR -> "100 EUR"
    }
}
```

We can then use this `enum` in the `eval` method of our UDF:

```java
package com.github.streamshub.flink.functions;

import org.apache.flink.table.functions.ScalarFunction;

import com.github.streamshub.flink.enums.Currency;

public class CurrencyConverter extends ScalarFunction {
   // e.g. unicodeAmount = "€100"
   public String eval(String unicodeAmount) {
      return Currency.unicodeAmountToIsoAmount(unicodeAmount); // "€100" -> "100 EUR"
   }
}
```

### Testing (optional)

If we want to, we can modify `CurrencyConverterTest` to verify the UDF works as expected:

```java
package com.github.streamshub.flink.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.github.streamshub.flink.enums.Currency;

public class CurrencyConverterTest {
    public static final String VALID_UNICODE_AMOUNT = " €100 ";
    public static final String VALID_ISO_AMOUNT = "100" + Currency.SEPARATOR + Currency.EUR.getIsoCode();

    public static final String INVALID_UNICODE_AMOUNT = " ]100 ";
    public static final String INVALID_ISO_AMOUNT = "100" + Currency.SEPARATOR + Currency.ERR.getIsoCode();

    @Test
    public void shouldConvertValidUnicodeAmount() throws Exception {
        CurrencyConverter currencyConverter = new CurrencyConverter();

        assertEquals(VALID_ISO_AMOUNT, currencyConverter.eval(VALID_UNICODE_AMOUNT));
    }

    @Test
    public void shouldConvertInvalidUnicodeAmount() throws Exception {
        CurrencyConverter currencyConverter = new CurrencyConverter();

        assertEquals(INVALID_ISO_AMOUNT, currencyConverter.eval(INVALID_UNICODE_AMOUNT));
    }
}
```

> Note: Since our UDF is simple and stateless, we can test its methods directly. If we had made use of managed state or timers (e.g. for watermarks) we would need to use the Flink [test harnesses](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/datastream/testing/#unit-testing-stateful-or-timely-udfs--custom-operators).

### Building the JAR

After implementing the logic, we can build our JAR:

```shell
mvn clean package
```

Assuming there are no errors, we can now try out our new UDF!

## Using the User Defined Function

### Dependencies

In order to try the UDF you will need:

- [Minikube](https://minikube.sigs.k8s.io/docs/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/#kubectl)
- [Helm](https://helm.sh/)
- [Docker](https://www.docker.com/) or [Podman](https://podman.io/)
- [Maven](https://maven.apache.org/install.html)

### Setup

> Note: If you want more information on what the steps below are doing, look at the [Interactive ETL example](../interactive-etl/_index.md) setup which is almost identical.

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

In order to use our UDF we need to create a container containing it and the Flink runtime.

First, we need to port forward the Flink Job Manager pod so the Flink SQL CLI can access it:

```shell
kubectl -n flink port-forward <job-manager-pod> 8081:8081
```

The job manager pod will have the name format `session-cluster-udf-<alphanumeric>`, your `kubectl` should tab-complete the name. 
If it doesn't then you can find the job manager name by running `kubectl -n flink get pods`.

Next, we will create a container with our JAR mounted into it:

```shell
podman run -it --rm --net=host \
    -v ~/currency-converter/target/flink-udf-currency-converter-1.0-SNAPSHOT.jar:/opt/flink-udf-currency-converter-1.0-SNAPSHOT.jar:Z \
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
AS 'com.github.streamshub.flink.functions.CurrencyConverter'
USING JAR '/opt/flink-udf-currency-converter-1.0-SNAPSHOT.jar';
```

> Note: Temporary catalog functions [only live as long as the current session](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/functions/overview/#types-of-functions). Provided you have a [Flink catalog](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/table/catalogs/#catalogs) deployed and configured, you can omit the `TEMPORARY` keyword to create a function that persists across sessions.

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

We can also use the UDF in more complex queries e.g. to filter for records with specific currencies and quantities:

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
    quantity > 1 AND RIGHT(iso_unit_cost, 3) NOT IN ('MNT', 'ERR');
```

> Note: This query might take a while to return results, since there are many currencies used in the data!

### Persisting back to Kafka

Just like in the [Interactive ETL example](../interactive-etl/_index.md), we can create a new table to persist the output of our query back to Kafka (look at that example for an explanation of the steps below). This way we don't have to run the query every time we want to access the formatted cost.

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

## Layering the UDF on top of the `flink-sql-runner` image

### Adding [docker-maven-plugin](https://github.com/fabric8io/docker-maven-plugin)

To make it easier for others to use our UDF, we can create a new container image that layers our JAR on top of the StreamsHub [`flink-sql-runner`](https://github.com/streamshub/flink-sql) image. 
This way, the function will be available to all users of the image without needing to have the jar file locally.
It also allows us to deploy a standalone Flink job, which we will discuss later.

Instead of writing the Dockerfile ourselves, we can automate this by adding the [docker-maven-plugin](https://github.com/fabric8io/docker-maven-plugin) to our `pom.xml`:

```xml
<plugin>
    <groupId>io.fabric8</groupId>
    <artifactId>docker-maven-plugin</artifactId>
    <version>0.46.0</version>
    <configuration>
        <images>
            <image>
                <name>flink-sql-runner-with-${project.artifactId}</name>
                <build>
                    <from>quay.io/streamshub/flink-sql-runner:0.2.0</from>
                    <assembly>
                        <descriptorRef>artifact</descriptorRef>
                        <targetDir>/opt</targetDir>
                    </assembly>
                </build>
            </image>
        </images>
    </configuration>
</plugin>
```

We can then build the image like this:

```shell
mvn clean package docker:build
```

> Note: docker-maven-plugin uses Docker by default, if you're using Podman [you will likely need to set `DOCKER_HOST` to use podman](https://github.com/fabric8io/docker-maven-plugin/issues/1330#issuecomment-872905283).

Finally, we can create a new container using the image we just built:

```shell
# We don't need to mount the JAR anymore!
podman run -it --rm --net=host \
    flink-sql-runner-with-flink-udf-currency-converter:latest \
        /opt/flink/bin/sql-client.sh embedded
```

You can run the same Flink SQL queries as before to verify that everything works the same way.

### Using the new UDF image in a `FlinkDeployment`

So far, we've been using the UDF in ETL queries that would have to compete for resources with other queries running in the same Flink session cluster.

Instead, like in the [Interactive ETL example](../interactive-etl/_index.md), we can create a FlinkDeployment CR for deploying our queries as a stand-alone Flink Job:

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: standalone-etl-udf
spec:
  # Change the two lines below depending on your image
  image: docker.io/library/flink-sql-runner-with-flink-udf-currency-converter:latest
  # This is set to Never when you have pushed/built the image directly in your Kubernetes cluster
  imagePullPolicy: Never
  flinkVersion: v2_0
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "1"
  serviceAccount: flink
  jobManager:
    resource:
      memory: "2048m"
      cpu: 1
  taskManager:
    resource:
      memory: "2048m"
      cpu: 1
  job:
    jarURI: local:///opt/streamshub/flink-sql-runner.jar
    args: ["
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
        CREATE FUNCTION currency_convert
        AS 'com.github.streamshub.flink.functions.CurrencyConverter'
        USING JAR '/opt/flink-udf-currency-converter-1.0-SNAPSHOT.jar';
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
            'topic' = 'flink.iso.international.sales.records', 
            'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092', 
            'properties.client.id' = 'sql-cleaning-client', 
            'properties.transaction.timeout.ms' = '800000', 
            'key.format' = 'csv', 
            'value.format' = 'csv', 
            'value.fields-include' = 'ALL' 
        );
        INSERT INTO IsoInternationalSalesRecordTable
        SELECT 
            invoice_id, 
            user_id,
            product_id,
            CAST(quantity AS INT), 
            currency_convert(unit_cost), 
            purchase_time
        FROM InternationalSalesRecordTable;
        "]
    parallelism: 1
    upgradeMode: stateless
```

Then use it:

```shell
# If using minikube and a local image, load the image first:
minikube image load flink-sql-runner-with-flink-udf-currency-converter

kubectl apply -n flink -f <path-to-flink-deployment>.yaml
```

> Note: We can also just use the provided example FlinkDeployment CR instead (which uses the image built from the example code in this repository):
>
> ```shell
> kubectl apply -n flink -f tutorials/user-defined-functions/standalone-etl-udf-deployment.yaml
> ```

Finally, we can verify that data is being written to the new topic, just like before:

```shell
kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
    ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic flink.iso.international.sales.records
```
