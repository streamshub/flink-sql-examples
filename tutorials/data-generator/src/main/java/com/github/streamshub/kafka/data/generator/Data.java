package com.github.streamshub.kafka.data.generator;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

public interface Data {
    String topic();
    default int batchSize() {
        return 1;
    }
    SpecificRecord generate();
    String generateCsv();
    Schema schema();
}
