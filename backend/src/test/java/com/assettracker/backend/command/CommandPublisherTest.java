package com.assettracker.backend.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

class CommandPublisherTest {

    @Test
    void publishSetWaypoint_sendsKeyedJsonCommandWithMintedId() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, CommandEvent> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        CommandPublisher publisher = new CommandPublisher(kafkaTemplate);

        long before = System.currentTimeMillis();
        CommandEvent returned = publisher.publishSetWaypoint("drone-007", 39.06, -77.10, "RECON");
        long after = System.currentTimeMillis();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<CommandEvent> valueCaptor = ArgumentCaptor.forClass(CommandEvent.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        // Topic + partition key
        assertThat(topicCaptor.getValue()).isEqualTo(CommandPublisher.TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo("drone-007");

        // The published value carries the intent fields verbatim
        CommandEvent sent = valueCaptor.getValue();
        assertThat(sent).isEqualTo(returned);
        assertThat(sent.droneId()).isEqualTo("drone-007");
        assertThat(sent.targetLat()).isEqualTo(39.06);
        assertThat(sent.targetLng()).isEqualTo(-77.10);
        assertThat(sent.missionType()).isEqualTo("RECON");

        // commandId is server-minted; issuedAt is stamped at publish time
        assertThat(sent.commandId()).startsWith("cmd-");
        assertThat(sent.commandId().length()).isGreaterThan("cmd-".length());
        assertThat(sent.issuedAt()).isBetween(before, after);
    }
}
