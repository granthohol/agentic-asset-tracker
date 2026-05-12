
## Producer
```bash
docker compose -f infra/docker-compose.yml exec broker \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic drone.telemetry.v1
```

## Consumer
```bash
docker compose -f infra/docker-compose.yml exec broker \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic drone.telemetry.v1 \
  --from-beginning
```