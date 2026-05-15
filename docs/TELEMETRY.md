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