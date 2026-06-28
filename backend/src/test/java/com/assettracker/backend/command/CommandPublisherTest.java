package com.assettracker.backend.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CommandPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void publishSetWaypoint_sendsKeyedJsonCommandWithMintedId() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        CommandPublisher publisher = new CommandPublisher(kafkaTemplate, mapper);

        long before = System.currentTimeMillis();
        CommandEvent returned = publisher.publishSetWaypoint("drone-007", 39.06, -77.10, "RECON");
        long after = System.currentTimeMillis();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        // Topic + partition key
        assertThat(topicCaptor.getValue()).isEqualTo(CommandPublisher.TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo("drone-007");

        // The published value is JSON matching the wire contract (note snake_case mission_type)
        JsonNode sent = mapper.readTree(valueCaptor.getValue());
        assertThat(sent.get("droneId").asText()).isEqualTo("drone-007");
        assertThat(sent.get("targetLat").asDouble()).isEqualTo(39.06);
        assertThat(sent.get("targetLng").asDouble()).isEqualTo(-77.10);
        assertThat(sent.get("mission_type").asText()).isEqualTo("RECON");

        // commandId is server-minted; issuedAt is stamped at publish time
        assertThat(sent.get("commandId").asText()).startsWith("cmd-").hasSizeGreaterThan("cmd-".length());
        assertThat(sent.get("issuedAt").asLong()).isBetween(before, after);

        // The returned event mirrors what was published
        assertThat(returned.commandId()).isEqualTo(sent.get("commandId").asText());
    }
}
