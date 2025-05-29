package com.github.streamshub.kafka.data.generator;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

public interface Data {
    String topic();
    SpecificRecord generate();
    String generateCsv();
    Schema schema();
}
