# Command and Plan Contracts

Phase 3 adds a **bidirectional write-path**: the backend publishes **commands** to Kafka; the Python edge simulator consumes them and steers drones. Separately, **`POST /api/plan`** returns a proposed **ExecutionPlan** for human approval before any command is published.

---

## Topic: `drone.commands.v1`

| Setting | Value |
|---------|--------|
| **Topic name** | `drone.commands.v1` |
| **Partition key** | `droneId` (per-drone ordering, same logic as [telemetry](TELEMETRY.md)) |

Commands are the inverse of telemetry: **intent flows from Java → Kafka → Python**, then position changes appear again on `drone.telemetry.v1`.

---

## V1 command types

Every command includes:

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | `SET_WAYPOINT` or `CLEAR_WAYPOINT`. |
| `droneId` | string | Target drone (must match telemetry id space). |
| `issuedAt` | number | Unix epoch milliseconds when the command was issued. |
| `commandId` | string | Unique id for deduplication on the edge consumer. |

### `SET_WAYPOINT`

| Field | Type | Description |
|-------|------|-------------|
| `targetLat` | double | Destination latitude. |
| `targetLng` | double | Destination longitude. |
| `mission_type` | string | Mission label for the maneuver (see below). |

### `mission_type` semantics (edge)

| Value | On arrival |
|-------|------------|
| `FORM_UP` / `HOLD` | Snap to target and **loiter** (keep waypoint; no random walk). Used for formation assembly. |
| `ADVANCE` / `RECON` / other | Snap to target and **clear** waypoint (resume free movement). |

### Example `SET_WAYPOINT`

```json
{
  "type": "SET_WAYPOINT",
  "droneId": "drone-042",
  "targetLat": 39.0,
  "targetLng": -77.2,
  "mission_type": "FORM_UP",
  "issuedAt": 1715432100123,
  "commandId": "cmd-8f3a2b1c-4d5e-6f7a-8b9c-0d1e2f3a4b5c"
}
```

### `CLEAR_WAYPOINT`

Clears the active target so the edge resumes free movement. No lat/lng fields.

```json
{
  "type": "CLEAR_WAYPOINT",
  "droneId": "drone-042",
  "issuedAt": 1715432100456,
  "commandId": "cmd-…"
}
```

**Publish path:** Java `CommandPublisher` only — called by `PlanExecutor` (approved plans) and `MissionCancelService` (`POST /api/cancel-mission` for HITL Stop). The browser never publishes to Kafka directly.

---

## ExecutionPlan

> **Superseded.** Phase 3 uses a **polymorphic** `ExecutionPlan` (`actions[]` with an
> `op` discriminator) that intermixes graph mutations and motion, not the old flat
> `commands[]` shape. The full contract now lives in **[docs/PLAN.md](PLAN.md)**.

`SET_WAYPOINT` above is the **internal command-event layer** (Java → Kafka → Python).
`ExecutionPlan` is the **user-intent layer** (LLM proposal → human approval). They are
deliberately different: a `setWaypoint` action in a plan is what *eventually* produces a
`SET_WAYPOINT` command event, but only after approval and only from inside the executor.

---

## Endpoints (summary)

| Method | Path | Mutates state? | Role |
|--------|------|----------------|------|
| `POST` | `/api/plan` | No | LLM tool loop → proposed `ExecutionPlan` JSON (read-only). See [PLAN.md](PLAN.md). |
| `POST` | `/api/execute-plan` | No (publishes only) | Validates an **approved** plan and publishes it to the `plan.events` Kafka topic; returns `202 Accepted`. |
| `POST` | `/api/cancel-mission` | Yes (clear) | Body `{ "droneIds": [...] }` — clears Neo4j waypoints and publishes `CLEAR_WAYPOINT` per drone (HITL Stop). |
| _(none)_ | ~~`/api/commands`~~ | — | **Removed by design.** Motion commands go through `CommandPublisher` only. |

The actual Neo4j writes and `drone.commands.v1` publishes happen asynchronously in the
`PlanExecutor` (`@KafkaListener` on `plan.events`) — the single auditable write seam.
