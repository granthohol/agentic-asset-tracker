package com.assettracker.backend.service;

import com.assettracker.backend.model.Drone;
import com.assettracker.backend.model.DroneStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DroneService {
    
    private final List<Drone> drones = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    public DroneService() {
        double MIN_LAT = 38.8;
        double MAX_LAT = 39.2;
        double MIN_LONG = -77.5;
        double MAX_LONG = -77.0;
        int MIN_BAT = 20;
        int MAX_BAT = 100;

        for (int i=1; i < 51; i++) {
            String id = "Drone-" + i;
            double lat = MIN_LAT + (MAX_LAT - MIN_LAT) * random.nextDouble();
            double lon = MIN_LONG + (MAX_LONG - MIN_LONG) * random.nextDouble();
            int batLevel = (int) (MIN_BAT + (MAX_BAT - MIN_BAT) * random.nextDouble());
            DroneStatus status = DroneStatus.ACTIVE;

            drones.add(new Drone(id, lat, lon, batLevel, status)); 
        }
    }

    // return a list of drones with a small random update to the latitude
    // and longitude of each drone
    public List<Drone> getAllDrones() {
        for (int i = 0; i < drones.size(); i++) {
            Drone currDrone = drones.get(i);
            double latDelta = (random.nextDouble() - 0.5) * 0.002;  // random val [-0.001, 0.001]
            double longDelta = (random.nextDouble() - 0.5) * 0.002;

            Drone new_drone = new Drone(currDrone.id(), currDrone.latitude() + latDelta,
                                        currDrone.longitude() + longDelta, currDrone.batteryLevel(),
                                        currDrone.status()); 

            drones.set(i, new_drone);
        }

        return drones;
    }

}
