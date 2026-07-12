# Telemetry Contract

## Topic Name
drone.telemetry.v1

## Message Shape
This is the canonical message shape for "a drone moved" ("telemetry tick")

string: droneId - unique identifier for a drone object

double: latitude - latitude coordinate

double: longitude - longitude coordinate

int: batteryLevel - battery level 0-100

string: status - "ACTIVE" | "LOW_BATTERY" | "OFFLINE"

number: time - event timestamp as Unix epoch milliseconds

int: seqNum - sequence number per drone; helps detect duplicates or out of order delivery

## Topic Partitioning Strategy 
Key = droneId - all events for one drone land in one partition; strict per-drone ordering

## Wire format

Values are UTF-8 JSON. The message value is a **single JSON object** with these **top-level** keys (camelCase): `droneId`, `latitude`, `longitude`, `batteryLevel`, `status`, `time`, `seqNum`.

All fields are **required** for v1 (subject to change).

### Example message value (pretty-printed)

```json
{
  "droneId": "drone-042",
  "latitude": 39.012345,
  "longitude": -77.123456,
  "batteryLevel": 87,
  "status": "ACTIVE",
  "time": 1715432100123,
  "seqNum": 9042
}
```

---

## Browser WebSocket payload (`/ws/drones`)

The edge -> backend Kafka wire stays JSON (above). The **server -> browser** WebSocket
payload is separate and, as of **Phase 4**, **binary Protocol Buffers** rather than JSON
text. This shrinks bytes on the wire and removes `JSON.parse` overhead at 1,000-drone scale.

- **Schema (single source of truth):** [`proto/telemetry.proto`](../proto/telemetry.proto).
  The backend Gradle `com.google.protobuf` plugin generates Java from it; the frontend
  `npm run proto:gen` (protobufjs) generates `frontend/src/proto/telemetry.*`.
- **Transport:** `TelemetryWebSocket` sends `BinaryMessage(TelemetryFrame.toByteArray())`;
  the client sets `ws.binaryType = "arraybuffer"` and decodes with the generated module.

### Frame types

`TelemetryFrame { FrameType type; repeated DroneState drones; }`

- `SNAPSHOT` — the full fleet, sent once on connect/reconnect.
- `BATCH` — a **coalesced** set of drone updates. Instead of one frame per drone per event,
  the backend marks drones dirty as Kafka events arrive and a fixed-tick scheduler
  (`telemetry.broadcast.tick-ms`, default 50 ms) sends a single batched frame to every
  client. At 1,000 drones x 20 Hz this collapses ~20k frames/s/client into ~20 frames/s.

`DroneState { string id; double lat; double lng; int32 battery; DroneStatus status; }`
mirrors `Drone.java` / `frontend/src/types/drone.ts`.

### Persistence decoupling (Phase 4)

The Kafka listener no longer writes SQLite/Neo4j inline. It updates the in-memory
`DroneService` map (authoritative for the live map), marks the drone dirty for the next
broadcast tick, and enqueues the raw event into `TelemetryPersistence`. A scheduled flush
(`telemetry.persistence.flush-ms`, default 500 ms) batch-writes the SQLite shadow log
(JDBC batch) and coalesces newest-per-drone into a single Neo4j `UNWIND` upsert. Under
sustained overload the bounded queue sheds oldest events (counted + logged).