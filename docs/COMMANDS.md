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

## V1 command type: `SET_WAYPOINT`

| Field | Type | Description |
|-------|------|-------------|
| `droneId` | string | Target drone (must match telemetry id space). |
| `targetLat` | double | Destination latitude. |
| `targetLng` | double | Destination longitude. |
| `mission_type` | string | Mission label for the maneuver (e.g. `RETURN_TO_BASE`). |
| `issuedAt` | number | Unix epoch milliseconds when the command was issued. |
| `commandId` | string | Unique id for deduplication on the edge consumer. |

### Example command message value

```json
{
  "droneId": "drone-042",
  "targetLat": 39.0,
  "targetLng": -77.2,
  "mission_type": "RETURN_TO_BASE",
  "issuedAt": 1715432100123,
  "commandId": "cmd-8f3a2b1c-4d5e-6f7a-8b9c-0d1e2f3a4b5c"
}
```

**Publish path:** `POST /api/commands` validates and publishes to this topic. **Do not** publish from `POST /api/plan` — the plan endpoint is read-only until the operator approves.

---

## ExecutionPlan (`POST /api/plan`)

The orchestrator returns a **proposed** plan JSON. The React client renders ghost paths from `commands`; only after approval does the client call `POST /api/commands`.

| Field | Type | Description |
|-------|------|-------------|
| `planId` | string | Correlates this proposal (logging, UI state). |
| `rationale` | string | Human-readable summary from the planner. |
| `commands` | array | List of intended waypoint changes (same shape as Kafka commands, plus `reason`). |

Each element of `commands`:

| Field | Type | Description |
|-------|------|-------------|
| `droneId` | string | Target drone. |
| `targetLat` | double | Proposed latitude. |
| `targetLng` | double | Proposed longitude. |
| `mission_type` | string | Mission label for this leg. |
| `reason` | string | Why this drone was included (for the approval modal). |

### Example ExecutionPlan (pretty-printed)

```json
{
  "planId": "plan-2026-05-21-001",
  "rationale": "Send low-battery drones in Sector 1 back to base.",
  "commands": [
    {
      "droneId": "drone-007",
      "targetLat": 39.0,
      "targetLng": -77.2,
      "mission_type": "RETURN_TO_BASE",
      "reason": "batteryLevel below threshold in sector-1"
    },
    {
      "droneId": "drone-042",
      "targetLat": 39.0,
      "targetLng": -77.2,
      "mission_type": "RETURN_TO_BASE",
      "reason": "batteryLevel below threshold in sector-1"
    }
  ]
}
```

### Request to `POST /api/plan`

```json
{
  "command": "Send all low-battery drones in Sector A back to base"
}
```

---

## Endpoints (summary)

| Method | Path | Mutates state? | Role |
|--------|------|----------------|------|
| `POST` | `/api/plan` | No | LLM tool loop → `ExecutionPlan` JSON. |
| `POST` | `/api/commands` | Yes (via Kafka) | Publishes approved commands to `drone.commands.v1`. |
