package com.github.streamshub.kafka.data.generator.examples;

import com.github.streamshub.kafka.data.generator.Data;
import com.github.streamshub.kafka.data.generator.schema.Sales;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import java.util.Random;

public class SalesData implements Data {
    private final Random random = new Random();

    public String topic() {
        return "flink.sales.records";
    }
    public Schema schema() {
        return Sales.SCHEMA$;
    }

    public SpecificRecord generate() {
        return Sales.newBuilder()
                .setInvoiceId(generateInvoiceId())
                .setUserId(generateUserId())
                .setProductId(generateProductId())
                .setQuantity(generateQuantity())
                .setUnitCost(generateUnitCost())
                .build();
    }
    public String generateCsv() {
        return String.join(",",
                generateInvoiceId(),
                generateUserId(),
                generateProductId(),
                generateQuantity(),
                generateUnitCost());
    }

    private String generateInvoiceId() {
        return String.valueOf(Math.abs(random.nextLong()));
    }

    private String generateUserId() {
        return "user-" + Math.abs(random.nextInt(100));
    }

    private String generateProductId() {
        return String.valueOf(Math.abs(random.nextInt(200)));
    }

    private String generateQuantity() {
        return String.valueOf(Math.abs(random.nextInt(3) + 1));
    }

    private String generateUnitCost() {
        return "£" + Math.abs(random.nextInt(1000) + 1);
    }
}
