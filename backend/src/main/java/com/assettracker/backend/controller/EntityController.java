package com.assettracker.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assettracker.backend.entity.EntityService;
import com.assettracker.backend.entity.EntitySnapshot;
import com.assettracker.backend.graph.Affiliation;
import com.assettracker.backend.graph.TrackDomain;
import com.assettracker.backend.graph.TrackNode;
import com.assettracker.backend.graph.WaypointNode;
import com.assettracker.backend.graph.ZoneNode;
import com.assettracker.backend.graph.ZoneShape;
import com.assettracker.backend.graph.ZoneType;

/**
 * REST CRUD for tracks, waypoints, and zones. Writes go through {@link EntityService}.
 * Jackson handles bad enums; geometry validation lives here (PlanValidator is stricter).
 */
@RestController
@RequestMapping("/api")
public class EntityController {

    private final EntityService entityService;

    public EntityController(EntityService entityService) {
        this.entityService = entityService;
    }

    @GetMapping("/entities")
    public EntitySnapshot entities() {
        return entityService.snapshot();
    }

    // Tracks

    public record TrackRequest(
        String name, Affiliation affiliation, TrackDomain domain, Double latitude, Double longitude) {}

    @PostMapping("/tracks")
    public ResponseEntity<Object> createTrack(@RequestBody TrackRequest r) {
        return upsertTrack(null, r);
    }

    @PutMapping("/tracks/{id}")
    public ResponseEntity<Object> updateTrack(@PathVariable String id, @RequestBody TrackRequest r) {
        return upsertTrack(id, r);
    }

    @DeleteMapping("/tracks/{id}")
    public ResponseEntity<Object> deleteTrack(@PathVariable String id) {
        entityService.deleteTrack(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Object> upsertTrack(String id, TrackRequest r) {
        String err = validateTrack(r);
        if (err != null) {
            return badRequest(err);
        }
        return ResponseEntity.ok(entityService.upsertTrack(new TrackNode(
            id, r.name(), r.affiliation(), r.domain(), r.latitude(), r.longitude())));
    }

    // Waypoints

    public record WaypointRequest(String name, Double latitude, Double longitude) {}

    @PostMapping("/waypoints")
    public ResponseEntity<Object> createWaypoint(@RequestBody WaypointRequest r) {
        return upsertWaypoint(null, r);
    }

    @PutMapping("/waypoints/{id}")
    public ResponseEntity<Object> updateWaypoint(@PathVariable String id, @RequestBody WaypointRequest r) {
        return upsertWaypoint(id, r);
    }

    @DeleteMapping("/waypoints/{id}")
    public ResponseEntity<Object> deleteWaypoint(@PathVariable String id) {
        entityService.deleteWaypoint(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Object> upsertWaypoint(String id, WaypointRequest r) {
        if (isBlank(r.name())) {
            return badRequest("name is required");
        }
        String coordErr = validateCoord(r.latitude(), r.longitude());
        if (coordErr != null) {
            return badRequest(coordErr);
        }
        return ResponseEntity.ok(entityService.upsertWaypoint(new WaypointNode(
            id, r.name(), r.latitude(), r.longitude())));
    }

    // Zones

    public record ZoneRequest(
        String name,
        ZoneType type,
        ZoneShape shape,
        Double centerLatitude,
        Double centerLongitude,
        Double radiusMeters,
        List<List<Double>> vertices) {}

    @PostMapping("/zones")
    public ResponseEntity<Object> createZone(@RequestBody ZoneRequest r) {
        return upsertZone(null, r);
    }

    @PutMapping("/zones/{id}")
    public ResponseEntity<Object> updateZone(@PathVariable String id, @RequestBody ZoneRequest r) {
        return upsertZone(id, r);
    }

    @DeleteMapping("/zones/{id}")
    public ResponseEntity<Object> deleteZone(@PathVariable String id) {
        entityService.deleteZone(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Object> upsertZone(String id, ZoneRequest r) {
        if (isBlank(r.name())) {
            return badRequest("name is required");
        }
        if (r.type() == null || r.shape() == null) {
            return badRequest("type and shape are required");
        }

        if (r.shape() == ZoneShape.CIRCLE) {
            String coordErr = validateCoord(r.centerLatitude(), r.centerLongitude());
            if (coordErr != null) {
                return badRequest("circle center: " + coordErr);
            }
            if (r.radiusMeters() == null || r.radiusMeters() <= 0) {
                return badRequest("circle requires radiusMeters > 0");
            }
            return ResponseEntity.ok(entityService.upsertZone(new ZoneNode(
                id, r.name(), r.type(), r.shape(),
                r.centerLatitude(), r.centerLongitude(), r.radiusMeters(),
                new double[0], new double[0])));
        }

        if (r.vertices() == null || r.vertices().size() < 3) {
            return badRequest("polygon requires at least 3 vertices");
        }
        double[] lats = new double[r.vertices().size()];
        double[] lngs = new double[r.vertices().size()];
        for (int i = 0; i < r.vertices().size(); i++) {
            List<Double> v = r.vertices().get(i);
            if (v == null || v.size() != 2 || v.get(0) == null || v.get(1) == null) {
                return badRequest("polygon vertex " + i + " must be [lat, lng]");
            }
            String coordErr = validateCoord(v.get(0), v.get(1));
            if (coordErr != null) {
                return badRequest("polygon vertex " + i + ": " + coordErr);
            }
            lats[i] = v.get(0);
            lngs[i] = v.get(1);
        }
        return ResponseEntity.ok(entityService.upsertZone(new ZoneNode(
            id, r.name(), r.type(), r.shape(), null, null, null, lats, lngs)));
    }

    // Validation helpers

    private String validateTrack(TrackRequest r) {
        if (isBlank(r.name())) {
            return "name is required";
        }
        if (r.affiliation() == null || r.domain() == null) {
            return "affiliation and domain are required";
        }
        return validateCoord(r.latitude(), r.longitude());
    }

    private String validateCoord(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return "latitude and longitude are required";
        }
        if (lat < -90 || lat > 90) {
            return "latitude out of range [-90, 90]";
        }
        if (lng < -180 || lng > 180) {
            return "longitude out of range [-180, 180]";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
