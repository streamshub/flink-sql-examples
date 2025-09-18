package com.github.streamshub.kafka.data.generator;

import java.util.Properties;

import static org.apache.kafka.clients.CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG;
import static org.apache.kafka.clients.CommonClientConfigs.CLIENT_ID_CONFIG;

public class KafkaCommonProps {
    static final int KAFKA_CLIENT_REQUEST_TIMEOUT_MS_CONFIG = 5000;

    public static Properties get(String clientId) {
        Properties props = ConfigUtil.getKafkaPropertiesFromEnv();
        props.put(CLIENT_ID_CONFIG, clientId);
        props.putIfAbsent(REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_CLIENT_REQUEST_TIMEOUT_MS_CONFIG));
        props.putIfAbsent("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        return props;
    }
}
