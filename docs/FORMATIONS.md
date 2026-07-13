# Formation planning tools

Read-only tools that let the planner (stub or real LLM) choose a geometric layout for a swarm. For two-phase swarms the planner calls `preview_two_phase` once and emits two compact `applyFormation` actions; the backend expands each into per-drone `setWaypoint`s before execution. Formations **never** mutate Neo4j or publish Kafka commands by themselves.

> **Token diet.** `list_drones` returns a compact columnar table `{ "fields": ["id","lat","lng"], "rows": [["drone-000", 43.07311, -89.40121], …] }` (id + position only, coords rounded to 5 dp). Battery/status are dropped — use `get_drones_by_status` / `get_low_battery_drones` for those. All formation-tool coordinates are likewise rounded to 5 dp.

## Catalog

| Type | Layout | Default spacing |
|------|--------|-----------------|
| `RING` | Even circle around the center | ~200 m |
| `WEDGE` | V with apex leading toward the facing point | ~200 m |
| `LINE` | Picket perpendicular to the facing direction | ~200 m |

Optional `facingLatitude` / `facingLongitude` on `preview_formation` rotate WEDGE/LINE so they point at the target (e.g. the AOI). RING is unchanged visually.

Each type accepts 1–50 drones (preview clamps to `maxDrones`). Swarm size comes from the
prompt: named ids (`drone-000`), a count (`5 drones` / `swarm of 3`), or **all available**
drones when unspecified.

## Two-phase swarm (form up → advance)

For prompts like “observe the disturbance at lat,lng with a swarm in a wedge”:

1. Call `preview_two_phase(formationType, droneIds, aoiLat, aoiLng)` **once**. It resolves the leader (first drone id) position from the graph, computes the FORM_UP standoff via `FormationService.standoffCenter(AOI, leaderPos, ~2000m)`, and returns a compact summary `{ formationType, droneCount, formUpCenter{lat,lng}, advanceCenter{lat,lng} }` — no per-slot list.
2. Emit two `applyFormation` actions (see [PLAN.md](PLAN.md)): the first at `formUpCenter` with `mission_type: "FORM_UP"` and `facingLat/facingLng` = the AOI; the second at `advanceCenter` with `mission_type: "ADVANCE"`.
3. The backend `PlanExpander` expands each `applyFormation` into one `setWaypoint` per drone (FORM_UP wave then ADVANCE wave), so the plan the operator approves is an ordinary list of `setWaypoint`s.
4. On approve, `PlanExecutor` publishes all FORM_UP commands, **waits** until telemetry shows the swarm has formed (or ~90s timeout), then publishes ADVANCE.

`preview_formation` (per-phase) is still available for single-formation asks, but two-phase swarms should prefer `preview_two_phase` + `applyFormation` (far fewer tokens in and out of the model).

Edge behavior: every mission type **loiters** on arrival (holds station at its waypoint until a new `SET_WAYPOINT` or an explicit `CLEAR_WAYPOINT`); see [COMMANDS.md](COMMANDS.md).

## Tools

### `list_formations`

No args. Returns `FormationSpec[]`: `type`, `name`, `description`, `minDrones`, `maxDrones`, `defaultSpacingMeters`.

### `preview_formation`

| Arg | Required | Description |
|-----|----------|-------------|
| `type` | yes | `RING` \| `WEDGE` \| `LINE` |
| `centerLatitude` | yes | Formation center |
| `centerLongitude` | yes | Formation center |
| `droneIds` | yes | Ordered drone ids (from `list_drones`) |
| `spacingMeters` | no | Override default spacing |
| `facingLatitude` | no | Point the formation should face (e.g. AOI) |
| `facingLongitude` | no | Point the formation should face (e.g. AOI) |

Returns:

```json
{
  "type": "WEDGE",
  "centerLat": 39.03,
  "centerLng": -77.18,
  "spacingMeters": 200.0,
  "slots": [
    { "droneId": "drone-000", "targetLat": 39.03, "targetLng": -77.18 }
  ]
}
```

### `preview_two_phase`

Plans a whole two-phase swarm approach in one call (the token-efficient path for swarms).

| Arg | Required | Description |
|-----|----------|-------------|
| `formationType` | yes | `RING` \| `WEDGE` \| `LINE` |
| `droneIds` | yes | Ordered drone ids (leader first), from `list_drones` |
| `aoiLat` | yes | Area-of-interest (objective / disturbance) latitude |
| `aoiLng` | yes | Area-of-interest longitude |
| `spacingMeters` | no | Override default spacing |

Returns a **summary only** (no slot list), coords rounded to 5 dp:

```json
{
  "formationType": "WEDGE",
  "droneCount": 6,
  "formUpCenter": { "lat": 39.03203, "lng": -77.18 },
  "advanceCenter": { "lat": 39.05, "lng": -77.18 }
}
```

Feed `formUpCenter` / `advanceCenter` straight into two `applyFormation` actions.

## Planner contract

1. Choose a type (`list_formations`) and drones (`list_drones`) for swarm/formation requests.
2. Two-phase swarm: call `preview_two_phase` once, then emit two `applyFormation` actions (FORM_UP at `formUpCenter` facing the AOI, ADVANCE at `advanceCenter`). Single formation: `preview_formation` + per-slot `setWaypoint`.
3. The backend expands `applyFormation` → per-drone `setWaypoint` (see [PLAN.md](PLAN.md)).
4. Human approves → executor gates ADVANCE on form-up arrival.

Do **not** invent lat/lng offsets in the model — geometry is owned by `FormationService`.
