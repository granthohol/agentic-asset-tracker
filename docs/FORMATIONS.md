# Formation planning tools

Read-only tools that let the planner (stub or real LLM) choose a geometric layout for a swarm. For two-phase swarms the planner calls `preview_two_phase` once and emits a single `applyFormationRoute` action; the backend computes the full FORM_UP → HOLD\* → ADVANCE route itself (avoiding RESTRICTED CIRCLE zones) and expands it into per-drone `setWaypoint`s before execution. Formations **never** mutate Neo4j or publish Kafka commands by themselves.

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

## Two-phase swarm (form up → advance, with automatic avoidance)

For prompts like “form up in a wedge at Rally, then advance on Red Track 1 avoiding the restricted circle”:

1. Resolve the **ADVANCE** destination (named track/zone or lat/lng). Call `preview_two_phase(formationType, droneIds, aoiLat, aoiLng)` **once** with that destination. It returns a compact summary `{ formationType, droneCount, formUpCenter{lat,lng}, advanceCenter{lat,lng} }` — default `formUpCenter` is a ~2 km standoff short of the destination toward the leader.
2. **Named rally / form-up waypoint:** if the operator says e.g. “form at Rally, then go to Red Track 1”, use the waypoint’s lat/lng for `formUpLat`/`formUpLng` — do **not** use `preview_two_phase`’s standoff `formUpCenter`.
3. Emit exactly **one** `applyFormationRoute` action (see [PLAN.md](PLAN.md)): `{ formationType, droneIds, formUpLat, formUpLng, destLat, destLng, mission_type? }`.
4. The backend `PlanExpander` does the rest, deterministically: it forms the swarm up at `formUpLat/formUpLng`, runs `ZoneRouter` from there to `destLat/destLng` against every RESTRICTED CIRCLE zone (`RestrictedZoneObstacles`), inserts a HOLD wave per detour leg, and finishes with ADVANCE at the destination — expanding the whole thing into ordinary `setWaypoint` waves before the plan is shown to the operator. Every wave carries the full `droneIds` list; there is no path by which only some of the swarm gets a wave. Before routing, every zone is inflated by `FormationService.footprintRadiusMeters(formationType, droneCount, spacingMeters)` — the formation's own center-to-edge span — so a wide formation's outer drones (and the paths they fly between waypoints) clear the zone too, not just the center point `ZoneRouter` is routing.
5. On approve, `PlanExecutor` publishes each wave in turn and **waits** until telemetry shows the swarm has arrived (or ~90s timeout) before publishing the next one.

The planner never calls `plan_route` or invents detour coordinates for a swarm — that was the old (fragile) design, where the model had to hand-compose matching `applyFormation` calls per wave and could under- or over-specify one. `preview_formation` (per-phase) and a plain `applyFormation` are still available for a single-phase formation move with nothing to avoid.

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

Feed `formUpCenter` / `advanceCenter` straight into a single `applyFormationRoute` action's `formUpLat/formUpLng` / `destLat/destLng`.

## Planner contract

1. Choose a type (`list_formations`) and drones (`list_drones`) for swarm/formation requests.
2. Two-phase swarm: call `preview_two_phase` once with the destination. Use a named rally waypoint's lat/lng for `formUpLat/formUpLng` when the operator specified one; otherwise `formUpCenter`. Emit one `applyFormationRoute` with `destLat/destLng` = `advanceCenter`. Single-phase formation with nothing to avoid: `preview_formation` + a plain `applyFormation`.
3. The backend expands `applyFormationRoute` → FORM_UP / HOLD\* / ADVANCE `setWaypoint` waves, routed around RESTRICTED CIRCLE zones, always in that order (see [PLAN.md](PLAN.md)).
4. Human approves → executor gates each wave on the previous wave's arrival.

Do **not** invent lat/lng offsets or detour coordinates in the model — geometry and routing are owned by `FormationService` and `ZoneRouter`.
