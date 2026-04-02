import {MapContainer, TileLayer} from 'react-leaflet';
import { useState, useEffect } from 'react';

import type { Drone } from '../types/drone'

export default function DroneMap() {
    const [drones, setDrones] = useState<Drone[]>([]);
    useEffect(() => {
        // This code runs exactly ONE time when the map first appears.
        const timer = setInterval(() => {
            
            // Fetch the JSON from Spring Boot backend
            fetch('http://localhost:8080/api/drones')
                .then(response => response.json())
                .then(data => {
                    // It trips the useState wire, moving the drones on the map
                    setDrones(data);
                });

        }, 1000);   // loop every 1000ms (one second)

        // The Cleanup: If the user navigates away from the map, this destroys the timer.
        return () => clearInterval(timer);

    }, []); // [] -> runs once on mount, never again

    return (
        <MapContainer
            center={[39.0, -77.2]}
            zoom = {11}
            style = {{ height: "100vh", width: "100%" }}
        > 
            <TileLayer
                attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />
        </MapContainer>
    );
}