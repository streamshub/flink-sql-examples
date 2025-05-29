package com.github.streamshub.kafka.data.generator.examples;

import com.github.streamshub.kafka.data.generator.Data;
import com.github.streamshub.kafka.data.generator.schema.ClickStream;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import java.util.Random;

public class ClickStreamData implements Data {
    private final Random random = new Random();

    public String topic() {
        return "flink.click.streams";
    }
    public Schema schema() {
        return ClickStream.SCHEMA$;
    }

    public SpecificRecord generate() {
        return ClickStream.newBuilder()
                .setUserId(generateUserId())
                .setProductId(generateProductId())
                .build();
    }

    public String generateCsv() {
        return String.join(",", generateUserId(), generateProductId());
    }

    private String generateUserId() {
        return "user-" + Math.abs(random.nextInt(100));
    }

    private String generateProductId() {
        return String.valueOf(Math.abs(random.nextInt(200)));
    }
}
