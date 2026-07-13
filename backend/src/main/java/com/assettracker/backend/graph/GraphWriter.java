package com.assettracker.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

    // One Cypher statement per tick; buffer already keeps newest state per drone.
    public void upsertDronesBatch(Collection<DroneNode> drones) {
        if (drones.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>(drones.size());
        for (DroneNode d : drones) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", d.id());
            row.put("latitude", d.latitude());
            row.put("longitude", d.longitude());
            row.put("batteryLevel", d.batteryLevel());
            row.put("status", d.status().toString());
            rows.add(row);
        }
        try (Session session = driver.session()) {
            session.run(
                "UNWIND $rows AS row "
                    + "MERGE (d:Drone {id: row.id}) "
                    + "SET d.latitude = row.latitude, d.longitude = row.longitude, "
                    + "d.batteryLevel = row.batteryLevel, d.status = row.status",
                Values.parameters("rows", rows));
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

    // Both ends must exist before we swap the edge.
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
            // Flat lat/lng props, no nested map.
            session.run(
                "MATCH (d:Drone {id:$d}) "
                    + "SET d.currentWaypointLat = $lat, d.currentWaypointLng = $lng "
                    + "REMOVE d.currentWaypoint",
                Values.parameters(
                    "d", droneId,
                    "lat", waypoint.latitude(),
                    "lng", waypoint.longitude()));
        }
    }

    public void clearDroneWaypoint(String droneId) {
        try (Session session = driver.session()) {
            session.run(
                "MATCH (d:Drone {id:$d}) "
                    + "REMOVE d.currentWaypoint, d.currentWaypointLat, d.currentWaypointLng",
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

    // Safe if the drone's already gone. DETACH DELETE cleans up edges too.
    public void deleteDrone(String droneId) {
        try (Session session = driver.session()) {
            session.run("MATCH (d:Drone {id:$d}) DETACH DELETE d",
                Values.parameters("d", droneId));
        }
    }

    // Map entities

    public void upsertTrack(TrackNode track) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (t:Track {id: $id}) "
                    + "SET t.name = $name, t.affiliation = $affiliation, t.domain = $domain, "
                    + "t.latitude = $latitude, t.longitude = $longitude",
                Values.parameters(
                    "id", track.id(),
                    "name", track.name(),
                    "affiliation", track.affiliation().toString(),
                    "domain", track.domain().toString(),
                    "latitude", track.latitude(),
                    "longitude", track.longitude()));
        }
    }

    public void upsertWaypoint(WaypointNode waypoint) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (w:Waypoint {id: $id}) "
                    + "SET w.name = $name, w.latitude = $latitude, w.longitude = $longitude",
                Values.parameters(
                    "id", waypoint.id(),
                    "name", waypoint.name(),
                    "latitude", waypoint.latitude(),
                    "longitude", waypoint.longitude()));
        }
    }

    public void upsertZone(ZoneNode zone) {
        try (Session session = driver.session()) {
            // Polygon verts as two flat arrays.
            session.run(
                "MERGE (z:Zone {id: $id}) "
                    + "SET z.name = $name, z.type = $type, z.shape = $shape, "
                    + "z.centerLatitude = $centerLatitude, z.centerLongitude = $centerLongitude, "
                    + "z.radiusMeters = $radiusMeters, "
                    + "z.vertexLats = $vertexLats, z.vertexLngs = $vertexLngs",
                Values.parameters(
                    "id", zone.id(),
                    "name", zone.name(),
                    "type", zone.type().toString(),
                    "shape", zone.shape().toString(),
                    "centerLatitude", zone.centerLatitude(),
                    "centerLongitude", zone.centerLongitude(),
                    "radiusMeters", zone.radiusMeters(),
                    "vertexLats", zone.vertexLats(),
                    "vertexLngs", zone.vertexLngs()));
        }
    }

    public void deleteTrack(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (t:Track {id:$id}) DETACH DELETE t", Values.parameters("id", id));
        }
    }

    public void deleteWaypoint(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (w:Waypoint {id:$id}) DETACH DELETE w", Values.parameters("id", id));
        }
    }

    public void deleteZone(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (z:Zone {id:$id}) DETACH DELETE z", Values.parameters("id", id));
        }
    }
}
