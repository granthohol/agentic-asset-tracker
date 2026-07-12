package com.assettracker.backend.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.Values;

import org.springframework.stereotype.Service;

import com.assettracker.backend.model.DroneStatus;

// read API over the graph stored in Neo4j
@Service
public class GraphService {
    private final Driver driver;

    public GraphService(Driver driver) {
        this.driver = driver;
    }

    public List<DroneNode> listDrones() {
        List<DroneNode> drones = new ArrayList<>();
        try(Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone)
                RETURN d
                ORDER BY d.id
                """
            );
            while (res.hasNext()) {
                drones.add(mapDroneNode(res.next().get("d").asNode()));
            }
        }
        return drones;
    }

    public List<SquadronNode> listSquadrons() {
        List<SquadronNode> squadrons = new ArrayList<>();
        try(Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (s:Squadron)
                RETURN s
                ORDER BY s.id
                """
            );
            while (res.hasNext()) {
                squadrons.add(mapSquadronNode(res.next().get("s").asNode()));
            }
        }
        return squadrons;
    }

    public List<ObjectiveNode> listObjectives() {
        List<ObjectiveNode> objectives = new ArrayList<>();
        try(Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (o:Objective)
                RETURN o
                ORDER BY o.id
                """
            );
            while (res.hasNext()) {
                objectives.add(mapObjectiveNode(res.next().get("o").asNode()));
            }
        }
        return objectives;
    }

    public Optional<DroneDetail> getDroneById(String id){
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone {id: $id})
                OPTIONAL MATCH (d)-[:ASSIGNED_TO]->(s:Squadron)
                OPTIONAL MATCH (s)-[:DEPLOYED_FOR]->(o:Objective)
                RETURN d, s, o
                """,
                Values.parameters("id", id)
            );

            if (!res.hasNext()) {
                return Optional.empty();
            }

            Record rec = res.next();

            SquadronNode squadron = null;
            if (!rec.get("s").isNull()) {
                squadron = mapSquadronNode(rec.get("s").asNode());
            }

            ObjectiveNode objective = null;
            if (!rec.get("o").isNull()) {
                objective = mapObjectiveNode(rec.get("o").asNode());
            }

            return Optional.of(new DroneDetail(
                mapDroneNode(rec.get("d").asNode()),
                squadron,
                objective
            ));
        }
    }

    public Optional<SquadronNode> getSquadronById(String id) {
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (s:Squadron {id: $id}) RETURN s", Values.parameters("id", id));
            if (!res.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(mapSquadronNode(res.next().get("s").asNode()));
        }
    }

    public Optional<ObjectiveNode> getObjectiveById(String id) {
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (o:Objective {id: $id}) RETURN o", Values.parameters("id", id));
            if (!res.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(mapObjectiveNode(res.next().get("o").asNode()));
        }
    }



    public List<DroneNode> getDronesInSquadron(String squadId) {
        List<DroneNode> drones = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone)-[:ASSIGNED_TO]->(s:Squadron {id: $squadId})
                RETURN d
                """,
                Values.parameters("squadId", squadId)
            );
            while (res.hasNext()) {
                drones.add(mapDroneNode(res.next().get("d").asNode()));
            }
        }
        return drones;
    }

    public List<DroneNode> getDronesByStatus(DroneStatus status) {
        List<DroneNode> drones = new ArrayList<>();
        String statusStr = status.toString();
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone {status:$statusStr})
                Return d
                """,
                Values.parameters("statusStr", statusStr)
            );
            while (res.hasNext()) {
                drones.add(mapDroneNode(res.next().get("d").asNode()));
            }
        }
        return drones; 
    }

    public List<DroneNode> getLowBatteryDrones(int threshold) {
        List<DroneNode> drones = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone) WHERE
                d.batteryLevel < $threshold
                Return d
                """,
                Values.parameters("threshold", threshold)
            );
            while (res.hasNext()) {
                drones.add(mapDroneNode(res.next().get("d").asNode()));
            }
        }
        return drones;
    }

    public List<DroneNode> getLowBatteryDronesInSector(String sectorId, int threshold) {
        List<DroneNode> drones = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone)-[:ASSIGNED_TO]->(s:Squadron {sectorId: $sectorId}) WHERE
                d.batteryLevel < $threshold
                Return d
                """,
                Values.parameters("sectorId", sectorId, "threshold", threshold)
            );
            while (res.hasNext()) {
                drones.add(mapDroneNode(res.next().get("d").asNode()));
            }
        }
        return drones;
    }

    public List<SquadronNode> getSquadronsForObjective(String objId){
        List<SquadronNode> squadrons = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (s:Squadron)-[:DEPLOYED_FOR]->(o:Objective {id: $objId})
                Return s        
                """,
                Values.parameters("objId", objId)
            );
            while (res.hasNext()) {
                squadrons.add(mapSquadronNode(res.next().get("s").asNode()));
            }
        }
        return squadrons;
    }

    public List<DroneNode> getDronesNear(double lat, double lng, double radiusKm) {
        List<DroneNode> drones = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run(
                """
                MATCH (d:Drone) WHERE
                point.distance(point({latitude: d.latitude, longitude: d.longitude}), point({latitude: $lat, longitude: $lng})) < $radiusMeters
                Return d        
                """,
                Values.parameters("lat", lat, "lng", lng, "radiusMeters", radiusKm * 1000)
            );
            while (res.hasNext()) {
                drones.add(mapDroneNode(res.next().get("d").asNode()));
            }
        }
        return drones; 
    }
    
    // ---- Map entities (Phase 1): :Track / :Waypoint / :Zone ----

    public List<TrackNode> listTracks() {
        List<TrackNode> tracks = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (t:Track) RETURN t ORDER BY t.id");
            while (res.hasNext()) {
                tracks.add(mapTrackNode(res.next().get("t").asNode()));
            }
        }
        return tracks;
    }

    public List<WaypointNode> listWaypoints() {
        List<WaypointNode> waypoints = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (w:Waypoint) RETURN w ORDER BY w.id");
            while (res.hasNext()) {
                waypoints.add(mapWaypointNode(res.next().get("w").asNode()));
            }
        }
        return waypoints;
    }

    public List<ZoneNode> listZones() {
        List<ZoneNode> zones = new ArrayList<>();
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (z:Zone) RETURN z ORDER BY z.id");
            while (res.hasNext()) {
                zones.add(mapZoneNode(res.next().get("z").asNode()));
            }
        }
        return zones;
    }

    public Optional<TrackNode> getTrackById(String id) {
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (t:Track {id: $id}) RETURN t", Values.parameters("id", id));
            if (!res.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(mapTrackNode(res.next().get("t").asNode()));
        }
    }

    public Optional<WaypointNode> getWaypointById(String id) {
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (w:Waypoint {id: $id}) RETURN w", Values.parameters("id", id));
            if (!res.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(mapWaypointNode(res.next().get("w").asNode()));
        }
    }

    public Optional<ZoneNode> getZoneById(String id) {
        try (Session session = driver.session()) {
            Result res = session.run("MATCH (z:Zone {id: $id}) RETURN z", Values.parameters("id", id));
            if (!res.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(mapZoneNode(res.next().get("z").asNode()));
        }
    }

    private TrackNode mapTrackNode(Node node) {
        return new TrackNode(
            node.get("id").asString(),
            node.get("name").asString(),
            Affiliation.valueOf(node.get("affiliation").asString()),
            TrackDomain.valueOf(node.get("domain").asString()),
            node.get("latitude").asDouble(),
            node.get("longitude").asDouble()
        );
    }

    private WaypointNode mapWaypointNode(Node node) {
        return new WaypointNode(
            node.get("id").asString(),
            node.get("name").asString(),
            node.get("latitude").asDouble(),
            node.get("longitude").asDouble()
        );
    }

    private ZoneNode mapZoneNode(Node node) {
        var centerLatVal = node.get("centerLatitude");
        Double centerLatitude = centerLatVal.isNull() ? null : centerLatVal.asDouble();

        var centerLngVal = node.get("centerLongitude");
        Double centerLongitude = centerLngVal.isNull() ? null : centerLngVal.asDouble();

        var radiusVal = node.get("radiusMeters");
        Double radiusMeters = radiusVal.isNull() ? null : radiusVal.asDouble();

        return new ZoneNode(
            node.get("id").asString(),
            node.get("name").asString(),
            ZoneType.valueOf(node.get("type").asString()),
            ZoneShape.valueOf(node.get("shape").asString()),
            centerLatitude,
            centerLongitude,
            radiusMeters,
            toDoubleArray(node.get("vertexLats")),
            toDoubleArray(node.get("vertexLngs"))
        );
    }

    private static double[] toDoubleArray(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return new double[0];
        }
        List<Object> raw = value.asList();
        double[] out = new double[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            out[i] = ((Number) raw.get(i)).doubleValue();
        }
        return out;
    }

    // Mapping a neo4j cypher return type 'node' to the DroneNode object
    private DroneNode mapDroneNode(Node node) {
        DroneStatus status = DroneStatus.valueOf(node.get("status").asString());
    
        // Prefer flat primitive properties (Neo4j cannot store nested maps on nodes).
        Waypoint currentWaypoint = null;
        var lat = node.get("currentWaypointLat");
        var lng = node.get("currentWaypointLng");
        if (!lat.isNull() && !lng.isNull()) {
            currentWaypoint = new Waypoint(lat.asDouble(), lng.asDouble());
        }

        return new DroneNode(
            node.get("id").asString(),
            node.get("latitude").asDouble(),
            node.get("longitude").asDouble(),
            node.get("batteryLevel").asInt(),
            status,
            currentWaypoint
        );
    }

    // Mapping a neo4j cypher return type 'node' to the SquadronNode object
    private SquadronNode mapSquadronNode(Node node) {
        return new SquadronNode(
            node.get("id").asString(),
            node.get("name").asString(),
            node.get("sectorId").asString()
        );
    }

    private ObjectiveNode mapObjectiveNode(Node node) {
        var centerLatVal = node.get("centerLatitude");
        Double centerLatitude = centerLatVal.isNull() ? null : centerLatVal.asDouble();

        var centerLngVal = node.get("centerLongitude");
        Double centerLongitude = centerLngVal.isNull() ? null : centerLngVal.asDouble();

        var targetEntityIdVal = node.get("targetEntityId");
        String targetEntityId = targetEntityIdVal.isNull() ? null : targetEntityIdVal.asString();

        var radiusVal = node.get("radiusMeters");
        Double radiusMeters = radiusVal.isNull() ? null : radiusVal.asDouble();

        return new ObjectiveNode(
            node.get("id").asString(),
            node.get("name").asString(),
            node.get("priority").asInt(),
            centerLatitude,
            centerLongitude,
            targetEntityId,
            radiusMeters
        );
    }
    
}
