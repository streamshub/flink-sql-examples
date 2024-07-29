package com.github.streamshub.kafka.data.generator.examples;

import com.github.streamshub.kafka.data.generator.Data;
import com.github.streamshub.kafka.data.generator.schema.ClickStream;
import org.apache.avro.Schema;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class ClickStreamData implements Data {
    private final static String USER_ID = "user_id";
    private final static String PRODUCT_ID = "product_id";
    private final Random random = new Random();

    public String topic() {
        return "flink.click.streams";
    }
    public Schema schema() {
        return ClickStream.SCHEMA$;
    }
    public Map<String, String> generate() {
        Map<String, String> data = new HashMap<>();
        data.put(USER_ID, "user-" + Math.abs(random.nextInt(100)));
        data.put(PRODUCT_ID, String.valueOf(Math.abs(random.nextInt(200))));
        return data;
    }
}
