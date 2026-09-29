package com.shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortener.events.ClickAggregator;
import com.shortener.events.ClickEventPublisher;
import com.shortener.events.InMemoryClickEventPublisher;
import com.shortener.events.KafkaClickEventConsumer;
import com.shortener.events.KafkaClickEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class EventsConfig {

    private static final Logger log = LoggerFactory.getLogger(EventsConfig.class);

    @Bean
    @ConditionalOnExpression("'${app.events.kafka-bootstrap-servers:}' == ''")
    public ClickEventPublisher inMemoryClickEventPublisher(ClickAggregator aggregator) {
        log.info("KAFKA_BOOTSTRAP_SERVERS not set: click events use the in-process bus");
        return new InMemoryClickEventPublisher(aggregator);
    }

    @Configuration
    @EnableKafka
    @ConditionalOnExpression("'${app.events.kafka-bootstrap-servers:}' != ''")
    static class KafkaEventsConfig {

        private final AppProperties.Events cfg;

        KafkaEventsConfig(AppProperties props) {
            this.cfg = props.events();
        }

        private Map<String, Object> common() {
            Map<String, Object> m = new HashMap<>();
            m.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, cfg.kafkaBootstrapServers());
            if (cfg.kafkaUsername() != null && !cfg.kafkaUsername().isBlank()) {
                String module = cfg.kafkaSaslMechanism().startsWith("SCRAM")
                        ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                        : "org.apache.kafka.common.security.plain.PlainLoginModule";
                m.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
                m.put(SaslConfigs.SASL_MECHANISM, cfg.kafkaSaslMechanism());
                m.put(SaslConfigs.SASL_JAAS_CONFIG, module + " required username=\"" + cfg.kafkaUsername()
                        + "\" password=\"" + cfg.kafkaPassword() + "\";");
            }
            return m;
        }

        @Bean
        KafkaAdmin kafkaAdmin() {
            KafkaAdmin admin = new KafkaAdmin(common());
            admin.setFatalIfBrokerNotAvailable(false);
            return admin;
        }

        @Bean
        NewTopic clickEventsTopic() {
            return TopicBuilder.name(cfg.topic()).partitions(cfg.partitions()).build();
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            Map<String, Object> p = common();
            p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            p.put(ProducerConfig.ACKS_CONFIG, "1");
            p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
            p.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
            p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(p));
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            Map<String, Object> c = common();
            c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
            ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(c));
            return f;
        }

        @Bean
        ClickEventPublisher kafkaClickEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json,
                                                     MeterRegistry meters) {
            log.info("Publishing click events to Kafka topic '{}' at {}", cfg.topic(), cfg.kafkaBootstrapServers());
            return new KafkaClickEventPublisher(kafka, json, cfg.topic(), meters);
        }

        @Bean
        KafkaClickEventConsumer kafkaClickEventConsumer(ClickAggregator aggregator, ObjectMapper json) {
            return new KafkaClickEventConsumer(aggregator, json);
        }
    }
}
