#!/usr/bin/env python3
"""
Edge telemetry simulator for Agentic Asset Tracker (Phase 2)

Publishes JSON telemetry to Kafka topic drone.telemetry.v1 with record key = droneId.
See docs/TELEMETRY.md for message schema.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from dataclasses import dataclass

from kafka import KafkaProducer
from kafka.errors import KafkaError

# Defaults aligned with docs/TELEMETRY.md and local docker-compose
DEFAULT_BOOTSTRAP = "localhost:9092"
DEFAULT_TOPIC = "drone.telemetry.v1"
DEFAULT_DRONE_COUNT = 50

# Roughly matches Phase 1 map center
CENTER_LAT = 39.0
CENTER_LON = -77.2
STEP_DEG = 0.005 # small random walk per tick
PUBLISH_FREQ = 1.0
JITTER_RATIO = 0.2

@dataclass
class SimulatedDrone:
    drone_id: str
    latitude: float
    longitude: float
    battery_level: int
    seq_num: int

    def next_status(self) -> str:
        if self.battery_level <= 10:
            return "OFFLINE"
        if self.battery_level <= 25:
            return "LOW_BATTERY"
        return "ACTIVE"

    def step_physics(self) -> None:
        self.latitude += random.uniform(-STEP_DEG, STEP_DEG)
        self.longitude += random.uniform(-STEP_DEG, STEP_DEG)
        # gentle battery random walk, biased downward
        self.battery_level = max(0, min(100, self.battery_level + random.randint(-2, 1)))

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Publish mock drone telemetry to Kafka.")
    p.add_argument(
        "--bootstrap",
        default=DEFAULT_BOOTSTRAP,
        help="Kafka bootstrap servers, comma-separated if multiple.",
    )
    p.add_argument("--topic", default=DEFAULT_TOPIC, help="Telemetry topic name.")
    p.add_argument("--drones", type=int, default=DEFAULT_DRONE_COUNT, help="How many drones to simulate.")
    p.add_argument(
        "--interval",
        type=float,
        default=PUBLISH_FREQ,
        help="Base seconds between publish waves (one message per drone per wave).",
    )
    p.add_argument(
        "--jitter-ratio",
        type=float,
        default=JITTER_RATIO,
        help="Fraction of interval used as +/- random sleep jitter, e.g. 0.2 => +/-20%%.",
    )
    return p

def make_initial_fleet(n: int) -> list[SimulatedDrone]:
    drones: list[SimulatedDrone] = []
    for i in range(n):
        drones.append(
            SimulatedDrone(
                drone_id=f"drone-{i:03d}",
                latitude=CENTER_LAT + random.uniform(-0.05, 0.05),
                longitude=CENTER_LON + random.uniform(-0.05,0.05),
                battery_level=random.randint(40, 100),
                seq_num=0, # first telemetry instance for each drone
            )
        )
    return drones

def sleep_with_jitter(base_interval: float, jitter_ratio: float) -> None:
    """Adds randomness"""
    if base_interval <= 0:
        return

    span = base_interval * jitter_ratio
    delay = base_interval + random.uniform(-span, span)
    time.sleep(max(0.05, delay))

def main() -> None:
    args = build_parser().parse_args()
    drones = make_initial_fleet(args.drones)

    producer = KafkaProducer(
        bootstrap_servers=[s.strip() for s in args.bootstrap.split(",") if s.strip()],
        key_serializer=lambda k: k.encode("utf-8"),
        value_serializer=lambda v: json.dumps(v, separators=(",", ":")).encode("utf-8"),
        acks="all",
        linger_ms=5,
        retries=5,
    )


    print(f"Publishing to topic={args.topic!r} bootstrap={args.bootstrap!r} drones={args.drones}")
    try:
        while True:
            for d in drones:
                d.step_physics()
                d.seq_num += 1
                payload = {
                    "droneId": d.drone_id,
                    "latitude": d.latitude,
                    "longitude": d.longitude,
                    "batteryLevel": d.battery_level,
                    "status": d.next_status(),
                    "time": int(time.time() * 1000), # Unix epoch milliseconds
                    "seqNum": d.seq_num
                }
                # Record KEY is droneId so all events for one drone share one partition
                future = producer.send(args.topic, key=d.drone_id, value=payload)
                try:
                    future.get(timeout=10)
                except KafkaError as exc:
                    print(f"Kafka error for {d.drone_id}: {exc}")

            producer.flush()    # wait until every message has been acknowledged
            sleep_with_jitter(args.interval, args.jitter_ratio)
    except KeyboardInterrupt:
        print("Stopping producer (flushing)...")
    finally:
        producer.flush()
        producer.close()    # tear down TCP socket

if __name__ == "__main__":
    main()



    