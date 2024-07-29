package com.github.streamshub.kafka.data.generator;

import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public class DataGenerator implements Runnable {
    final String bootstrapServers;
    final Data data;

    public DataGenerator(String bootstrapServers, Data data) {
        this.bootstrapServers = bootstrapServers;
        this.data = data;
    }

    @Override
    public void run() {
        if (Boolean.parseBoolean(System.getenv("USE_APICURIO_REGISTRY"))) {
            String registryUrl = System.getenv("REGISTRY_URL");
            Producer<String, Object> producer = new KafkaProducer<>(KafkaClientProps.avro(bootstrapServers, registryUrl));
            send(producer, data.topic(), () -> {
                Map<String, String> generatedData = data.generate();
                GenericRecord avroRecord = new GenericData.Record(data.schema());
                generatedData.forEach(avroRecord::put);
                return avroRecord;
            });
        } else {
            Producer<String, String> producer = new KafkaProducer<>(KafkaClientProps.csv(bootstrapServers));
            send(producer, data.topic(), () -> String.join(",", data.generate().values()));
        }
    }

    private <V> void send(Producer<String, V> producer, String topic, Supplier<V> valueSupplier) {
        try (producer) {

            while (true) {
                String key = String.valueOf(System.currentTimeMillis());
                V value = valueSupplier.get();
                ProducerRecord<String, V> record =
                        new ProducerRecord<>(
                                topic,
                                key,
                                valueSupplier.get());

                producer.send(record).get();

                System.out.println("Kafka Data Generator : Sent record to topic " + topic + " with value: " +
                        value);

                Thread.sleep(3000);

            }

        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
