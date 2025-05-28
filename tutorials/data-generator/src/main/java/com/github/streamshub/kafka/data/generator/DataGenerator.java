package com.github.streamshub.kafka.data.generator;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.List;
import java.util.function.Supplier;

public class DataGenerator implements Runnable {
    final String bootstrapServers;
    final List<Data> dataTypes;

    public DataGenerator(String bootstrapServers, List<Data> dataTypes) {
        this.bootstrapServers = bootstrapServers;
        this.dataTypes = dataTypes;
    }

    @Override
    public void run() {
        if (Boolean.parseBoolean(System.getenv("USE_APICURIO_REGISTRY"))) {
            String registryUrl = System.getenv("REGISTRY_URL");
            Producer<String, Object> producer = new KafkaProducer<>(KafkaClientProps.avro(bootstrapServers, registryUrl));
            send(producer, () -> dataTypes.stream().map(this::generateAvroRecord).toList());
        } else {
            Producer<String, String> producer = new KafkaProducer<>(KafkaClientProps.csv(bootstrapServers));
            send(producer, () -> dataTypes.stream().map(this::generateCsvRecord).toList());
        }
    }

    private ProducerRecord<String, Object> generateAvroRecord(Data data) {
        return getKafkaProducerRecord(data, data.generate());
    }

    private ProducerRecord<String, String> generateCsvRecord(Data data) {
        return getKafkaProducerRecord(data, data.generateCsv());
    }

    private static <V> ProducerRecord<String, V> getKafkaProducerRecord(Data data, V value) {
        String key = String.valueOf(System.currentTimeMillis());
        return new ProducerRecord<>(
                data.topic(),
                key,
                value);
    }

    private <V> void send(Producer<String, V> producer, Supplier<List<ProducerRecord<String, V>>> recordsSupplier) {
        try (producer) {

            while (true) {
                List<ProducerRecord<String, V>> producerRecords = recordsSupplier.get();
                for (ProducerRecord<String, V> record : producerRecords) {
                    try {
                        producer.send(record).get();
                        System.out.println("Kafka Data Generator : Sent record to topic " + record.topic() +
                                " with value: " + record.value());
                    } catch (Exception e) {
                        System.out.println("Kafka Data Generator : Encountered error sending record to topic " + record.topic() +
                                " with value: " + record.value());
                        e.printStackTrace();
                    }
                }

                Thread.sleep(3000);

            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
