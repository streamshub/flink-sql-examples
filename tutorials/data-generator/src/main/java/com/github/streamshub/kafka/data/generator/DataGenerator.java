package com.github.streamshub.kafka.data.generator;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.apache.kafka.clients.CommonClientConfigs.*;
import static org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG;

public class DataGenerator implements Runnable {
    final static Map<String, String> RETENTION_CONFIG = Collections.singletonMap(RETENTION_MS_CONFIG, String.valueOf(60 * 60 * 1000)); // 1 hour
    final static int KAFKA_ADMIN_CLIENT_REQUEST_TIMEOUT_MS_CONFIG = 5000;
    final static String KAFKA_ADMIN_CLIENT_ID_CONFIG = "data-generator-admin-client";
    final String bootstrapServers;
    final List<Data> dataTypes;
    final Properties kafkaAdminProps;

    public DataGenerator(String bootstrapServers, List<Data> dataTypes) {
        this.bootstrapServers = bootstrapServers;
        this.dataTypes = dataTypes;

        kafkaAdminProps = new Properties();
        kafkaAdminProps.put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        kafkaAdminProps.put(CLIENT_ID_CONFIG, KAFKA_ADMIN_CLIENT_ID_CONFIG);
        kafkaAdminProps.put(REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_ADMIN_CLIENT_REQUEST_TIMEOUT_MS_CONFIG));
    }

    @Override
    public void run() {
        createTopics(dataTypes.stream().map(Data::topic).toList());

        if (Boolean.parseBoolean(System.getenv("USE_APICURIO_REGISTRY"))) {
            String registryUrl = System.getenv("REGISTRY_URL");
            Producer<String, Object> producer = new KafkaProducer<>(KafkaClientProps.avro(bootstrapServers, registryUrl));
            send(producer, () -> generateTopicRecords(this::generateAvroRecord));
        } else {
            Producer<String, String> producer = new KafkaProducer<>(KafkaClientProps.csv(bootstrapServers));
            send(producer, () -> generateTopicRecords(this::generateCsvRecord));
        }
    }

    private <V> List<ProducerRecord<String, V>> generateTopicRecords(Function<Data, ProducerRecord<String, V>> recordsGenerator) {
        List<ProducerRecord<String, V>> records = new ArrayList<>();

        for (Data dataType : this.dataTypes) {
            for (int i = 0; i < dataType.batchSize(); i++) {
                records.add(recordsGenerator.apply(dataType));
            }
        }

        return records;
    }

    private void createTopics(List<String> topicNames) {
        try (AdminClient adminClient = AdminClient.create(kafkaAdminProps)) {
            Set<String> existingTopicNames = adminClient.listTopics().names().get();
            List<NewTopic> newTopics = topicNames.stream()
                    .filter(topicName -> !existingTopicNames.contains(topicName)) // createTopics() will throw if topic already exists
                    .map(
                    topicName -> new NewTopic(topicName, Optional.empty(), Optional.empty()).configs(RETENTION_CONFIG)
                    )
                    .toList();

            if (newTopics.isEmpty()) {
                return;
            }

            adminClient.createTopics(newTopics).all().get();
            System.out.println("Kafka Data Generator : Successfully created topics: " + newTopics);
        } catch (Exception e) {
            System.out.println("Kafka Data Generator : Failed to create topics.");
            throw new RuntimeException(e);
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

                Thread.sleep(1000);

            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
