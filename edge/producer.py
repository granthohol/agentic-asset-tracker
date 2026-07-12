#!/usr/bin/env python3
"""
Edge telemetry simulator for Agentic Asset Tracker (Phase 2 + Phase 3)

Two responsibilities run in one process on two threads:

1. PRODUCER (main thread): publishes JSON telemetry to Kafka topic
   ``drone.telemetry.v1`` with record key = droneId. See docs/TELEMETRY.md.
2. COMMANDS CONSUMER (daemon thread): listens on ``drone.commands.v1`` for
   ``SET_WAYPOINT`` commands and steers the matching drone toward the target
   instead of letting it random-walk. See docs/COMMANDS.md.

The two threads share the ``drones`` fleet, so a ``threading.Lock`` guards the
state the consumer mutates (a drone's current target) and the producer reads.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import threading
import time
from dataclasses import dataclass
from typing import Optional

from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError

# Defaults aligned with docs/TELEMETRY.md / docs/COMMANDS.md and local docker-compose
DEFAULT_BOOTSTRAP = "localhost:9092"
DEFAULT_TOPIC = "drone.telemetry.v1"
DEFAULT_COMMANDS_TOPIC = "drone.commands.v1"
DEFAULT_COMMANDS_GROUP = "edge-sim-commands"
DEFAULT_DRONE_COUNT = 50

# Roughly matches Phase 1 map center
CENTER_LAT = 39.0
CENTER_LON = -77.2
# Degrees per tick (~1 Hz). Steer is slower so form-up / advance is watchable on the map.
WALK_STEP_DEG = 0.002
STEER_STEP_DEG = 0.0025
PUBLISH_FREQ = 1.0
JITTER_RATIO = 0.2

@dataclass
class SimulatedDrone:
    drone_id: str
    latitude: float
    longitude: float
    battery_level: int
    seq_num: int
    # Phase 3: set by an incoming SET_WAYPOINT command; None => free random walk.
    target_lat: Optional[float] = None
    target_lng: Optional[float] = None
    mission_type: Optional[str] = None

    def next_status(self) -> str:
        if self.battery_level <= 10:
            return "OFFLINE"
        if self.battery_level <= 25:
            return "LOW_BATTERY"
        return "ACTIVE"

    def step_physics(self) -> None:
        if self.target_lat is not None and self.target_lng is not None:
            self._steer_toward_target()
        else:
            self.latitude += random.uniform(-WALK_STEP_DEG, WALK_STEP_DEG)
            self.longitude += random.uniform(-WALK_STEP_DEG, WALK_STEP_DEG)
        # gentle battery random walk, biased downward
        self.battery_level = max(0, min(100, self.battery_level + random.randint(-2, 1)))

    def _steer_toward_target(self) -> None:
        """Move up to STEER_STEP_DEG toward (target_lat, target_lng).

        On arrival the drone holds station at the target (loiter) for every mission
        type. A waypoint is only released by an explicit CLEAR_WAYPOINT (mission
        cancel), so "arrived" is a stable, detectable condition for both the FORM_UP
        gate and ADVANCE mission-complete. Previously non-FORM_UP missions cleared the
        waypoint the instant they touched down and resumed a random walk, which made
        early arrivers scatter off the objective and turned arrival into a one-tick
        event the map/executor routinely missed (mission stuck "running").
        """
        dlat = self.target_lat - self.latitude
        dlng = self.target_lng - self.longitude
        dist = math.hypot(dlat, dlng)
        if dist <= STEER_STEP_DEG:
            # Close enough: snap to the target and hold station. step_physics keeps
            # calling steer while target_* is set, so the drone stays put until a new
            # SET_WAYPOINT or a CLEAR_WAYPOINT arrives.
            self.latitude = self.target_lat
            self.longitude = self.target_lng
        else:
            self.latitude += STEER_STEP_DEG * (dlat / dist)
            self.longitude += STEER_STEP_DEG * (dlng / dist)

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Publish mock drone telemetry to Kafka and consume waypoint commands.")
    p.add_argument(
        "--bootstrap",
        default=DEFAULT_BOOTSTRAP,
        help="Kafka bootstrap servers, comma-separated if multiple.",
    )
    p.add_argument("--topic", default=DEFAULT_TOPIC, help="Telemetry topic name.")
    p.add_argument("--commands-topic", default=DEFAULT_COMMANDS_TOPIC, help="Command topic to consume (SET_WAYPOINT).")
    p.add_argument("--commands-group", default=DEFAULT_COMMANDS_GROUP, help="Consumer group id for the command listener.")
    p.add_argument("--no-consumer", action="store_true", help="Disable the command consumer (telemetry-only, Phase 2 behavior).")
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

def consume_commands(
    drones_by_id: dict[str, SimulatedDrone],
    lock: threading.Lock,
    args: argparse.Namespace,
    stop_event: threading.Event,
) -> None:
    """Daemon loop: apply SET_WAYPOINT commands to the shared fleet.

    Per-drone ordering is guaranteed by Kafka because commands are keyed on
    droneId (see docs/COMMANDS.md). Dedup on commandId so a redelivered command
    doesn't re-route a drone that already finished the maneuver.
    """
    consumer = KafkaConsumer(
        args.commands_topic,
        bootstrap_servers=[s.strip() for s in args.bootstrap.split(",") if s.strip()],
        key_deserializer=lambda b: b.decode("utf-8") if b is not None else None,
        value_deserializer=lambda b: json.loads(b.decode("utf-8")),
        auto_offset_reset="latest",
        enable_auto_commit=True,
        group_id=args.commands_group,
        # Wake up periodically so we can observe stop_event during shutdown.
        consumer_timeout_ms=1000,
    )
    print(f"Listening for commands on topic={args.commands_topic!r} group={args.commands_group!r}")

    seen_command_ids: set[str] = set()
    try:
        while not stop_event.is_set():
            # `for msg in consumer` yields until consumer_timeout_ms elapses with
            # no records, then exits the for-loop so the while can re-check stop.
            for msg in consumer:
                if stop_event.is_set():
                    break
                _apply_command(msg.value, drones_by_id, lock, seen_command_ids)
    finally:
        consumer.close()

def _apply_command(
    cmd: dict,
    drones_by_id: dict[str, SimulatedDrone],
    lock: threading.Lock,
    seen_command_ids: set[str],
) -> None:
    command_id = cmd.get("commandId")
    if command_id is not None:
        if command_id in seen_command_ids:
            return
        seen_command_ids.add(command_id)
        # Bounded dedup memory: dev simulator, best-effort is fine.
        if len(seen_command_ids) > 4096:
            seen_command_ids.clear()
            seen_command_ids.add(command_id)

    drone_id = cmd.get("droneId")
    if drone_id is None:
        print(f"Ignoring malformed command (need droneId): {cmd}")
        return

    cmd_type = (cmd.get("type") or "SET_WAYPOINT").upper()

    with lock:
        drone = drones_by_id.get(drone_id)
        if drone is None:
            print(f"Command for unknown drone {drone_id!r}; ignoring")
            return

        if cmd_type == "CLEAR_WAYPOINT":
            drone.target_lat = None
            drone.target_lng = None
            drone.mission_type = None
            print(f"CLEAR_WAYPOINT {drone_id} commandId={command_id}")
            return

        target_lat = cmd.get("targetLat")
        target_lng = cmd.get("targetLng")
        mission_type = cmd.get("mission_type")
        if target_lat is None or target_lng is None:
            print(f"Ignoring malformed SET_WAYPOINT (need targetLat/targetLng): {cmd}")
            return
        drone.target_lat = float(target_lat)
        drone.target_lng = float(target_lng)
        drone.mission_type = mission_type

    print(f"SET_WAYPOINT {drone_id} -> ({target_lat}, {target_lng}) mission={mission_type} commandId={command_id}")

def main() -> None:
    args = build_parser().parse_args()
    drones = make_initial_fleet(args.drones)
    drones_by_id = {d.drone_id: d for d in drones}
    lock = threading.Lock()
    stop_event = threading.Event()

    consumer_thread: Optional[threading.Thread] = None
    if not args.no_consumer:
        consumer_thread = threading.Thread(
            target=consume_commands,
            args=(drones_by_id, lock, args, stop_event),
            name="commands-consumer",
            daemon=True,
        )
        consumer_thread.start()

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
            # Hold the lock only for the quick physics step + payload snapshot, so
            # the command consumer is never blocked behind network I/O.
            with lock:
                payloads = []
                for d in drones:
                    d.step_physics()
                    d.seq_num += 1
                    payloads.append({
                        "droneId": d.drone_id,
                        "latitude": d.latitude,
                        "longitude": d.longitude,
                        "batteryLevel": d.battery_level,
                        "status": d.next_status(),
                        "time": int(time.time() * 1000), # Unix epoch milliseconds
                        "seqNum": d.seq_num,
                    })

            for payload in payloads:
                # Record KEY is droneId so all events for one drone share one partition
                future = producer.send(args.topic, key=payload["droneId"], value=payload)
                try:
                    future.get(timeout=10)  # TODO: potentially edit for better latency (use callbacks)
                except KafkaError as exc:
                    print(f"Kafka error for {payload['droneId']}: {exc}")

            producer.flush()    # wait until every message has been acknowledged (TODO: potentially edit for better latency (remove)
            sleep_with_jitter(args.interval, args.jitter_ratio)
    except KeyboardInterrupt:
        print("Stopping producer (flushing)...")
    finally:
        stop_event.set()
        producer.flush()
        producer.close()    # tear down TCP socket
        if consumer_thread is not None:
            consumer_thread.join(timeout=2.0)

if __name__ == "__main__":
    main()
