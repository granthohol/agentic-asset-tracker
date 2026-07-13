package com.assettracker.backend.service;

import com.assettracker.backend.model.Drone;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DroneService {
    
    private final ConcurrentHashMap<String, Drone> droneMap = new ConcurrentHashMap<>();  // droneId -> latest state

    public void updateDroneMap(Drone drone) {
        droneMap.put(drone.id(), drone);
    }

    public List<Drone> getAllDrones() {
        List<Drone> drones = new ArrayList<>();
        droneMap.forEach((id, drone) -> {
            drones.add(drone);
        });
        drones.sort(Comparator.comparing(Drone::id));
        return drones;
    }

    /** Live telemetry for one drone, or null if unknown. */
    public Drone getDrone(String droneId) {
        return droneMap.get(droneId);
    }

}
