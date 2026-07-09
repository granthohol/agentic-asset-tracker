package com.assettracker.backend.service;

import com.assettracker.backend.model.Drone;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DroneService {
    
    private final ConcurrentHashMap<String, Drone> droneMap = new ConcurrentHashMap<>();  // thread-safe map of droneId -> Drone object

    public void updateDroneMap(Drone drone) {
        droneMap.put(drone.id(), drone);  // put the drone into the map; overwrite the existing drone if it already exists
    }

    public List<Drone> getAllDrones() {
        List<Drone> drones = new ArrayList<>();
        droneMap.forEach((id, drone) -> {
            drones.add(drone);
        });
        // sort the drones by id
        drones.sort(Comparator.comparing(Drone::id));
        return drones;
    }

    /** Live telemetry snapshot for one drone, or null if unknown. */
    public Drone getDrone(String droneId) {
        return droneMap.get(droneId);
    }

}
