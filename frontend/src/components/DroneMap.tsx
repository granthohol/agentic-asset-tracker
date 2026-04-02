import {MapContainer, TileLayer} from 'react-leaflet';

export default function DroneMap() {
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