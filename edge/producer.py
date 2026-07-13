#!/usr/bin/env python3
"""
Mock edge simulator. Two threads in one process:

- main thread: publishes JSON to Kafka ``drone.telemetry.v1`` (key = droneId)
- daemon thread: listens on ``drone.commands.v1`` for SET_WAYPOINT / CLEAR_WAYPOINT

Shared ``drones`` state is guarded by a ``threading.Lock``.
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

# Topic/group defaults; see docs/TELEMETRY.md and docs/COMMANDS.md
DEFAULT_BOOTSTRAP = "localhost:9092"
DEFAULT_TOPIC = "drone.telemetry.v1"
DEFAULT_COMMANDS_TOPIC = "drone.commands.v1"
DEFAULT_COMMANDS_GROUP = "edge-sim-commands"
DEFAULT_DRONE_COUNT = 50

# Map center from Phase 1
CENTER_LAT = 39.0
CENTER_LON = -77.2

# Motion is time-based (deg/sec), not per-tick, so 1 Hz and 20 Hz look the same.
# Roaming drones curve gently; commanded ones steer toward a waypoint.
ROAM_SPEED_DEG_PER_S = 0.004
STEER_SPEED_DEG_PER_S = 0.0025
# Turn rate mean-reverts with random acceleration for smooth arcs, not per-tick wobble.
TURN_NOISE_RAD_PER_S = 0.6
TURN_DAMP_PER_S = 0.8
MAX_TURN_RATE_RAD_PER_S = 0.5
ROAM_RADIUS_DEG = 0.08  # soft boundary, steer back toward center
BATTERY_STEP_HZ = 1.0     # drain rate is independent of publish Hz

PUBLISH_FREQ = 1.0
JITTER_RATIO = 0.2

@dataclass
class SimulatedDrone:
    drone_id: str
    latitude: float
    longitude: float
    battery_level: int
    seq_num: int
    # Roam heading + turn rate for curved paths.
    heading: float = 0.0
    turn_rate: float = 0.0
    # Keeps battery drain independent of tick rate.
    battery_accum: float = 0.0
    # SET_WAYPOINT target; None means free roam.
    target_lat: Optional[float] = None
    target_lng: Optional[float] = None
    mission_type: Optional[str] = None

    def next_status(self) -> str:
        if self.battery_level <= 10:
            return "OFFLINE"
        if self.battery_level <= 25:
            return "LOW_BATTERY"
        return "ACTIVE"

    def step_physics(self, dt: float) -> None:
        """Advance by dt seconds. Time-scaled so any publish rate looks the same."""
        if self.target_lat is not None and self.target_lng is not None:
            self._steer_toward_target(dt)
        else:
            self._roam(dt)
        self._step_battery(dt)

    def _roam(self, dt: float) -> None:
        """Wander with a held heading and gentle turns. Soft boundary pulls drones back toward center."""
        # Evolve turn rate (sqrt(dt) keeps wander rate-independent), integrate into heading.
        self.turn_rate += (
            -TURN_DAMP_PER_S * self.turn_rate * dt
            + random.gauss(0.0, TURN_NOISE_RAD_PER_S) * math.sqrt(dt)
        )
        self.turn_rate = max(-MAX_TURN_RATE_RAD_PER_S, min(MAX_TURN_RATE_RAD_PER_S, self.turn_rate))
        self.heading += self.turn_rate * dt

        dlat = CENTER_LAT - self.latitude
        dlng = CENTER_LON - self.longitude
        if math.hypot(dlat, dlng) > ROAM_RADIUS_DEG:
            # Past the boundary: ease heading home, bleed off turn rate.
            home = math.atan2(dlat, dlng)
            diff = (home - self.heading + math.pi) % (2 * math.pi) - math.pi
            self.heading += diff * min(1.0, 2.0 * dt)
            self.turn_rate *= 0.5

        step = ROAM_SPEED_DEG_PER_S * dt
        self.longitude += step * math.cos(self.heading)
        self.latitude += step * math.sin(self.heading)

    def _steer_toward_target(self, dt: float) -> None:
        """Steer toward target at fixed speed. On arrival, loiter until CLEAR_WAYPOINT.

        Non-FORM_UP missions used to clear the waypoint on touchdown and random-walk
        again, which broke arrival detection (missions stuck "running").
        """
        dlat = self.target_lat - self.latitude
        dlng = self.target_lng - self.longitude
        dist = math.hypot(dlat, dlng)
        step = STEER_SPEED_DEG_PER_S * dt
        if dist <= step:
            # Snap only the last sub-step; drone holds station until cleared.
            self.latitude = self.target_lat
            self.longitude = self.target_lng
        else:
            self.latitude += step * (dlat / dist)
            self.longitude += step * (dlng / dist)

    def _step_battery(self, dt: float) -> None:
        # ~1 Hz battery steps regardless of publish rate.
        self.battery_accum += dt
        while self.battery_accum >= 1.0 / BATTERY_STEP_HZ:
            self.battery_accum -= 1.0 / BATTERY_STEP_HZ
            self.battery_level = max(0, min(100, self.battery_level + random.randint(-2, 1)))

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
    p.add_argument("--no-consumer", action="store_true", help="Telemetry only, no command consumer.")
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
                seq_num=0,
                heading=random.uniform(0, 2 * math.pi),
            )
        )
    return drones

def sleep_with_jitter(base_interval: float, jitter_ratio: float) -> None:
    """Sleep with random jitter."""
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
    """Daemon loop for SET_WAYPOINT. Kafka key=droneId gives per-drone ordering. Dedup on commandId."""
    consumer = KafkaConsumer(
        args.commands_topic,
        bootstrap_servers=[s.strip() for s in args.bootstrap.split(",") if s.strip()],
        key_deserializer=lambda b: b.decode("utf-8") if b is not None else None,
        value_deserializer=lambda b: json.loads(b.decode("utf-8")),
        auto_offset_reset="latest",
        enable_auto_commit=True,
        group_id=args.commands_group,
        # Poll timeout so we notice shutdown.
        consumer_timeout_ms=1000,
    )
    print(f"Listening for commands on topic={args.commands_topic!r} group={args.commands_group!r}")

    seen_command_ids: set[str] = set()
    try:
        while not stop_event.is_set():
            # Timeout exits the for-loop so we can check stop_event.
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
        # Bounded dedup set; dev sim, best-effort is fine.
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
        # acks=1 + batching so 1000 drones at 20 Hz don't block on every ack.
        acks=1,
        linger_ms=20,
        batch_size=64 * 1024,
        compression_type="lz4",
        retries=5,
    )

    def on_send_error(drone_id: str):
        # Bind drone_id for the errback.
        def _cb(exc: Exception) -> None:
            print(f"Kafka error for {drone_id}: {exc}")
        return _cb

    print(f"Publishing to topic={args.topic!r} bootstrap={args.bootstrap!r} drones={args.drones}")
    last_tick = time.monotonic()
    try:
        while True:
            # Real dt drives physics; clamp to avoid huge jumps on stall.
            now = time.monotonic()
            dt = max(0.001, min(now - last_tick, 1.0))
            last_tick = now

            # Lock only for physics + snapshot, not network I/O.
            with lock:
                now_ms = int(time.time() * 1000)
                payloads = []
                for d in drones:
                    d.step_physics(dt)
                    d.seq_num += 1
                    payloads.append({
                        "droneId": d.drone_id,
                        "latitude": d.latitude,
                        "longitude": d.longitude,
                        "batteryLevel": d.battery_level,
                        "status": d.next_status(),
                        "time": now_ms,
                        "seqNum": d.seq_num,
                    })

            # Fire-and-forget with background batching; much faster at scale.
            for payload in payloads:
                # Key = droneId keeps one drone on one partition.
                producer.send(
                    args.topic, key=payload["droneId"], value=payload
                ).add_errback(on_send_error(payload["droneId"]))

            sleep_with_jitter(args.interval, args.jitter_ratio)
    except KeyboardInterrupt:
        print("Stopping producer (flushing)...")
    finally:
        stop_event.set()
        producer.flush()
        producer.close()
        if consumer_thread is not None:
            consumer_thread.join(timeout=2.0)

if __name__ == "__main__":
    main()
