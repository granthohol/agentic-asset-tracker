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

# Defaults aligned with docs/TELEMETRY.md / docs/COMMANDS.md and local docker-compose
DEFAULT_BOOTSTRAP = "localhost:9092"
DEFAULT_TOPIC = "drone.telemetry.v1"
DEFAULT_COMMANDS_TOPIC = "drone.commands.v1"
DEFAULT_COMMANDS_GROUP = "edge-sim-commands"
DEFAULT_DRONE_COUNT = 50

# Roughly matches Phase 1 map center
CENTER_LAT = 39.0
CENTER_LON = -77.2

# Motion model is time-based (degrees per SECOND), not per-tick, so the look is identical
# whether we publish at 1 Hz or 20 Hz. Roaming drones hold a heading and turn gently for a
# smooth, curving path instead of jittering in place; commanded drones steer toward a target.
ROAM_SPEED_DEG_PER_S = 0.004     # ground speed while free-roaming
STEER_SPEED_DEG_PER_S = 0.0025   # ground speed toward a commanded waypoint (watchable form-up)
# Heading turns smoothly: we drive an angular *velocity* (turn_rate) that mean-reverts to 0
# with a little random acceleration, then integrate it into the heading. This yields gentle
# banking curves instead of the per-tick white-noise wobble a direct random heading gives.
TURN_NOISE_RAD_PER_S = 0.6       # stddev of random angular acceleration
TURN_DAMP_PER_S = 0.8            # pulls turn_rate back toward straight-line flight
MAX_TURN_RATE_RAD_PER_S = 0.5    # clamp so drones can't spin in place
ROAM_RADIUS_DEG = 0.08           # soft boundary: turn back toward center beyond this
STEER_ARRIVAL_DEG = 0.0015       # snap-to-target threshold (< frontend ARRIVAL_DEG slack)
BATTERY_STEP_HZ = 1.0            # battery random-walk steps per second (rate-independent)

PUBLISH_FREQ = 1.0
JITTER_RATIO = 0.2

@dataclass
class SimulatedDrone:
    drone_id: str
    latitude: float
    longitude: float
    battery_level: int
    seq_num: int
    # Free-roam heading (radians) and its angular velocity; integrating the (smoothly
    # drifting) turn_rate into heading gives curved paths without per-tick wobble.
    heading: float = 0.0
    turn_rate: float = 0.0
    # Accumulates elapsed time so battery steps happen at a fixed rate regardless of tick Hz.
    battery_accum: float = 0.0
    # Phase 3: set by an incoming SET_WAYPOINT command; None => free roam.
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
        """Advance the drone by ``dt`` seconds. All motion is time-scaled so 1 Hz and
        20 Hz produce the same ground track."""
        if self.target_lat is not None and self.target_lng is not None:
            self._steer_toward_target(dt)
        else:
            self._roam(dt)
        self._step_battery(dt)

    def _roam(self, dt: float) -> None:
        """Smooth wandering: keep a heading, turn it gently, and translate along it.

        Because we move a fixed distance *along the current heading* (rather than picking a
        brand-new random offset every tick), the path is continuous and covers real ground
        instead of averaging back to the start. A soft boundary re-points the drone toward
        the map center so the fleet doesn't disperse off-screen.
        """
        # Smoothly evolve the turn rate (mean-reverting random acceleration), then integrate
        # it into the heading. sqrt(dt) on the noise keeps the wander rate-independent.
        self.turn_rate += (
            -TURN_DAMP_PER_S * self.turn_rate * dt
            + random.gauss(0.0, TURN_NOISE_RAD_PER_S) * math.sqrt(dt)
        )
        self.turn_rate = max(-MAX_TURN_RATE_RAD_PER_S, min(MAX_TURN_RATE_RAD_PER_S, self.turn_rate))
        self.heading += self.turn_rate * dt

        dlat = CENTER_LAT - self.latitude
        dlng = CENTER_LON - self.longitude
        if math.hypot(dlat, dlng) > ROAM_RADIUS_DEG:
            # Beyond the soft boundary, ease the heading toward home instead of snapping,
            # and bleed off the turn rate so the turn-back is smooth too.
            home = math.atan2(dlat, dlng)  # atan2(north, east)
            diff = (home - self.heading + math.pi) % (2 * math.pi) - math.pi
            self.heading += diff * min(1.0, 2.0 * dt)
            self.turn_rate *= 0.5

        step = ROAM_SPEED_DEG_PER_S * dt
        self.longitude += step * math.cos(self.heading)
        self.latitude += step * math.sin(self.heading)

    def _steer_toward_target(self, dt: float) -> None:
        """Move toward (target_lat, target_lng) at a fixed ground speed.

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
        step = STEER_SPEED_DEG_PER_S * dt
        if dist <= max(STEER_ARRIVAL_DEG, step):
            # Close enough: snap to the target and hold station. step_physics keeps
            # calling steer while target_* is set, so the drone stays put until a new
            # SET_WAYPOINT or a CLEAR_WAYPOINT arrives.
            self.latitude = self.target_lat
            self.longitude = self.target_lng
        else:
            self.latitude += step * (dlat / dist)
            self.longitude += step * (dlng / dist)

    def _step_battery(self, dt: float) -> None:
        # Gentle battery random walk, biased downward, at ~BATTERY_STEP_HZ regardless of
        # publish rate (so 20 Hz doesn't drain 20x faster than 1 Hz).
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
                heading=random.uniform(0, 2 * math.pi),
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
        # Phase 4 throughput: fire-and-forget. acks=1 (leader only) + batching keeps the
        # publish loop from blocking on per-message round trips at 1000 drones x 20 Hz.
        acks=1,
        linger_ms=20,
        batch_size=64 * 1024,
        compression_type="lz4",
        retries=5,
    )

    def on_send_error(drone_id: str):
        # Bound the closure's drone_id so the errback reports the right drone.
        def _cb(exc: Exception) -> None:
            print(f"Kafka error for {drone_id}: {exc}")
        return _cb

    print(f"Publishing to topic={args.topic!r} bootstrap={args.bootstrap!r} drones={args.drones}")
    last_tick = time.monotonic()
    try:
        while True:
            # Real elapsed time since the previous wave drives time-scaled physics, so the
            # motion looks the same at any --interval. Clamp to avoid a huge jump if the
            # loop stalls (e.g. broker backpressure).
            now = time.monotonic()
            dt = max(0.001, min(now - last_tick, 1.0))
            last_tick = now

            # Hold the lock only for the quick physics step + payload snapshot, so
            # the command consumer is never blocked behind network I/O.
            with lock:
                now_ms = int(time.time() * 1000)  # Unix epoch milliseconds
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

            # Fire-and-forget: enqueue every record without blocking on acks. The client
            # batches (linger_ms/batch_size) and flushes in the background; errors surface
            # via the errback. This is the difference between ~50 msg/s and 20k+ msg/s.
            for payload in payloads:
                # Record KEY is droneId so all events for one drone share one partition.
                producer.send(
                    args.topic, key=payload["droneId"], value=payload
                ).add_errback(on_send_error(payload["droneId"]))

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
