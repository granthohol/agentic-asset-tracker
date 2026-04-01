package com.assettracker.backend.controller;

import com.assettracker.backend.model.Drone;
import com.assettracker.backend.service.DroneService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DroneController {
    private final DroneService droneService;

    public DroneController(DroneService droneService) {
        this.droneService = droneService;
    }
    
    // runs on "GET /drones" request
    @GetMapping("/drones")
    public List<Drone> getDrones() {
        return droneService.getAllDrones();
    }
}
