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
                MATCH (o:Objectives)
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
            Result res = session.run("MATCH (d:Drone {id: $id})-[:ASSIGNED_TO]->(s:Squadron) OPTIONAL MATCH (s)-[:DEPLOYED_FOR]->(o:Objective) RETURN d, s, o", 
                                            Values.parameters("id", id)
            ); 

            if (!res.hasNext()) {
                return Optional.empty();
            }

            Record rec = res.next();

            ObjectiveNode obj = null; 
            if (!rec.get("o").isNull()){
                obj = mapObjectiveNode(rec.get("o").asNode());
            }

            return Optional.of(new DroneDetail(
                mapDroneNode(rec.get("d").asNode()),
                mapSquadronNode(rec.get("s").asNode()),
                obj
            ));
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
                MATCH (d:Drone)-[:ASSIGNED_TO]->(s {sectorId: $sectorId}) WHERE
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
    
    // Mapping a neo4j cypher return type 'node' to the DroneNode object
    private DroneNode mapDroneNode(Node node) {
        DroneStatus status = DroneStatus.valueOf(node.get("status").asString());
    
        // map from node.get("currentWaypoint") if present
        Waypoint currentWaypoint = null; 
        var wp = node.get("currentWaypoint");
        if (!wp.isNull()) {
            var map = wp.asMap();
            currentWaypoint = new Waypoint(
                ((Number) map.get("lat")).doubleValue(),
                ((Number) map.get("lng")).doubleValue()
            );
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
        return new ObjectiveNode(
            node.get("id").asString(),
            node.get("name").asString(),
            node.get("priority").asInt()
        );
    }
    
}
