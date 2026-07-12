# Formation planning tools

Read-only tools that let the planner (stub or real LLM) choose a geometric layout for a swarm, then emit ordinary `setWaypoint` actions. Formations **never** mutate Neo4j or publish Kafka commands by themselves.

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

1. **Leader** = first drone from `list_drones` (slot 0).
2. **Form-up center** = `FormationService.standoffCenter(AOI, leaderPos, ~2000m)` — on the line from leader → AOI, short of the disturbance.
3. `preview_formation` at the form-up center → emit `setWaypoint` with `mission_type: "FORM_UP"`.
4. `preview_formation` at the AOI (same type + drone order) → emit `setWaypoint` with `mission_type: "ADVANCE"`.
5. On approve, `PlanExecutor` publishes all FORM_UP commands, **waits** until telemetry shows the swarm has formed (or ~90s timeout), then publishes ADVANCE.

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
    { "index": 0, "droneId": "drone-000", "targetLat": 39.03, "targetLng": -77.18 }
  ]
}
```

## Planner contract

1. Call `list_formations` + `list_drones` for swarm/formation requests.
2. Call `preview_formation` twice (standoff, then AOI) with the same type and drone order.
3. Emit FORM_UP then ADVANCE `setWaypoint` actions (see [PLAN.md](PLAN.md)).
4. Human approves → executor gates ADVANCE on form-up arrival.

Do **not** invent lat/lng offsets in the model — geometry is owned by `FormationService`.
