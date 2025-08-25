+++
title = 'Connecting to Kafka securely using Flink SQL'
+++

> Note: TLS and `KafkaUser` always included for recommendation-app to work.

## PLAINTEXT

No encryption, no authentication.

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

## TLS

One-way, server side only, no authentication. Use cluster-ca-cert directly / add to own truststore?

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
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9093',
    'properties.security.protocol' = 'SSL',
    'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
    'properties.ssl.truststore.type' = 'PEM',
    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

## mTLS

> Note: you can remove the truststore details and it will still work.

Get `user.password` from `my-user` secret.

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
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
    'properties.security.protocol' = 'SSL',
    'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
    'properties.ssl.truststore.type' = 'PEM',
    'properties.ssl.keystore.location' = '/opt/my-user/user.p12',
    'properties.ssl.keystore.password' = 'HoDYta38YJizRcL8cd8hhQqRAxq68gKe', -- REPLACE THIS
    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

### Authentication only

xx

### Authentication and Authorization

xx

### SCRAM-SHA-512

Get `sasl.jaas.config` from `my-user` secret and change `ScramLoginModule` path to shaded location.

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
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
    'properties.security.protocol' = 'SASL_SSL',
    'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
    'properties.ssl.truststore.type' = 'PEM',
    'properties.sasl.mechanism' = 'SCRAM-SHA-512',
    'properties.sasl.jaas.config' = 'org.apache.flink.kafka.shaded.org.apache.kafka.common.security.scram.ScramLoginModule
        required
            username="my-user"
            password="FOb3lXmnzN3fuqgXteZhuIWfP4nOKiAw" -- REPLACE THIS
        ;',
    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

### OAuth 2.0

https://github.com/strimzi/strimzi-kafka-operator/blob/main/examples/security/keycloak-authorization/kafka-authz-realm.json

https://github.com/apache/flink-connector-kafka/blob/ecedd632bbf86d333f376374d4674e79e2f614b8/flink-sql-connector-kafka/pom.xml#L68-L108

Need to add strimzi callbacks to sql-runner, needs shading, relocation, and patches.

keycloak user: admin
keycloak pass: admin

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
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
    'properties.security.protocol' = 'SASL_SSL',
    'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
    'properties.ssl.truststore.type' = 'PEM',
    'properties.sasl.mechanism' = 'OAUTHBEARER',
    'properties.sasl.jaas.config' = 'org.apache.flink.kafka.shaded.org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule
        required
            oauth.ssl.truststore.location="/opt/keycloak-ca-cert/ca.crt"
            oauth.ssl.truststore.type="PEM"
            oauth.client.id="kafka"
            oauth.client.secret="kafka-secret"
            oauth.token.endpoint.uri="https://keycloak.flink.svc:8443/realms/kafka-authz/protocol/openid-connect/token"
        ;',
    'properties.sasl.login.callback.handler.class' = 'io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler',
    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

## Custom

Get `user.password` from `my-user` secret.

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
    'properties.bootstrap.servers' = 'my-cluster-kafka-bootstrap.flink.svc:9094',
    'properties.security.protocol' = 'SSL',
    'properties.ssl.truststore.location' = '/opt/my-cluster-cluster-ca-cert/ca.crt',
    'properties.ssl.truststore.type' = 'PEM',
    'properties.ssl.keystore.location' = '/opt/my-user-custom-cert/keystore.p12',
    'properties.ssl.keystore.password' = '123', -- REPLACE THIS
    'properties.group.id' = 'sales-record-group',
    'value.format' = 'avro-confluent',
    'value.avro-confluent.url' = 'http://apicurio-registry-service.flink.svc:8080/apis/ccompat/v6',
    'scan.startup.mode' = 'latest-offset'
);
```

## Conclusion / Differences / Comparison Table

