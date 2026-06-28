package com.assettracker.backend.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/**
 * A dedicated listener container factory for the {@code plan.events} topic.
 *
 * <p>The app's default consumer (from {@code application.properties}) uses a
 * {@code JsonDeserializer} bound to {@code TelemetryEvent}, which is wrong for plan
 * envelopes. {@link com.assettracker.backend.execution.PlanExecutor} references this factory
 * by name so it consumes plain JSON <b>strings</b> (which it parses with the Jackson 2 agent
 * mapper), leaving the telemetry listener untouched.
 *
 * <p>{@code auto.offset.reset=earliest}: an approved plan must execute even if the executor
 * joined the group after it was enqueued — we never want to silently drop an approval.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> planEventsListenerContainerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        return factory;
    }
}
