package com.assettracker.backend.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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

        assertThat(topicCaptor.getValue()).isEqualTo(CommandPublisher.TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo("drone-007");

        JsonNode sent = mapper.readTree(valueCaptor.getValue());
        assertThat(sent.get("type").asText()).isEqualTo(CommandEvent.TYPE_SET_WAYPOINT);
        assertThat(sent.get("droneId").asText()).isEqualTo("drone-007");
        assertThat(sent.get("targetLat").asDouble()).isEqualTo(39.06);
        assertThat(sent.get("targetLng").asDouble()).isEqualTo(-77.10);
        assertThat(sent.get("mission_type").asText()).isEqualTo("RECON");

        assertThat(sent.get("commandId").asText()).startsWith("cmd-").hasSizeGreaterThan("cmd-".length());
        assertThat(sent.get("issuedAt").asLong()).isBetween(before, after);

        assertThat(returned.commandId()).isEqualTo(sent.get("commandId").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishClearWaypoint_sendsTypeWithoutTargets() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        CommandPublisher publisher = new CommandPublisher(kafkaTemplate, mapper);

        publisher.publishClearWaypoint("drone-003");

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(CommandPublisher.TOPIC), eq("drone-003"), valueCaptor.capture());

        JsonNode sent = mapper.readTree(valueCaptor.getValue());
        assertThat(sent.get("type").asText()).isEqualTo(CommandEvent.TYPE_CLEAR_WAYPOINT);
        assertThat(sent.get("droneId").asText()).isEqualTo("drone-003");
        assertThat(sent.has("targetLat")).isFalse();
        assertThat(sent.has("targetLng")).isFalse();
        assertThat(sent.get("commandId").asText()).startsWith("cmd-");
    }
}
