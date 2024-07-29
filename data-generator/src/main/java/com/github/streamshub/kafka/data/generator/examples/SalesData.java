package com.github.streamshub.kafka.data.generator.examples;

import com.github.streamshub.kafka.data.generator.Data;
import com.github.streamshub.kafka.data.generator.schema.Sales;
import org.apache.avro.Schema;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class SalesData implements Data {
    private final static String INVOICE_ID = "invoice_id";
    private final static String USER_ID = "user_id";
    private final static String PRODUCT_ID = "product_id";
    private final static String QUANTITY = "quantity";
    private final static String COST = "unit_cost";
    private final Random random = new Random();

    public String topic() {
        return "flink.sales.records";
    }
    public Schema schema() {
        return Sales.SCHEMA$;
    }
    public Map<String, String> generate() {
        Map<String, String> data = new HashMap<>();
        data.put(INVOICE_ID, String.valueOf(Math.abs(random.nextLong())));
        data.put(USER_ID, "user-" + Math.abs(random.nextInt(100)));
        data.put(PRODUCT_ID, String.valueOf(Math.abs(random.nextInt(200))));
        data.put(QUANTITY, String.valueOf(Math.abs(random.nextInt(3) + 1)));
        data.put(COST, "£" + Math.abs(random.nextInt(1000) + 1));
        return data;
    }
}
