package com.github.streamshub.kafka.data.generator;

import io.apicurio.registry.rest.v2.beans.IfExists;
import io.apicurio.registry.serde.SerdeConfig;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import io.apicurio.registry.serde.config.IdOption;

import java.util.Properties;

public class KafkaClientProps {
    static final String KAFKA_CLIENT_ID_CONFIG = "data-generator-client";

    public static Properties csv() {
        Properties props = KafkaCommonProps.get(KAFKA_CLIENT_ID_CONFIG);
        props.put("value.serializer",
                "org.apache.kafka.common.serialization.StringSerializer");
        return props;
    }

    public static Properties avro(String registryUrl) {
        Properties props = KafkaCommonProps.get(KAFKA_CLIENT_ID_CONFIG);
        props.put("value.serializer", AvroKafkaSerializer.class.getName());

        props.put(SerdeConfig.REGISTRY_URL, registryUrl);
        props.putIfAbsent(SerdeConfig.ENABLE_CONFLUENT_ID_HANDLER, Boolean.TRUE);
        props.putIfAbsent(SerdeConfig.USE_ID, IdOption.contentId.toString());
        props.putIfAbsent(SerdeConfig.ENABLE_HEADERS, Boolean.FALSE);
        props.putIfAbsent(SerdeConfig.AUTO_REGISTER_ARTIFACT, Boolean.TRUE);
        props.putIfAbsent(SerdeConfig.AUTO_REGISTER_ARTIFACT_IF_EXISTS, IfExists.RETURN.name());

        return props;
    }
}
