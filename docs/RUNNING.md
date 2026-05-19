# Running and Testing the Agentic Asset Tracker

This guide covers the **completed Phase 2 pipeline**:

```
Python edge producer
  -> Kafka topic (drone.telemetry.v1)
    -> Spring Boot consumer
       -> SQLite shadow log (./backend/data/telemetry-events.db)
       -> In-memory DroneService map
         -> WebSocket /ws/drones (snapshot + droneUpdate frames)
            -> React + Leaflet UI (live markers, no polling)
```

The React UI consumes WebSocket frames only. `GET /api/drones` remains as a **debug-only** REST endpoint that reads the same `DroneService` map; it is not on the UI hot path.

The wire format for telemetry events is documented in [docs/TELEMETRY.md](TELEMETRY.md).

---

## Prerequisites

- Docker Desktop (or a compatible runtime) running
- Java 21 (the backend uses the Gradle wrapper toolchain)
- Python 3.11+
- Node.js 18+ and npm (for the Vite frontend)
- (Optional) `sqlite3` CLI for inspecting the shadow log

You can confirm versions with:

```bash
docker --version
java -version
python3 --version
node -v
```

---

## Component Layout

| Path                       | Role                                                  |
| -------------------------- | ----------------------------------------------------- |
| `infra/docker-compose.yml` | Single-node Kafka (KRaft) for local dev               |
| `edge/`                    | Python edge simulator publishing telemetry            |
| `backend/`                 | Spring Boot consumer + shadow log + WebSocket + REST  |
| `frontend/`                | React + Leaflet UI (WebSocket-driven)                 |
| `docs/`                    | Telemetry contract and this guide                     |

---

## Recommended Startup Order

Because `spring.kafka.consumer.auto-offset-reset=latest`, the backend only sees events produced **after** it joins the topic. Start in this order so nothing is missed:

1. **Kafka broker** (Docker)
2. **Spring Boot backend**
3. **Python edge producer**
4. **React frontend**

Each step below assumes the previous one is running.

---

## 1. Start Kafka

From the repo root:

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

You should see the `broker` container in an `Up` state with port `9092` published to `localhost:9092`.

To tail broker logs:

```bash
docker compose -f infra/docker-compose.yml logs -f broker
```

For manual broker-level testing (console producer/consumer, topic describe), see [infra/run.md](../infra/run.md).

---

## 2. Start the Spring Boot Backend

The backend is the Kafka consumer, REST API, SQLite shadow log writer, and WebSocket broadcaster.

From `backend/`:

```bash
./gradlew clean bootRun
```

On startup, the backend will:

1. Connect to Kafka at `localhost:9092` (consumer group `asset-tracker-backend`).
2. Subscribe to `drone.telemetry.v1`.
3. Create `backend/data/telemetry-events.db` (and the `telemetry_events` table) if missing.
4. Register the WebSocket endpoint at `ws://localhost:8080/ws/drones`.

Important: do **not** `touch` or hand-create `telemetry-events.db`. SQLite creates it itself on first connection. If a non-DB file exists at that path you'll see `[SQLITE_NOTADB] file is not a database` at startup; delete it and rerun.

The backend listens on port `8080` (HTTP + WebSocket).

---

## 3. Start the Python Edge Producer

The edge simulator runs an independent loop and publishes telemetry to Kafka.

First-time setup (from `edge/`):

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Run with defaults (50 drones, ~1 publish wave per second, jittered):

```bash
python3 producer.py
```

Useful flags:

```bash
# Slow rate, fewer drones
python3 producer.py --drones 10 --interval 2.0

# Faster rate
python3 producer.py --interval 0.25

# Custom broker
python3 producer.py --bootstrap localhost:9092 --topic drone.telemetry.v1
```

The producer prints per-wave ack stats. Cumulative `ack_err` should stay at `0` against a healthy local broker.

---

## 4. Start the Frontend

The frontend opens a WebSocket to `ws://localhost:8080/ws/drones`, receives an initial snapshot, then merges per-drone updates as Kafka events arrive. It also reconnects with exponential backoff if the connection drops.

From `frontend/`:

```bash
npm install   # first time only
npm run dev
```

Open `http://localhost:5173` (or whatever Vite prints) in a browser.

---

## 5. Verifying End-to-End

With Kafka, backend, producer, and frontend all running:

### A. Backend logs

You should see `KafkaListener` activity and consumer group join logs. No exceptions on startup.

### B. Browser (primary UI path)

- Open the map page; Leaflet tiles load.
- Open DevTools → **Network** → filter **WS** → click `drones` → **Messages**:
  - First frame: `{"type":"snapshot","drones":[...]}`
  - Subsequent frames: `{"type":"droneUpdate","drone":{...}}` continuously while the producer runs.
- Markers move on the map without page refresh and without any `fetch` polling.
- Console should log `WebSocket Connection Established`.

### C. SQLite shadow log

From `backend/`:

```bash
file data/telemetry-events.db
sqlite3 data/telemetry-events.db ".schema telemetry_events"
sqlite3 data/telemetry-events.db "SELECT COUNT(*) FROM telemetry_events;"
sqlite3 data/telemetry-events.db \
  "SELECT drone_id, latitude, longitude, battery_level, status,
          event_time_ms, seq_num, received_at_ms
   FROM telemetry_events
   ORDER BY id DESC
   LIMIT 5;"
```

Expectations:

- `file` reports `SQLite 3.x database`.
- The table schema matches `TelemetryEventLog#initialize`.
- Row count grows roughly at the producer's publish rate.
- `event_time_ms` is producer-side; `received_at_ms` is backend-side. Backend lag = `received_at_ms - event_time_ms`.

### D. Consumer lag (Kafka group health)

```bash
docker compose -f infra/docker-compose.yml exec broker \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group asset-tracker-backend
```

Under steady load, `LAG` should stay near `0` per partition. Growing lag means the backend is slower than the produce rate.

### E. REST endpoint (debug only)

```bash
curl -s http://localhost:8080/api/drones | head -c 500
```

This is not on the UI hot path; it reads the same in-memory `DroneService` map for ad-hoc inspection.

### F. Direct Kafka inspection

To confirm raw events on the wire (independent of the backend), use the console consumer in [infra/run.md](../infra/run.md).

### G. Reconnect-with-backoff (frontend resilience)

1. Leave the browser tab open.
2. Stop the backend (`Ctrl+C` in its terminal).
3. In DevTools console you should see `Reconnecting in 1000ms (attempt 1)`, then `2000ms`, `4000ms`, ..., capped at `30000ms`.
4. Restart the backend (`./gradlew bootRun`).
5. The frontend should reconnect automatically, receive a new `snapshot`, and resume updates without a page refresh.

---

## 6. Stopping Everything

- `Ctrl+C` in the frontend terminal (Vite).
- `Ctrl+C` in the producer terminal (Python flushes and closes).
- `Ctrl+C` in the backend terminal.
- `docker compose -f infra/docker-compose.yml down` to stop Kafka.

To stop and **wipe Kafka's local data** (e.g. fresh start):

```bash
docker compose -f infra/docker-compose.yml down -v
```

---

## 7. Resetting Local State

When iterating, you may want a clean slate. Pick what to reset:

| Reset                | Command                                       |
| -------------------- | --------------------------------------------- |
| Shadow log only      | `rm backend/data/telemetry-events.db`         |
| Kafka data only      | `docker compose -f infra/docker-compose.yml down -v` |
| Consumer offsets     | `kafka-consumer-groups.sh --reset-offsets ...` (see [infra/run.md](../infra/run.md)) |
| Backend state only   | Restart `./gradlew bootRun` (in-memory map repopulates from new events) |

The producer is stateless across runs (random initial scatter + per-drone `seqNum` resets to 0 each launch).

---

## 8. Offset Reset Behavior (`latest` vs `earliest`)

`spring.kafka.consumer.auto-offset-reset` applies **only when there is no committed offset** for a partition in group `asset-tracker-backend`.

- **`latest` (default):** New consumers see only events produced after they join. Clean for demos; backend restarts resume from the last committed offset, not from topic start.
- **`earliest`:** New consumers replay from the start of retention. Useful when rebuilding state from the log.

To experiment, change `spring.kafka.consumer.group-id` to a fresh value (e.g. `asset-tracker-backend-replay`) before flipping `auto-offset-reset=earliest`, so the existing group's commits don't override the behavior.

---

## 9. Troubleshooting

**`./gradlew: no such file or directory`**
You are running from the repo root. Run from `backend/` or use `-p backend`.

**`Failed to construct kafka consumer ... ClassNotFoundException: com.fasterxml.jackson.databind.JavaType`**
Missing Jackson on the runtime classpath. The backend's `build.gradle.kts` declares `jackson-databind` explicitly; rerun `./gradlew clean bootRun`.

**`[SQLITE_NOTADB] file is not a database`**
A non-DB file exists at `backend/data/telemetry-events.db`. Delete it and rerun; SQLite will create the file itself.

**Map loads but markers never appear**
- Producer not running, or
- Backend started after producer with `auto-offset-reset=latest`. Restart the producer (or use `earliest` once with a fresh group id) to backfill into the in-memory map.

**WebSocket never connects in the browser**
- Backend not running on port 8080.
- CORS origin mismatch: `WebSocketConfig` allows only `http://localhost:5173`. If Vite picked a different port, update the allowed origin or run with `npm run dev -- --port 5173`.
- Browser blocked the request: check DevTools console for the actual error.

**`auto.create.topics.enable` confusion**
The Kafka topic auto-creates on first produce/consume in this dev setup. If you want explicit control (e.g. specific partition count), create the topic manually with `kafka-topics.sh` (see [infra/run.md](../infra/run.md) for the broker exec pattern).
