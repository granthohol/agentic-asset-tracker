package com.assettracker.backend.command;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes {@code SET_WAYPOINT} commands to Kafka topic {@code drone.commands.v1}.
 *
 * <p><b>CQRS write seam (motion half).</b> This is the ONLY component that turns a
 * waypoint intent into a Kafka command. There is deliberately <b>no</b>
 * {@code /api/commands} HTTP endpoint: the browser never publishes directly. The
 * only legitimate caller is {@code PlanExecutor} (the Kafka {@code plan.events}
 * listener), which runs commands on behalf of a human-approved {@code ExecutionPlan}.
 *
 * <p>{@code commandId} is minted here (server-side UUID) so the Python edge
 * consumer can dedup redelivered commands.
 */
@Component
public class CommandPublisher {

    public static final String TOPIC = "drone.commands.v1";

    private static final Logger log = LoggerFactory.getLogger(CommandPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    public CommandPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

    /**
     * Stamp a fresh {@code commandId} + {@code issuedAt} onto the waypoint intent and
     * publish it keyed on {@code droneId} (per-drone ordering survives partitioning).
     *
     * @return the fully-stamped event that was published (useful for logging / audit).
     */
    public CommandEvent publishSetWaypoint(String droneId, double targetLat, double targetLng, String missionType) {
        CommandEvent event = new CommandEvent(
            droneId,
            targetLat,
            targetLng,
            missionType,
            System.currentTimeMillis(),
            "cmd-" + UUID.randomUUID()
        );
        // Key = droneId so all commands for one drone land in the same partition, in order.
        kafkaTemplate.send(TOPIC, event.droneId(), serialize(event));
        log.info("Published SET_WAYPOINT droneId={} target=({},{}) mission={} commandId={}",
            droneId, targetLat, targetLng, missionType, event.commandId());
        return event;
    }

    private String serialize(CommandEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize CommandEvent for " + event.droneId(), e);
        }
    }
}
