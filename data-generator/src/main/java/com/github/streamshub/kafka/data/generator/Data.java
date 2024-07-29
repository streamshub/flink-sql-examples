package com.github.streamshub.kafka.data.generator;

import org.apache.avro.Schema;

import java.util.Map;

public interface Data {
    String topic();
    Map<String, String> generate();
    Schema schema();
}
