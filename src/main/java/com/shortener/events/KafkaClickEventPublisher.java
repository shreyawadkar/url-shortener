package com.shortener.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes click events keyed by short code, so every click for a code lands on the same
 * partition and is processed in order by one consumer thread.
 */
public class KafkaClickEventPublisher implements ClickEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaClickEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;
    private final Counter failures;

    public KafkaClickEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json, String topic,
                                    MeterRegistry meters) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
        this.failures = meters.counter("shortener.clicks.publish.failures");
    }

    @Override
    public void publish(ClickEvent event) {
        try {
            // Fire-and-forget: the redirect never waits on the broker.
            kafka.send(topic, event.code(), json.writeValueAsString(event))
                    .whenComplete((res, err) -> {
                        if (err != null) {
                            failures.increment();
                            log.warn("Kafka publish failed for {}: {}", event.code(), err.getMessage());
                        }
                    });
        } catch (JsonProcessingException | RuntimeException e) {
            failures.increment();
            log.warn("Kafka publish failed for {}: {}", event.code(), e.getMessage());
        }
    }

    @Override
    public String type() {
        return "kafka";
    }
}
