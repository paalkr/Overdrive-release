# Telemetry architecture

How vehicle data gets from the BYD bus to consumers (MQTT / Home Assistant,
the internal trip recorder, the web UI), what caches and buffers sit in the
path, and what update latency to expect for each kind of field.

Everything below runs in the `byd_cam_daemon` process unless noted. The single
source of truth for bus values is an immutable snapshot (`BydVehicleData`)
held in `BydDataCollector`; both event callbacks and polls write into it, and
every consumer reads from it.

## Collection layer: three field classes

### 1. Event-driven fields (milliseconds from bus to snapshot)

These register listeners on the BYD SDK devices and update the snapshot the
moment the HAL fires a callback:

| Field | Callback |
|---|---|
| speed | `onSpeedChanged` (the poll below is only a backstop) |
| state of charge (decimal) | `onElecPercentageChanged` |
| charging state | typed charging listener (`onBatteryManagementDeviceStateChanged`) |
| external charging power | typed instrument listener (`onExternalChargingPowerChanged`) |
| doors, locks, windows | typed bodywork/doorlock listeners (no polling at all) |
| ACC on/off | bodywork power-level listener, plus a fixed 30 s heartbeat backstop |

Some of these require the *typed* listener classes: the generic
`IBYDAutoListener` proxy registers successfully but never receives the
device-specific callbacks (this was the root cause of frozen lock data and
missed charging starts on some firmwares).

### 2. Poll-only fields

`BydDataCollector` polls the SDK on a scheduler:

- **5 s while ACC is on** (`POLL_INTERVAL_MS`)
- **90 s while parked** (`POLL_INTERVAL_PARKED_MS`); speed, gear and engine
  reads are skipped entirely while parked
- **an immediate, unthrottled full collection fires on the ACC off→on edge**,
  so the first values after wake are fresh instead of up to one poll interval
  old (the regular collect path has a 5 s core-collect throttle that would
  otherwise swallow the wake read)

Poll-only fields include gear, 12 V battery voltage, cell temperatures and
voltages, and outside temperature. Gear is poll-only deliberately: registering
the gearbox listener crashes when the daemon runs as shell (uid 2000)
(`learningEPB()` package check), and the crash cascades into a daemon restart
loop — so it stays disabled.

### 3. GPS

GPS never touches the BYD SDK. `LocationSidecarService` (app process, has
location permission) registers LocationManager listeners at 1 s/0 m and also
polls `getLastKnownLocation` every 1 s, then ships fixes over a local TCP
socket to the daemon (`SurveillanceIpcServer`, port 19877), which updates
`GpsMonitor`. Every GPS consumer — MQTT, the trip recorder, RoadSense — reads
`GpsMonitor`.

Two details that matter:

- The listeners are registered *unconditionally*, not gated on
  `isProviderEnabled()`. The head unit disables location whenever the car is
  off, and app restarts usually happen parked — a gate there means the
  listener never registers and GPS silently degrades to whatever other apps
  happen to request.
- Each fix carries its own timestamp (`Location.getTime()`) through the whole
  chain as `gps_utc` / `fix_time`, separate from the send time. GNSS fixes on
  this HAL arrive several hundred ms old (seconds, through turns), so
  consumers that align positions against wall-clock time need the true fix
  time per point.

## Publish layer: the MQTT path

Each configured MQTT connection runs a publish cycle
(`MqttConnectionManager.runPublishCycle`). Three stages sit between the
snapshot and the broker:

1. **Telemetry cache (2 s).** `collectTelemetry()` serves a cached payload if
   the last collection was under 2 s ago, protecting the BYD SDK from
   concurrent hammering by multiple connections. GPS position and `gps_utc`
   bypass this cache (refreshed live onto a copy each cycle). The cache is
   invalidated on the ACC off→on edge so the wake-up publish contains fresh
   values, not the parked snapshot.
2. **Publish cycle at the configured min interval.** The cycle runs every
   `max(1, minIntervalSeconds)` seconds — the 1 s UI slider is literally the
   cycle rate. Per cycle, only *changed* fields transmit (`changeOnly` diff);
   a full re-send of every field happens on the heartbeat (default 300 s,
   measured since the last full sync) and for 5 cycles after every ACC or
   charging state transition, so a lost publish at a network handover can't
   leave a stale retained state.
3. **Transport.** Broker and network; measured MQTT→Home Assistant receipt is
   typically around one second.

The device tracker position is published as its own retained message
(latitude, longitude, `gps_utc`) whenever the position changed or a full sync
runs.

## What latency to expect end to end (bus change → visible in HA)

| Field class | Stages | Typical | Worst |
|---|---|---|---|
| event-driven (speed, SoC, charging) | cache 0–2 s + cycle 0–1 s + net ~1 s | 2–3 s | ~4 s |
| poll-only (gear, 12 V, cells) | poll 0–5 s + cache + cycle + net | 4–6 s | ~9 s |
| GPS | fix 0–1 s + cycle 0–1 s + net ~1 s | 1.5–2 s | ~3 s |

The min-interval slider sets the cycle floor, but the *observed* per-field
rate is bounded upstream: the 2 s cache for event fields, the 5 s poll for
poll fields, the 1 Hz GNSS rate for position.

## Behavior by vehicle state

- **Parked (car off):** bus poll at 90 s; speed/gear/engine reads skipped;
  speed and motor power are forced to 0 in the payload (the bus values freeze
  at their last driving sample and must not be republished). `changeOnly`
  means almost nothing transmits between 300 s heartbeats. GPS keeps tracking
  at 1 Hz so the next departure starts with a live fix, but a static position
  rarely passes the change gate — parked MQTT traffic stays minimal.
- **Turn on:** the ACC edge propagates via SDK event within milliseconds →
  immediate unthrottled full bus read → telemetry cache dropped → 5-cycle
  full flush. Fresh values for everything reach HA in roughly 1–3 s.
- **Driving:** table above.
- **Standing still, car on:** identical to driving — ACC, not motion, is the
  cadence switch. Speed 0 comes from the bus.
- **Turn off:** ACC edge within milliseconds → speed/power forced to 0 in the
  next collect → 5-cycle flush ships the final parked state → collector drops
  to the 90 s cadence and stale ACC-gated fields are wiped or skipped.
