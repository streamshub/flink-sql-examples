package com.github.streamshub.kafka.data.generator;

import java.util.Properties;

public class KafkaAdminProps {
    static final String KAFKA_CLIENT_ID_CONFIG = "data-generator-admin-client";

    public static Properties get() {
        return KafkaCommonProps.get(KAFKA_CLIENT_ID_CONFIG);
    }
}
