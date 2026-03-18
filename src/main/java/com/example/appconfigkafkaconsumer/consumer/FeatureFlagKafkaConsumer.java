package com.example.appconfigkafkaconsumer.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class FeatureFlagKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagKafkaConsumer.class);

    // Provide an ID so we can target it via KafkaListenerEndpointRegistry
    public static final String LISTENER_ID = "dynamic-kafka-listener";

    // Auto-startup is set to false because we want the scheduler to evaluate the
    // flag first
    // Note: by setting the 'id' on the KafkaListener, Spring Kafka will
    // automatically use that as the consumer group unless you
    // also explicitly set 'groupId' on the KafkaListener.
    @KafkaListener(id = LISTENER_ID, topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}", autoStartup = "false")
    public void consume(ConsumerRecord<?, ?> record) {
        log.info("Consumed message; topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
