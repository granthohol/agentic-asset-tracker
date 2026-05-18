# Running and Testing the Agentic Asset Tracker

This guide covers the **current Phase 2 pipeline**:

```
Python edge producer
  -> Kafka topic (drone.telemetry.v1)
    -> Spring Boot consumer
       -> SQLite shadow log (./backend/data/telemetry-events.db)
       -> In-memory DroneService map
         -> REST GET /api/drones
```

WebSocket push and the frontend swap-over are pending (Phase 2 §7 and §8). The Phase 1 React app still works against the REST endpoint while we build the streaming UI.

The wire format for telemetry events is documented in [docs/TELEMETRY.md](TELEMETRY.md).

---

## Prerequisites

- Docker Desktop (or a compatible runtime) running
- Java 21 (the backend uses the Gradle wrapper toolchain)
- Python 3.11+
- (Optional) `sqlite3` CLI for inspecting the shadow log

You can confirm versions with:

```bash
docker --version
java -version
python3 --version
```

---

## Component Layout

| Path                     | Role                                      |
| ------------------------ | ----------------------------------------- |
| `infra/docker-compose.yml` | Single-node Kafka (KRaft) for local dev |
| `edge/`                  | Python edge simulator publishing telemetry |
| `backend/`               | Spring Boot consumer + REST API + shadow log |
| `frontend/`              | React + Leaflet UI (Phase 1 polling)      |
| `docs/`                  | Telemetry contract and this guide         |

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

For manual broker-level testing (console producer/consumer), see [infra/run.md](../infra/run.md).

---

## 2. Start the Spring Boot Backend

The backend is the Kafka consumer, REST API, and SQLite shadow log writer.

From `backend/`:

```bash
./gradlew clean bootRun
```

On startup, the backend will:

1. Connect to Kafka at `localhost:9092` (consumer group `asset-tracker-backend`).
2. Subscribe to `drone.telemetry.v1`.
3. Create `backend/data/telemetry-events.db` (and the `telemetry_events` table) if missing.

Important: do **not** `touch` or hand-create `telemetry-events.db`. SQLite creates it itself on first connection. If a non-DB file exists at that path you'll see `[SQLITE_NOTADB] file is not a database` at startup; delete it and rerun.

The default consumer offset reset is `latest`, so the backend only sees events produced **after** it starts. Start the producer **after** the backend if you want to be sure events are captured.

The backend listens on port `8080`.

---

## 3. Start the Python Edge Producer

The edge simulator runs an independent loop and publishes to Kafka.

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

## 4. (Optional) Start the Frontend

The frontend currently still polls `GET /api/drones` (Phase 1). Once the backend is running with consumed events, the map will reflect the live in-memory state.

From `frontend/`:

```bash
npm install   # first time only
npm run dev
```

Open `http://localhost:5173` (or whatever Vite prints) in a browser.

---

## 5. Verifying End-to-End

With Kafka, backend, and producer all running:

### A. Backend logs

You should see `KafkaListener` activity and consumer group join logs. No exceptions on startup.

### B. REST endpoint

```bash
curl -s http://localhost:8080/api/drones | head -c 500
```

You should get a non-empty JSON array of drones with realistic-looking lat/lon/battery values that change between calls.

If the array is empty, the backend started but no events have been consumed yet. Confirm the producer is running and that you started the producer after the backend (or set `spring.kafka.consumer.auto-offset-reset=earliest` to backfill from retained events).

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

### D. Direct Kafka inspection

To confirm raw events on the wire (independent of the backend), use the console consumer in [infra/run.md](../infra/run.md).

---

## 6. Stopping Everything

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
| Backend state only   | Restart `./gradlew bootRun` (in-memory map is rebuilt by replay if `auto-offset-reset=earliest`) |

The producer is stateless across runs (random initial scatter + per-drone `seqNum` resets to 0 each launch).

---

## 8. Troubleshooting

**`./gradlew: no such file or directory`**
You are running from the repo root. Run from `backend/` or use `-p backend`.

**`Failed to construct kafka consumer ... ClassNotFoundException: com.fasterxml.jackson.databind.JavaType`**
Missing Jackson on the runtime classpath. The backend's `build.gradle.kts` declares `jackson-databind` explicitly; rerun `./gradlew clean bootRun`.

**`[SQLITE_NOTADB] file is not a database`**
A non-DB file exists at `backend/data/telemetry-events.db`. Delete it and rerun; SQLite will create the file itself.

**Backend logs deserialize but `/api/drones` returns `[]`**
- The producer hasn't published yet, or
- `spring.kafka.consumer.auto-offset-reset=latest` and the producer was started before the backend. Either restart the producer after the backend, or temporarily set `auto-offset-reset=earliest`.

**`auto.create.topics.enable` confusion**
The Kafka topic auto-creates on first produce/consume in this dev setup. If you want explicit control, create the topic manually with `kafka-topics.sh` (see [infra/run.md](../infra/run.md) for the broker exec pattern).
