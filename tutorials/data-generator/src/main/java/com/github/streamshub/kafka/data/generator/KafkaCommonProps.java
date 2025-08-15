package com.github.streamshub.kafka.data.generator;

import java.util.Properties;

import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG;
import static org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG;
import static org.apache.kafka.clients.CommonClientConfigs.CLIENT_ID_CONFIG;
import static org.apache.kafka.common.config.SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG;
import static org.apache.kafka.common.config.SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG;
import static org.apache.kafka.common.config.SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG;
import static org.apache.kafka.common.config.SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG;

public class KafkaCommonProps {
    static final int KAFKA_CLIENT_REQUEST_TIMEOUT_MS_CONFIG = 5000;

    public static Properties get(String clientId) {
        Properties props = new Properties();
        props.put(BOOTSTRAP_SERVERS_CONFIG, System.getenv("KAFKA_BOOTSTRAP_SERVERS"));
        props.put(CLIENT_ID_CONFIG, clientId);
        props.put(REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_CLIENT_REQUEST_TIMEOUT_MS_CONFIG));

        String securityProtocol = System.getenv("KAFKA_SECURITY_PROTOCOL");

        if (securityProtocol != null && securityProtocol.equals("SSL")) {
            props.put(SECURITY_PROTOCOL_CONFIG, securityProtocol);
            props.put(SSL_TRUSTSTORE_LOCATION_CONFIG, System.getenv("KAFKA_SSL_TRUSTSTORE_LOCATION"));
            props.put(SSL_TRUSTSTORE_TYPE_CONFIG, System.getenv("KAFKA_SSL_TRUSTSTORE_TYPE"));
            props.put(SSL_KEYSTORE_LOCATION_CONFIG, System.getenv("KAFKA_SSL_KEYSTORE_LOCATION"));
            props.put(SSL_KEYSTORE_PASSWORD_CONFIG, System.getenv("KAFKA_SSL_KEYSTORE_PASSWORD"));
        }

        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        return props;
    }
}
