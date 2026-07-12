# Kafka Broker - Manual Testing

Snippets for poking the broker directly (independent of the Spring Boot consumer or the Python producer). For full end-to-end run/test instructions, see [docs/RUNNING.md](../docs/RUNNING.md).

## Manual Terminal Producer (Testing)
```bash
docker compose -f infra/docker-compose.yml exec broker \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic drone.telemetry.v1
```

## Terminal Consumer (Testing)
```bash
docker compose -f infra/docker-compose.yml exec broker \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic drone.telemetry.v1 \
  --from-beginning
```

## Publish a SET_WAYPOINT command (Phase 3)

Steers a single drone toward a target. The edge simulator (`edge/producer.py`)
consumes `drone.commands.v1` keyed on `droneId`. Pipe one JSON line in via stdin;
the key (before `:`) must match a real `droneId` the producer is simulating.

```bash
echo 'drone-007:{"droneId":"drone-007","targetLat":39.06,"targetLng":-77.10,"mission_type":"RECON","issuedAt":1737000000000,"commandId":"cmd-manual-001"}' \
| docker compose -f infra/docker-compose.yml exec -T broker \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic drone.commands.v1 \
  --property parse.key=true \
  --property key.separator=:
```

The simulator prints `SET_WAYPOINT drone-007 -> (39.06, -77.1) ...` and that marker
will visibly steer to the target over the next several telemetry ticks, then hold
station there until a new `SET_WAYPOINT` or a `CLEAR_WAYPOINT` arrives. Re-sending the
same `commandId` is ignored (dedup).