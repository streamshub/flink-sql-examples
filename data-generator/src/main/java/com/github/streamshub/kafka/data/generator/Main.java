package com.github.streamshub.kafka.data.generator;

import com.github.streamshub.kafka.data.generator.examples.ClickStreamData;
import com.github.streamshub.kafka.data.generator.examples.SalesData;

import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        String bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        String[] dataTypes = System.getenv("DATA").split(",");
        Arrays.stream(dataTypes)
                .map(Main::getDataClass)
                .forEach(data -> {
                    System.out.println("Starting Data Generator " + data.getClass().getName() + "...");
                    Thread dgThread = new Thread(new DataGenerator(bootstrapServers, data));
                    dgThread.start();

                    //So two classes don't fight when registering the schemas in the registry
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
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
