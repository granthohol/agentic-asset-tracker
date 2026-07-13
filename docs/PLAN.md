# ExecutionPlan Contract (v2)

The **ExecutionPlan** is the unit of human-approvable intent in Phase 3's Planner-Executor loop. The LLM orchestrator emits one against **read-only** graph tools; the user approves it; `POST /api/execute-plan` publishes it whole to the `plan.events` Kafka topic; and the `PlanExecutor` consumes it and performs every Neo4j write and `SET_WAYPOINT` publish.

The LLM **describes** actions. It never calls a write method. The set of describable actions below **is** the policy — anything not in this table is not executable.

---

## Top-level shape

| Field | Type | Description |
|-------|------|-------------|
| `planId` | string | Correlates the proposal across logs, UI state, and the `plan.events` envelope. |
| `rationale` | string | Human-readable summary rendered in the approval modal. |
| `actions` | `PlanAction[]` | **Ordered** list of operations. The executor walks it linearly. |

```json
{
  "planId": "plan-2026-05-28-001",
  "rationale": "Observe the disturbance and deploy Alpha there.",
  "actions": [ /* PlanAction[] */ ]
}
```

---

## Action vocabulary

Every action is an object with an `op` discriminator. Server-side deserialization rejects any unknown `op`.

| `op` | Required fields | Optional fields | Executes |
|------|-----------------|-----------------|----------|
| `upsertSquadron` | `name`, `sectorId`, and (`id` **xor** `tempId`) | — | `GraphWriter.upsertSquadron` |
| `upsertObjective` | `name`, `priority`, and (`id` **xor** `tempId`) | `centerLatitude`, `centerLongitude`, `radiusMeters`, `targetEntityId` | `GraphWriter.upsertObjective` |
| `assignDroneToSquadron` | `droneId`, `squadronId` | — | `GraphWriter.assignDroneToSquadron` |
| `deploySquadronToObjective` | `squadronId`, `objectiveId` | — | `GraphWriter.deploySquadronToObjective` |
| `removeDroneAssignment` | `droneId` | — | `GraphWriter.removeDroneAssignment` |
| `removeSquadronFromObjective` | `squadronId` | — | `GraphWriter.removeSquadronFromObjective` |
| `setWaypoint` | `droneId`, `targetLat`, `targetLng` | `mission_type` | `CommandPublisher.publishSetWaypoint` **and** `GraphWriter.setDroneWaypoint` |
| `applyFormation` | `formationType`, `centerLat`, `centerLng`, `droneIds` | `mission_type`, `spacingMeters`, `facingLat`, `facingLng` | **Expanded server-side** into N× `setWaypoint` (see note) |
| `clearWaypoint` | `droneId` | — | `GraphWriter.clearDroneWaypoint` |
| `upsertTrack` | `name`, `affiliation`, `domain`, `latitude`, `longitude`, and (`id` **xor** `tempId`) | — | `EntityService.upsertTrack` |
| `upsertWaypoint` | `name`, `latitude`, `longitude`, and (`id` **xor** `tempId`) | — | `EntityService.upsertWaypoint` |
| `upsertZone` | `name`, `type`, `shape`, and (`id` **xor** `tempId`) | CIRCLE: `centerLatitude`, `centerLongitude`, `radiusMeters`; POLYGON: `vertices` (`[[lat,lng],…]`) | `EntityService.upsertZone` |
| `removeTrack` | `id` | — | `EntityService.deleteTrack` |
| `removeWaypoint` | `id` | — | `EntityService.deleteWaypoint` |
| `removeZone` | `id` | — | `EntityService.deleteZone` |

Notes:

- `droneId` is **never** server-minted — drones exist only because telemetry created them. A plan cannot conjure a drone.
- `setWaypoint` is the one action that crosses both planes: it mirrors the target into Neo4j (so reads reflect intent immediately) **and** publishes a motion command to the edge. See [docs/COMMANDS.md](COMMANDS.md) for the `SET_WAYPOINT` wire shape.
- `mission_type` is snake_case on the wire (it matches the command contract); the Java record maps it to `missionType`.
- **`applyFormation` is a compact macro, not a distinct executable.** It exists only to cut the tokens the LLM emits (~2 actions for a two-phase swarm instead of ~100). A `PlanExpander` replaces each `applyFormation` with one `setWaypoint` per drone (geometry from `FormationService`) **before** the plan leaves the orchestrator, and again defensively at the top of the `PlanExecutor`. So `/api/plan` responses, the frontend overlays/mission card, `/api/execute-plan`, and the executor only ever see `setWaypoint`s — the wire contract downstream of the planner is unchanged. `applyFormation` has no direct executor dispatch (its `PlanExecutor` case throws, and is unreachable after expansion).
- **Persistent vs. ephemeral waypoints:** `upsertWaypoint` creates a durable `:Waypoint` map marker (an ontology annotation). `setWaypoint` is unrelated — it is ephemeral drone motion tasking. Use `setWaypoint` to move a drone; use `upsertWaypoint` to place a labeled point of interest.
- The `upsert*`/`remove*` **entity** ops route through `EntityService` (not `GraphWriter` directly), so each write persists to Neo4j **and** broadcasts over `/ws/entities` to the live map exactly like a manual edit. Discovery of existing entity ids is done via the read tools `list_tracks` / `list_waypoints` / `list_zones` (and `get_*_by_id`).
- `remove*` ops require a literal id (no `$ref`); an Objective's `targetEntityId` may reference a track/zone id (including a `$tempId` created earlier in the same plan).

---

## Temporary ids (`tempId` / `$tempId`)

A plan often creates an entity and then references it in a later action. Since the real Neo4j id doesn't exist until execution time, the plan uses placeholders:

1. An `upsert*` action declares a placeholder with `"tempId": "obj-1"` (and **no** `id`).
2. Any later action references it by prefixing with `$`: `"objectiveId": "$obj-1"`.
3. At execution time the `PlanExecutor` mints a real id for the upsert, stores `obj-1 -> objective-3f2a8c19` in a per-plan `idResolutionMap`, and substitutes it wherever `$obj-1` appears.

**Rules**

- A `$ref` MUST resolve to a `tempId` **declared earlier in `actions[]`** (order matters).
- `id` and `tempId` are mutually exclusive on an upsert: `id` updates an existing node; `tempId` creates a new one.
- A literal (non-`$`) id argument MUST already exist in Neo4j; the executor does not create it.

### Worked example

```json
{
  "planId": "plan-001",
  "rationale": "Observe the disturbance and deploy Alpha there.",
  "actions": [
    { "op": "upsertObjective", "tempId": "obj-1", "name": "Observe", "priority": 1,
      "centerLatitude": 39.05, "centerLongitude": -77.18, "radiusMeters": 250 },
    { "op": "deploySquadronToObjective", "squadronId": "squadron-alpha", "objectiveId": "$obj-1" },
    { "op": "setWaypoint", "droneId": "drone-007", "targetLat": 39.05, "targetLng": -77.18, "mission_type": "RECON" }
  ]
}
```

Executor trace:

1. `upsertObjective` has no `id`, `tempId="obj-1"` → mint `objective-3f2a8c19`, write it, record `obj-1 -> objective-3f2a8c19`.
2. `deploySquadronToObjective` sees `objectiveId="$obj-1"` → resolve to `objective-3f2a8c19`, deploy `squadron-alpha`.
3. `setWaypoint` → publish a keyed `SET_WAYPOINT` command for `drone-007` and mirror the waypoint into the graph.

---

## Where validation happens

| Concern | Layer |
|---------|-------|
| `op` is a known action; field types parse | Jackson deserialization (`PlanAction` sealed type) |
| `id` xor `tempId`; every `$ref` resolvable from earlier `tempId`s; coords in bounds | `POST /api/execute-plan` handler (rejects with `4xx` before publishing) |
| Literal ids actually exist in Neo4j; `$ref` substitution; mint + record real ids | `PlanExecutor` (at consume time) |

The record types themselves stay pure data so the parse step is a faithful mirror of the wire JSON.

---

## Java types

- `com.assettracker.backend.agent.plan.ExecutionPlan` — `record(planId, rationale, actions)`.
- `com.assettracker.backend.agent.plan.PlanAction` — `sealed interface` with one nested `record` per `op`, dispatched via Jackson `@JsonTypeInfo(use = NAME, property = "op")` + `@JsonSubTypes`.

Adding a capability = adding one permitted record + one `@JsonSubTypes.Type` + one executor dispatch case. Nothing else can become executable by accident.

---

## Formations (planner tools + the `applyFormation` macro)

Swarm layouts are computed by read-only tools (`list_formations`, `preview_formation`, `preview_two_phase`) — see [FORMATIONS.md](FORMATIONS.md). For a two-phase swarm the planner calls `preview_two_phase` once (compact FORM_UP + ADVANCE centers) and emits two `applyFormation` actions. The backend expands them into N× `setWaypoint` before execution, so the plan **still executes as `setWaypoint`s**; there is no `formFormation` op and no per-slot geometry on the wire from the model.
