package com.assettracker.backend.graph;

import java.util.Map;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

@Service
public class GraphWriter {

    private final Driver driver;

    public GraphWriter(Driver driver) {
        this.driver = driver;
    }

    public void upsertDrone(DroneNode drone) {
        try (Session session = driver.session()) {
            session.run("MERGE (d:Drone {id: $id}) SET d.latitude = $latitude, d.longitude = $longitude, d.batteryLevel = $batteryLevel, d.status = $status", 
                Values.parameters("id", drone.id(), "latitude", drone.latitude(), "longitude", drone.longitude(), "batteryLevel", drone.batteryLevel(), "status", drone.status().toString()));
        }
    }

    public void upsertSquadron(SquadronNode squadron) {
        try (Session session = driver.session()) {
            session.run("MERGE (s:Squadron {id: $id}) SET s.name = $name, s.sectorId = $sectorId", 
                Values.parameters("id", squadron.id(), "name", squadron.name(), "sectorId", squadron.sectorId()));
        }
    }

    public void upsertObjective(ObjectiveNode objective) {
        try (Session session = driver.session()) {
            session.run("MERGE (o:Objective {id: $id}) SET o.name = $name, o.priority = $priority, o.centerLatitude = $centerLatitude, o.centerLongitude = $centerLongitude, o.targetEntityId = $targetEntityId, o.radiusMeters = $radiusMeters", 
                Values.parameters("id", objective.id(), "name", objective.name(), "priority", objective.priority(), "centerLatitude", objective.centerLatitude(), "centerLongitude", objective.centerLongitude(), "targetEntityId", objective.targetEntityId(), "radiusMeters", objective.radiusMeters()));
        }
    }

    // Both endpoints are matched up front. If either is missing the whole
    // statement produces zero rows and we throw -- so we never delete the old
    // edge unless we can also create the new one. RETURN gives us a row count
    // to assert on.
    public void assignDroneToSquadron(String droneId, String squadronId) {
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (d:Drone {id:$d}) " +
                "MATCH (s:Squadron {id:$s}) " +
                "OPTIONAL MATCH (d)-[r:ASSIGNED_TO]->() DELETE r " +
                "WITH d, s " +
                "MERGE (d)-[:ASSIGNED_TO]->(s) " +
                "RETURN d.id AS droneId, s.id AS squadronId",
                Values.parameters("d", droneId, "s", squadronId));
            if (!result.hasNext()) {
                throw new IllegalArgumentException(
                    "assignDroneToSquadron: drone or squadron not found (droneId=" + droneId + ", squadronId=" + squadronId + ")");
            }
            result.consume();
        }
    }

    public void deploySquadronToObjective(String squadronId, String objectiveId) {
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (s:Squadron {id:$s}) " +
                "MATCH (o:Objective {id:$o}) " +
                "OPTIONAL MATCH (s)-[r:DEPLOYED_FOR]->() DELETE r " +
                "WITH s, o " +
                "MERGE (s)-[:DEPLOYED_FOR]->(o) " +
                "RETURN s.id AS squadronId, o.id AS objectiveId",
                Values.parameters("s", squadronId, "o", objectiveId));
            if (!result.hasNext()) {
                throw new IllegalArgumentException(
                    "deploySquadronToObjective: squadron or objective not found (squadronId=" + squadronId + ", objectiveId=" + objectiveId + ")");
            }
            result.consume();
        }
    }

    public void setDroneWaypoint(String droneId, Waypoint waypoint) {
        try (Session session = driver.session()) {
            // Waypoint record can't be auto-serialized by the driver, so hand
            // it a Map with the short keys the reader (mapDroneNode) expects.
            Map<String, Object> waypointMap = Map.of(
                "lat", waypoint.latitude(),
                "lng", waypoint.longitude()
            );
            session.run("MATCH (d:Drone {id:$d}) SET d.currentWaypoint = $waypoint",
                Values.parameters("d", droneId, "waypoint", waypointMap));
        }
    }

    public void clearDroneWaypoint(String droneId) {
        try (Session session = driver.session()) {
            session.run("MATCH (d:Drone {id:$d}) REMOVE d.currentWaypoint",
                Values.parameters("d", droneId));
        }
    }

    public void removeSquadronFromObjective(String squadronId) {
        try (Session session = driver.session()) {
            session.run("MATCH (s:Squadron {id:$s}) OPTIONAL MATCH (s)-[r:DEPLOYED_FOR]->() DELETE r",
                Values.parameters("s", squadronId));
        }
    }

    public void removeDroneAssignment(String droneId) {
        try (Session session = driver.session()) {
            session.run("MATCH (d:Drone {id:$d}) OPTIONAL MATCH (d)-[r:ASSIGNED_TO]->() DELETE r",
                Values.parameters("d", droneId));
        }
    }

    // Idempotent: if the drone isn't there, MATCH yields zero rows and nothing
    // happens. DETACH DELETE removes the node and any incident relationships
    // (e.g. ASSIGNED_TO) in one pass so we don't leave dangling edges.
    public void deleteDrone(String droneId) {
        try (Session session = driver.session()) {
            session.run("MATCH (d:Drone {id:$d}) DETACH DELETE d",
                Values.parameters("d", droneId));
        }
    }
}
