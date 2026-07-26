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
| `setRoute` | `droneId`, `legs` (`[[lat,lng],…]`, length ≥ 1) | `mission_type` (final leg) | Sequential `SET_WAYPOINT`s; intermediate legs use `TRANSIT`; waits for arrival between legs |
| `applyFormation` | `formationType`, `centerLat`, `centerLng`, `droneIds` | `mission_type`, `spacingMeters`, `facingLat`, `facingLng` | **Expanded server-side** into N× `setWaypoint` (see note) |
| `applyFormationRoute` | `formationType`, `droneIds`, `formUpLat`, `formUpLng`, `destLat`, `destLng` | `mission_type`, `spacingMeters` | **Expanded server-side** into FORM_UP → HOLD* → ADVANCE `setWaypoint` waves, routed around RESTRICTED CIRCLE zones (see note) |
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
- **`setRoute`** is multi-leg motion for one drone. Legs come from the `plan_route` tool (avoids RESTRICTED CIRCLE zones). The executor publishes each leg as `SET_WAYPOINT` and waits for arrival before the next; it is **not** flattened to concurrent `setWaypoint`s (that would last-wins). Use `setRoute` for a single drone with no formation.
- `mission_type` is snake_case on the wire (it matches the command contract); the Java record maps it to `missionType`.
- **`applyFormation` and `applyFormationRoute` are compact macros, not distinct executables.** They exist only to cut the tokens the LLM emits (a couple of actions for a swarm move instead of ~N per drone per wave). `PlanExpander` replaces each with one `setWaypoint` per drone per wave (geometry from `FormationService`) **before** the plan leaves the orchestrator, and again defensively at the top of the `PlanExecutor`. Contiguous `setWaypoint` waves with the same `mission_type` wait for arrival before the next wave. Neither op has a direct executor dispatch.
- **`applyFormationRoute` owns swarm avoidance end to end.** Given a form-up point and a destination, `PlanExpander` calls `ZoneRouter` itself (obstacles from `RestrictedZoneObstacles`, which reads every RESTRICTED CIRCLE zone) and expands the result into FORM_UP → HOLD\* (one per detour leg) → ADVANCE waves — each wave carrying every id in `droneIds`. The planner (model or stub) never computes detour coordinates and never has to keep multiple `applyFormation` calls in sync with each other; there is exactly one action to get right, and the executor-side wave grouping makes it structurally impossible for a subset of the swarm to be left behind. If `formUpLat/formUpLng` sits inside a RESTRICTED zone's buffer, expansion throws rather than silently flying through it.
- **Two or more HOLD legs get distinct `mission_type`s: `HOLD_1`, `HOLD_2`, ...** (a single HOLD leg stays plain `HOLD`). `PlanExecutor` — and the frontend's overlay logic — rediscover wave boundaries by grouping *contiguous* `setWaypoint`s that share an identical `mission_type` string; two zones (or one zone needing two detour points) both labeled bare `HOLD` would merge into a single wave, publish leg 2's coordinates as an immediate same-wave overwrite of leg 1 for every drone, and then wait out the full arrival timeout for a leg-1 position no drone would ever reach. Anything that treats `HOLD`-as-a-form-up-style wave (e.g. gating logic) must match on a `HOLD` *prefix*, not exact equality.
- **Every obstacle is inflated by the formation's footprint radius before routing**, not just `ZoneRouter`'s own fixed buffer. `ZoneRouter` only guarantees the route's *center line* clears each zone; a wide formation (e.g. a many-drone WEDGE can span 800+ m from center at default spacing) can otherwise put an off-center drone's waypoint — or the straight line it flies between two waypoints — inside a zone the center path cleared. `FormationService.footprintRadiusMeters` returns the max center-to-slot distance for the chosen type/count/spacing, and `PlanExpander` adds that to every obstacle's radius before calling `ZoneRouter`, so every drone (not just the formation center) keeps the router's clearance margin.

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

## Formations (planner tools + the formation macros)

Swarm layouts are computed by read-only tools (`list_formations`, `preview_formation`, `preview_two_phase`) — see [FORMATIONS.md](FORMATIONS.md). For a two-phase swarm the planner calls `preview_two_phase` once (compact FORM_UP + ADVANCE centers, or a named rally waypoint in place of the computed FORM_UP center) and emits a single `applyFormationRoute` action. The backend expands it into FORM_UP → HOLD\* → ADVANCE `setWaypoint`s before execution, so the plan **still executes as `setWaypoint`s**; there is no per-slot geometry and no route geometry on the wire from the model. A single-phase formation move with nothing to avoid may still use a plain `applyFormation`.
