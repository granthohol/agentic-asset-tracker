# Ontology Schema (Nodes and Edges)

We model the fleet as a **graph** in Neo4j, not a flat list of drones. Nodes hold identity and attributes; edges express assignment and deployment. Telemetry still updates drone position and battery; this schema captures **who belongs to whom** and **what they are deployed for**.

---

## Nodes

| Node | Fields | Notes |
|------|--------|-------|
| **Drone** | `id`, `latitude`, `longitude`, `batteryLevel`, `status`, `currentWaypoint?` | `currentWaypoint` is optional; set when a command steers the drone toward a target. |
| **Squadron** | `id`, `name`, `sectorId` | Groups drones by operational area (e.g. sector for planner queries). |
| **Objective** | `id`, `name`, `priority`, `centerLatitude?`, `centerLongitude?`, `targetEntityId?`, `radiusMeters?` | Mission the squadron is deployed for. Location fields are all optional — many objectives have no fixed location (see below). |

### Example node properties (conceptual)

```json
{
  "id": "drone-042",
  "latitude": 39.012345,
  "longitude": -77.123456,
  "batteryLevel": 87,
  "status": "ACTIVE",
  "currentWaypoint": { "lat": 39.0, "lng": -77.2 }
}
```

```json
{
  "id": "squadron-alpha",
  "name": "Alpha",
  "sectorId": "sector-1"
}
```

```json
{
  "id": "objective-recon",
  "name": "Recon",
  "priority": 1,
  "centerLatitude": 39.05,
  "centerLongitude": -77.18,
  "radiusMeters": 1500
}
```

---

## Objective location semantics

Objectives describe **what** a squadron is doing, not always **where**. The four optional location fields cover the three common shapes a mission can take. Any combination not listed below should be treated as an authoring error.

| Combination | Meaning | Example |
|-------------|---------|---------|
| **None set** | Abstract / role-based objective with no geography. | `standby`, `comms-relay-capability`, `counter-jamming` |
| **`centerLatitude` + `centerLongitude`** (no `radiusMeters`) | Single point objective. | `drop-sensor-at`, `surveil-building`, `stage-at-rally` |
| **`centerLatitude` + `centerLongitude` + `radiusMeters`** | Area objective: the point is the center, `radiusMeters` is the area to monitor / patrol. | `patrol-sector-2`, `search-grid-alpha` |
| **`targetEntityId`** (no center) | Follow / shadow / cover a moving entity by id. The entity provides the live position. | `escort-drone-007`, `shadow-convoy-bravo` |
| **`targetEntityId` + `radiusMeters`** | Maintain a standoff distance around a moving entity. | `air-defense-around-qrf-200m` |

**`targetEntityId` typing.** Stored as a plain `String` so it can reference any node id in the graph (drone, squadron, future entity types) without introducing a separate label or enum. The planner is responsible for resolving the id to a live position via the relevant read tool (`getDroneById`, `getSquadronById`, etc.). A future schema bump can add a `targetEntityType` discriminator if cross-type references become ambiguous.

**`radiusMeters` typing.** Meters (not km) so it matches the unit `getDronesNear` already passes to Neo4j's `point.distance(...)`.

### Examples of valid Objective shapes

Area patrol:

```json
{
  "id": "objective-patrol-2",
  "name": "Patrol Sector 2",
  "priority": 2,
  "centerLatitude": 39.10,
  "centerLongitude": -77.05,
  "radiusMeters": 2000
}
```

Escort moving entity:

```json
{
  "id": "objective-escort-007",
  "name": "Escort drone-007",
  "priority": 1,
  "targetEntityId": "drone-007",
  "radiusMeters": 250
}
```

Abstract / standby:

```json
{
  "id": "objective-standby",
  "name": "Standby",
  "priority": 5
}
```

---

## Edges

| Relationship | Pattern | Meaning |
|--------------|---------|---------|
| **ASSIGNED_TO** | `(:Drone)-[:ASSIGNED_TO]->(:Squadron)` | Drone is assigned to exactly one squadron. |
| **DEPLOYED_FOR** | `(:Squadron)-[:DEPLOYED_FOR]->(:Objective)` | Squadron is deployed for an objective (if any). |

```cypher
(:Drone)-[:ASSIGNED_TO]->(:Squadron)
(:Squadron)-[:DEPLOYED_FOR]->(:Objective)
```

---

## Rules

1. A **Drone** has exactly one **Squadron**; a **Squadron** has zero or one active **Objective**.
2. **`Drone.id`** is the same string the [telemetry contract](TELEMETRY.md) already uses (e.g. `drone-042`). **Squadron** and **Objective** IDs are server-assigned at seed time.
3. An **Objective**'s location fields are all optional and independently nullable, but only the combinations listed in *Objective location semantics* above are considered valid by the planner.

---

## How this fits the pipeline

- **Read path:** Graph queries power LLM tools (`get_drones_in_squadron`, `get_low_battery_drones`, etc.) and `POST /api/plan`. When an objective carries coordinates, the planner can use them as a grounded source of `targetLat / targetLng` for `SET_WAYPOINT` commands.
- **Write path:** Telemetry upserts drone state; commands update `currentWaypoint`; assignments are seeded or managed separately from the telemetry stream.
