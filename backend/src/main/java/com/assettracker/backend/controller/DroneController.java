package com.assettracker.backend.controller;

import com.assettracker.backend.graph.DroneDetail;
import com.assettracker.backend.graph.GraphService;
import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DroneController {
    private final DroneService droneService;
    private final GraphService graphService;

    public DroneController(DroneService droneService, GraphService graphService) {
        this.droneService = droneService;
        this.graphService = graphService;
    }
    
    @GetMapping("/drones")
    public List<Drone> getDrones() {
        return droneService.getAllDrones();
    }

    /** Hydrated drone from Neo4j: squadron assignment and deployed objective, if any. */
    @GetMapping("/drones/{id}")
    public ResponseEntity<DroneDetail> getDrone(@PathVariable String id) {
        return graphService.getDroneById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
