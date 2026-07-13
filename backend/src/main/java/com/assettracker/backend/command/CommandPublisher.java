package com.assettracker.backend.command;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes motion commands to drone.commands.v1.
 * Only PlanExecutor and MissionCancelService call this, never the browser.
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

    /** Fresh commandId + issuedAt, keyed on droneId for per-drone ordering. */
    public CommandEvent publishSetWaypoint(String droneId, double targetLat, double targetLng, String missionType) {
        CommandEvent event = new CommandEvent(
            CommandEvent.TYPE_SET_WAYPOINT,
            droneId,
            targetLat,
            targetLng,
            missionType,
            System.currentTimeMillis(),
            "cmd-" + UUID.randomUUID()
        );
        kafkaTemplate.send(TOPIC, event.droneId(), serialize(event));
        log.info("Published SET_WAYPOINT droneId={} target=({},{}) mission={} commandId={}",
            droneId, targetLat, targetLng, missionType, event.commandId());
        return event;
    }

    /** Clear waypoint so the edge resumes free movement. */
    public CommandEvent publishClearWaypoint(String droneId) {
        CommandEvent event = new CommandEvent(
            CommandEvent.TYPE_CLEAR_WAYPOINT,
            droneId,
            null,
            null,
            null,
            System.currentTimeMillis(),
            "cmd-" + UUID.randomUUID()
        );
        kafkaTemplate.send(TOPIC, event.droneId(), serialize(event));
        log.info("Published CLEAR_WAYPOINT droneId={} commandId={}", droneId, event.commandId());
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
