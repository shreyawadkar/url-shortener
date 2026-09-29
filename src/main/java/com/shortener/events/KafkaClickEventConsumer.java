package com.shortener.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.List;

public class KafkaClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaClickEventConsumer.class);

    private final ClickAggregator aggregator;
    private final ObjectMapper json;

    public KafkaClickEventConsumer(ClickAggregator aggregator, ObjectMapper json) {
        this.aggregator = aggregator;
        this.json = json;
    }

    @KafkaListener(topics = "${app.events.topic}", groupId = "click-aggregator",
            concurrency = "${app.events.consumer-concurrency}", batch = "true")
    public void onClicks(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> rec : records) {
            try {
                ClickEvent event = json.readValue(rec.value(), ClickEvent.class);
                aggregator.record(event.code());
            } catch (Exception e) {
                log.warn("Skipping malformed click event at {}-{}@{}: {}",
                        rec.topic(), rec.partition(), rec.offset(), e.getMessage());
            }
        }
    }
}
