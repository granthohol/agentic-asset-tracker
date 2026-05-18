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