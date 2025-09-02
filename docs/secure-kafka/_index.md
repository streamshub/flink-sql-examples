+++
title = 'Connecting to Kafka securely using Flink SQL'
+++

> Note: This tutorial is mainly focused on securing connections between Flink SQL and Kafka.
> For detailed information on working with [Flink ETL Jobs](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/learn-flink/etl/)
> and [Session Clusters](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments),
> look at the [Interactive ETL example](../interactive-etl/_index.md).

[Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/dev/table/overview/) is a powerful tool for data exploration, manipulation and inter-connection.
It allows you to access the power of Flink's distributed stream processing abilities with a familiar interface.
In this tutorial, we go over ways to securely connect to Kafka from Flink SQL.

The tutorial is based on the StreamsHub [Flink SQL Examples](https://github.com/streamshub/flink-sql-examples) repository and the code can be found under the [`tutorials/secure-kafka`](https://github.com/streamshub/flink-sql-examples/tree/main/tutorials/secure-kafka) directory.

> Note:
> - This tutorial only covers authentication, not authorization. 
> - All the commands below are meant to be run from the `tutorials` directory.
> - The Flink SQL commands assume the query is being run on our Flink distribution.
>   - `quay.io/streamshub/flink-sql-runner`
>     - Includes Strimzi's OAuth 2.0 callback handler.
>     - Shades Flink Kafka dependencies.
> - Only relevant lines are included in the code blocks.
>   - The `tutorials/secure-kafka` directory contains the complete files.
> - For greater detail on what is covered in this tutorial, you can read the following:
>   - [“Security” section in the Apache Kafka SQL Connector documentation](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/connectors/table/kafka/#security)
>   - ["Securing access to a Kafka cluster" in the Strimzi documentation](https://strimzi.io/docs/operators/latest/deploying#assembly-securing-access-str)
>   - [“Security” section in the Apache Kafka documentation](https://kafka.apache.org/documentation/#security)

## Unsecure

### PLAINTEXT

- No encryption.
- No authentication.

```shell
# Note: PLAINTEXT is the default option if you don't pass SECURE_KAFKA.
# Note: This sets up Kafka, Flink, recommendation-app (generates example data), etc.
SECURE_KAFKA=PLAINTEXT ./scripts/data-gen-setup.sh

# This creates a standalone Flink job
kubectl -n flink apply -f recommendation-app/flink-deployment.yaml
```

The commands above apply the following:

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false      # Plain listener with no encryption
```

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
    
    -- The line below is the default
    -- 'properties.security.protocol' = 'PLAINTEXT',
    
    -- Point to our plain listener
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092',
    
    'properties.group.id' = 'sales-record-group', 
    'value.format' = 'avro-confluent', 
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6', 
    'scan.startup.mode' = 'latest-offset'
); 
```

You can verify that the test data is flowing correctly by querying the Kafka topic using the console consumer:

```shell
kubectl exec -it my-cluster-dual-role-0 -n flink -- /bin/bash \
./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flink.sales.records
```

## Secure

> Note:
> - A TLS listener is always included to allow the `recommendation-app` to send example data.
> - Each example code block is `diff`ed against the PLAINTEXT example above.
>   - `KafkaUser` is `diff`ed against the mTLS example.

### Testing (preamble)

You can verify the different authentication methods below work by doing the following:

1. Run the commands in the example, as instructed.

2. Port forward the Flink Job Manager pod so we can access it:

    ```shell
    kubectl -n flink port-forward <job-manager-pod> 8081:8081
    ```
    
    The job manager pod will have the name format `standalone-etl-secure-<alphanumeric>`, your `kubectl` should tab-complete the name.
    If it doesn't then you can find the job manager name by running `kubectl -n flink get pods`.

3. Navigate to `http://localhost:8081` to view the Flink dashboard:

    ![jobs](./assets/jobs.png)

4. Click on the `standalone-etl-secure` job and verify it has sent `10` records to a topic.

    ![job](./assets/job.png)


### TLS

- Encrypted using TLS (e.g. `TLSv1.3` and `TLS_AES_256_GCM_SHA384`).
- Server is authenticated by client.

```shell
SECURE_KAFKA=OAuth2 ./scripts/data-gen-setup.sh

kubectl -n flink apply -f secure-kafka/TLS/standalone-etl-secure-deployment.yaml
```

The commands above apply the following:

```diff
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    listeners:
-     - name: plain
-       port: 9092
-       type: internal
-       tls: false

+     - name: tls
+       port: 9093
+       type: internal
+       tls: true       # Enable TLS encryption on listener
```

```diff
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
 
-   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092',

+     -- Change to secure listener
+   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9093',

+     -- Connect over SSL
+   'properties.security.protocol' = 'SSL',

+     -- Provide path to mounted secret containing the
+     -- Kafka cluster CA certificate generated by Strimzi
+   'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',

+     -- Indicate certificate is of type PEM (as opposed to JKS or PKCS12)
+   'properties.ssl.truststore.type' = 'PEM',

    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

### mTLS

- Encrypted using TLS (e.g. `TLSv1.3` and `TLS_AES_256_GCM_SHA384`).
- Both server and client are authenticated.

```shell
SECURE_KAFKA=mTLS ./scripts/data-gen-setup.sh

kubectl -n flink apply -f secure-kafka/mTLS/standalone-etl-secure-deployment.yaml
```

The commands above apply the following:

```diff
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    listeners:
-     - name: plain
-       port: 9092
-       type: internal
-       tls: false

+     - name: mtls
+       port: 9094
+       type: internal
+       tls: true
+       authentication:
+         type: tls     # Enable TLS client authentication
```

```yaml
# KafkaUser added for client authnetication examples,
# not necessary for Kafka listeners without
# 'authentication' property.
apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaUser
metadata:
  name: my-user
  labels:
    strimzi.io/cluster: my-cluster
spec:
  authentication:
    type: tls       # This will generate a 'my-user' Secret containing credentials
```

```diff
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
-   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092',
+   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
+   'properties.security.protocol' = 'SSL',
+   'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
+   'properties.ssl.truststore.type' = 'PEM',

+     -- Provide path to mounted 'my-user' secret containing
+     -- the keystore generated by the Strimzi User Operator
+     -- for the 'my-user' KafkaUser
+   'properties.ssl.keystore.location' = '/opt/my-user/user.p12',

+     -- Provide `user.password` from the generated `my-user` secret.
+   'properties.ssl.keystore.password' = 'xxxxxxx',

    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

### SCRAM-SHA-512

```shell
SECURE_KAFKA=SCRAM ./scripts/data-gen-setup.sh

kubectl -n flink apply -f secure-kafka/SCRAM/standalone-etl-secure-deployment.yaml
```

The commands above apply the following:

```diff
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    listeners:
-     - name: plain
-       port: 9092
-       type: internal
-       tls: false

+     - name: scram
+       port: 9094
+       type: internal
+       tls: true
+       authentication:
+         type: scram-sha-512   # Specify SCRAM authentication
```

```diff
apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaUser
metadata:
  name: my-user
  labels:
    strimzi.io/cluster: my-cluster
spec:
- authentication:
-   type: tls
+ authentication:
+   type: scram-sha-512     # Specify SCRAM authentication
```

```diff
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
-   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092',
+   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
+   'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
+   'properties.ssl.truststore.type' = 'PEM',

+     -- Connect over SASL_SSL, this allows us to specify a SASL mechanism
+   'properties.security.protocol' = 'SASL_SSL',

+     -- Connect using SCRAM mechanism
+   'properties.sasl.mechanism' = 'SCRAM-SHA-512',

+     -- Connect using Kafka's ScramLoginModule
+     -- Provide `user.password` from the generated `my-user` secret.
+   'properties.sasl.jaas.config' = 'org.apache.flink.kafka.shaded.org.apache.kafka.common.security.scram.ScramLoginModule
+       required
+           username="my-user"
+           password="xxxxxxx"
+       ;',

    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

### OAuth 2.0

> Note:
> - This example uses the Keycloak config and realm from the
> [`strimzi-kafka-operator` Keycloak example](https://github.com/strimzi/strimzi-kafka-operator/blob/main/examples/security/keycloak-authorization/kafka-authz-realm.json).
>   - Username: `admin`
>   - Password: `admin`

```shell
# Note: For OAuth 2.0, Keycloak and a self-signed
# TLS certificate for Keycloak are automatically created.
SECURE_KAFKA=OAuth2 ./scripts/data-gen-setup.sh

kubectl -n flink apply -f secure-kafka/OAuth2/standalone-etl-secure-deployment.yaml
```

The commands above apply the following:

```diff
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    listeners:
-     - name: plain
-       port: 9092
-       type: internal
-       tls: false

+     - name: oauth2
+       port: 9094
+       type: internal
+       tls: true
+       authentication:
+         type: oauth       # Specify OAuth 2.0 authentication

+         # Specify OAuth 2.0 JWKS/JWT details
+         validIssuerUri: https://keycloak.flink.svc:8443/realms/kafka-authz
+         jwksEndpointUri: https://keycloak.flink.svc:8443/realms/kafka-authz/protocol/openid-connect/certs
+         userNameClaim: preferred_username

+         # Trust self-signed TLS certificate used by Keycloak HTTPS endpoint
+         tlsTrustedCertificates:
+           - secretName: keycloak-cert
+             certificate: tls.crt
```

```diff
# This KafkaUser is only needed if the Kafka listener is using simple authorization
apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaUser
metadata:
- name: my-user
+ name: service-account-kafka   # Create KafkaUser for user in the Keycloak realm
  labels:
    strimzi.io/cluster: my-cluster
spec:
- authentication:               # Remove authentication
-   type: tls
```

```diff
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
-   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092',
+   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
+   'properties.security.protocol' = 'SASL_SSL',
+   'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
+   'properties.ssl.truststore.type' = 'PEM',

+     -- Connect using OAUTHBEARER mechanism (OAuth 2.0 with Bearer token)
+   'properties.sasl.mechanism' = 'OAUTHBEARER',

+     -- Connect using Kafka's OAuthBearerLoginModule
+     -- Provide path to mounted secret containing Keycloak's self-signed TLS certificate
+     -- Provide OAuth 2.0 client and endpoint details for/from Keycloak realm
+   'properties.sasl.jaas.config' = 'org.apache.flink.kafka.shaded.org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule
+       required
+           oauth.ssl.truststore.location="/opt/keycloak-ca-cert/ca.crt"
+           oauth.ssl.truststore.type="PEM"
+           oauth.client.id="kafka"
+           oauth.client.secret="kafka-secret"
+           oauth.token.endpoint.uri="https://keycloak.flink.svc:8443/realms/kafka-authz/protocol/openid-connect/token"
+       ;',

+     -- Use Strimzi's OAuth 2.0 callback handler
+   'properties.sasl.login.callback.handler.class' = 'io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler',

    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

### Custom

> Note: Custom authentication allows wide flexibility in how authentication is carried out.
> 
> For the sake of simplicity, this example shows how to use a custom TLS client authentication truststore. 

```shell
# Note: For custom authentication, a self-signed TLS truststore
# and 'my-user-custom-cert' TLS certificate is created
SECURE_KAFKA=custom ./scripts/data-gen-setup.sh

kubectl -n flink apply -f secure-kafka/custom/standalone-etl-secure-deployment.yaml
```

The commands above apply the following:

```diff
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    listeners:
-     - name: plain
-       port: 9092
-       type: internal
-       tls: false

+     - name: custom
+       port: 9094
+       type: internal
+       tls: true
+       authentication:
+         type: custom      # Specify custom authentication
+         sasl: false       # Disable SASL since it is unnecessary
+         listenerConfig:
+           ssl.client.auth: required       # Require TLS client authentication

+             # Provide path to self-signed client TLS truststore
+           ssl.truststore.location: /mnt/my-user-custom-cert/ca.crt
+           ssl.truststore.type: PEM
```

```diff
apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaUser
metadata:
  name: my-user
  labels:
    strimzi.io/cluster: my-cluster
spec:
- authentication:
-   type: tls
+ authentication:
+   type: tls-external  # Specify external TLS authentication
+                       # (we use our own self-signed certificate)
```

```diff
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
-   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9092',
+   'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
+   'properties.security.protocol' = 'SSL',
+   'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
+   'properties.ssl.truststore.type' = 'PEM',

+     -- Provide path to mounted secret containing our user's self-signed keystore
+   'properties.ssl.keystore.location' = '/opt/my-user-custom-cert/keystore.p12',

+     -- Provide 'password' from 'my-user-custom-cert-password' secret
+   'properties.ssl.keystore.password' = 'xxxxxxx',

    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```
