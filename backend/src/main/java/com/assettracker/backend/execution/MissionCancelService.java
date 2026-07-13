package com.assettracker.backend.execution;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.assettracker.backend.command.CommandPublisher;
import com.assettracker.backend.graph.GraphWriter;

/** Clear waypoints in Neo4j + publish CLEAR_WAYPOINT. POST /api/cancel-mission only. */
@Service
public class MissionCancelService {

    private static final Logger log = LoggerFactory.getLogger(MissionCancelService.class);

    private final CommandPublisher commandPublisher;
    private final GraphWriter graphWriter;

    public MissionCancelService(CommandPublisher commandPublisher, GraphWriter graphWriter) {
        this.commandPublisher = commandPublisher;
        this.graphWriter = graphWriter;
    }

    public int cancelDrones(List<String> droneIds) {
        if (droneIds == null || droneIds.isEmpty()) {
            return 0;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : droneIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id.trim());
            }
        }
        for (String droneId : unique) {
            graphWriter.clearDroneWaypoint(droneId);
            commandPublisher.publishClearWaypoint(droneId);
        }
        log.info("Cancelled mission for {} drone(s)", unique.size());
        return unique.size();
    }
}
