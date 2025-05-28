package com.github.streamshub.kafka.data.generator;

import com.github.streamshub.kafka.data.generator.examples.ClickStreamData;
import com.github.streamshub.kafka.data.generator.examples.SalesData;

import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        String bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        List<Data> dataTypes = Arrays.stream(System.getenv("DATA").split(","))
                .map(Main::getDataClass)
                .toList();
        Thread dgThread = new Thread(new DataGenerator(bootstrapServers, dataTypes));
        dgThread.start();
    }

    private static Data getDataClass(String dataType) {
        Data data;
        switch(dataType) {
            case "clickStream" -> data = new ClickStreamData();
            case "sales" -> data = new SalesData();
            default -> throw new RuntimeException("Unknown data type " + dataType);
        }
        return data;
    }
}
