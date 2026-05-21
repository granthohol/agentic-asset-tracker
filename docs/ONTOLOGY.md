# Ontology Schema (Nodes and Edges)

We model the fleet as a **graph** in Neo4j, not a flat list of drones. Nodes hold identity and attributes; edges express assignment and deployment. Telemetry still updates drone position and battery; this schema captures **who belongs to whom** and **what they are deployed for**.

---

## Nodes

| Node | Fields | Notes |
|------|--------|-------|
| **Drone** | `id`, `lat`, `lng`, `batteryLevel`, `status`, `currentWaypoint?` | `currentWaypoint` is optional; set when a command steers the drone toward a target. |
| **Squadron** | `id`, `name`, `sectorId` | Groups drones by operational area (e.g. sector for planner queries). |
| **Objective** | `id`, `name`, `priority` | Mission the squadron is deployed for. |

### Example node properties (conceptual)

```json
{
  "id": "drone-042",
  "lat": 39.012345,
  "lng": -77.123456,
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
  "priority": 1
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

---

## How this fits the pipeline

- **Read path:** Graph queries power LLM tools (`get_drones_in_squadron`, `get_low_battery_drones`, etc.) and `POST /api/plan`.
- **Write path:** Telemetry upserts drone state; commands update `currentWaypoint`; assignments are seeded or managed separately from the telemetry stream.
