# Member 2 — Sensor & GPS Data Acquisition Layer

## What This Module Does

This module is the **data foundation** of the entire Dead Reckoning Lite app.
It reads raw hardware sensors (accelerometer, gyroscope) and GPS from the
Android device and emits clean, typed, timestamped data objects that every
other module in the app consumes.

Nothing else in the app works without this — the CSV logger, the map, the
telemetry display, and the dead-reckoning engine all depend on the data
streams this module produces.

---

## Files & What Each One Does

### `sensors/SensorSample.kt` — IMU Data Container

**What it is:** An immutable Kotlin data class that holds one merged
accelerometer + gyroscope reading.

**Fields:**
| Field | Type | Unit | Description |
|---|---|---|---|
| `timestampNs` | `Long` | nanoseconds | When the reading was taken (elapsedRealtimeNanos clock) |
| `ax` | `Float` | m/s² | Acceleration along the phone's X axis |
| `ay` | `Float` | m/s² | Acceleration along the phone's Y axis |
| `az` | `Float` | m/s² | Acceleration along the phone's Z axis (includes gravity ~9.81) |
| `gx` | `Float` | rad/s | Rotation rate around the phone's X axis |
| `gy` | `Float` | rad/s | Rotation rate around the phone's Y axis |
| `gz` | `Float` | rad/s | Rotation rate around the phone's Z axis |

**Helper methods:**
- `toLogString()` → Human-readable string for Logcat debugging (3 decimal places)
  - Example: `IMU[ts=128394839483] A(0.120, -0.030, 9.810) G(0.001, -0.002, 0.000)`
- `toCsvValues()` → CSV-formatted string matching the unified schema (6 decimal places)
  - Example: `0.120000,-0.030000,9.810000,0.001000,-0.002000,0.000000`
- `CSV_HEADER` → Static constant: `"ax,ay,az,gx,gy,gz"`

**Thread safety:** Immutable data class — safe to pass between threads without locks.

---

### `sensors/ImuManager.kt` — Sensor Hardware Interface

**What it is:** The only class in the entire app that talks to Android's
`SensorManager` hardware API. It registers listeners for the accelerometer
and gyroscope, merges their readings, and emits `SensorSample` objects via
a callback.

**How the merging works:**

The accelerometer and gyroscope are two separate physical sensors that fire
their callbacks independently at ~50 Hz each. The challenge is combining
them into a single unified sample:

```
Timeline:
  Accel fires  →  cache accel reading  →  EMIT merged sample
  Gyro fires   →  cache gyro reading   →  (don't emit — wait for next accel)
  Accel fires  →  cache accel reading  →  EMIT merged sample (uses cached gyro)
  Accel fires  →  cache accel reading  →  EMIT merged sample (same cached gyro)
  Gyro fires   →  cache gyro reading   →  (don't emit)
  Accel fires  →  cache accel reading  →  EMIT merged sample (uses new gyro)
  ...
```

- We emit **only on accelerometer ticks** (the primary sensor), not on every
  sensor event. This keeps the output at ~50 Hz instead of ~100 Hz.
- The gyroscope reading is cached and picked up on the next accel tick.
- The first sample is only emitted after BOTH sensors have reported at least
  once (the "both-sensors gate"), preventing false zero readings.

**Thread safety — the AxisSnapshot pattern:**

Sensor callbacks arrive on a background thread. We need the cached accel/gyro
values to be readable without torn reads (reading half-old, half-new data).

Solution: Instead of caching 3 separate `@Volatile` floats (`ax`, `ay`, `az`),
we cache a single immutable `AxisSnapshot(x, y, z)` object. The JVM guarantees
that object reference assignment is atomic — so when we write
`latestAccel = AxisSnapshot(newX, newY, newZ)`, any thread reading `latestAccel`
either sees the complete old snapshot or the complete new one, never a mix.

**Safety features:**
- **Double-start guard:** If `start()` is called while already running (e.g.,
  after an Activity config change), the previous session is stopped cleanly first.
- **Callback exception safety:** If the downstream callback throws (e.g., the
  logger has a file I/O error), the exception is caught and logged — it does NOT
  kill the SensorManager delivery thread (which would permanently stop all sensor
  events).
- **Sensor availability diagnostics:** If the device lacks an accelerometer or
  gyroscope, this is logged as a warning and exposed via `hasAccelerometer` /
  `hasGyroscope` boolean properties so the UI can show an appropriate message.

**Public API:**
```kotlin
val imuManager = ImuManager(context)

// Check hardware availability
imuManager.hasAccelerometer  // true/false
imuManager.hasGyroscope      // true/false

// Start sampling — callback fires ~50 Hz on a background thread
imuManager.start { sample: SensorSample ->
    // route to logger, viewmodel, etc.
}

// Stop sampling (safe to call multiple times)
imuManager.stop()
```

---

### `location/GpsSample.kt` — GPS Data Container

**What it is:** An immutable Kotlin data class that holds one GPS fix.

**Fields:**
| Field | Type | Unit | Description |
|---|---|---|---|
| `timestampNs` | `Long` | nanoseconds | When the fix was obtained (elapsedRealtimeNanos clock — SAME clock as SensorSample) |
| `latDeg` | `Double` | degrees | Latitude in decimal degrees (WGS-84) |
| `lonDeg` | `Double` | degrees | Longitude in decimal degrees (WGS-84) |
| `speedMps` | `Float` | m/s | Ground speed (0 if unavailable) |
| `bearingDeg` | `Float` | degrees | Heading from true north, clockwise (0 if unavailable) |
| `accuracyM` | `Float` | meters | Horizontal accuracy radius (Float.MAX_VALUE if unavailable) |

**Helper methods:**
- `toLogString()` → Readable Logcat string
  - Example: `GPS[ts=12345678] (28.613940, 77.209021) spd=12.3m/s brg=45.0° acc=3.2m`
- `toCsvValues()` → CSV string (8dp lat/lon, 4dp speed/accuracy)
  - Example: `28.61394000,77.20902100,12.3000,3.2000`
- `CSV_HEADER` → `"gnss_lat,gnss_lon,gnss_speed,gnss_accuracy"`
- `UNIFIED_CSV_HEADER` → Full PRD schema: `"timestamp_ns,ax,ay,az,gx,gy,gz,gnss_lat,gnss_lon,gnss_speed,gnss_accuracy"`
- `distanceTo(other: GpsSample)` → Haversine distance in meters between two fixes
- `distanceTo(lat: Double, lon: Double)` → Haversine distance to raw coordinates
  (for comparing against `ConstantVelocityReckoner.project()` output)

**Why bearingDeg is NOT in `toCsvValues()`:**
The PRD §10.1 unified CSV schema specifies only `gnss_lat,gnss_lon,gnss_speed,gnss_accuracy` — bearing
is intentionally excluded from the CSV. It's only used live by the dead-reckoning
engine during tunnel simulation.

---

### `location/GpsProvider.kt` — GPS Hardware Interface

**What it is:** The only class in the entire app that talks to Google's
`FusedLocationProviderClient`. It requests ~1 Hz high-accuracy GPS updates
and emits `GpsSample` objects via a callback.

**How it works:**

```
FusedLocationProviderClient (Google Play Services)
    │
    ├─ LocationRequest: 1000ms interval, 500ms fastest, HIGH_ACCURACY priority
    │
    └─ LocationCallback
        │
        ├─ Extracts lat, lon, speed, bearing, accuracy from Location object
        ├─ Uses location.elapsedRealtimeNanos for timestamp (same clock as IMU)
        ├─ Safely defaults missing fields (speed=0, bearing=0, accuracy=MAX)
        └─ Emits GpsSample via callback on the main looper thread
```

**Why ~1 Hz and HIGH_ACCURACY:**
- 1 Hz matches the PRD's GPS rate specification and real GNSS receiver behavior
- HIGH_ACCURACY ensures the GPS hardware is used, not just network/WiFi positioning
- 500ms fastest interval accepts faster updates if available from other apps

**Timestamp alignment with IMU:**
Both `ImuManager` and `GpsProvider` use the `SystemClock.elapsedRealtimeNanos()` 
clock domain. This means you can directly subtract timestamps between IMU and GPS
samples to get the real time delta — critical for the dead-reckoning math and for
producing a coherent CSV timeline.

**Safety features:**
- **Play Services check:** Before starting, verifies Google Play Services is
  available via `GoogleApiAvailability`. Exposed as `isPlayServicesAvailable`
  so the UI can show an error on devices without it.
- **Double-start guard:** Same pattern as ImuManager — stops cleanly before
  re-registering.
- **SecurityException handling:** Catches the case where location permission
  is revoked between the permission check and the actual registration call.
  Returns `false` instead of crashing.
- **Callback exception safety:** Same try-catch pattern as ImuManager.
- **Null location handling:** Some devices return null from `LocationResult.lastLocation`
  — we skip these gracefully with a log warning.

**Public API:**
```kotlin
val gpsProvider = GpsProvider(context)

// Check Play Services availability
gpsProvider.isPlayServicesAvailable  // true/false

// Start ~1 Hz GPS updates — callback fires on main thread
// Returns false if Play Services unavailable or permission denied
val started: Boolean = gpsProvider.start { sample: GpsSample ->
    // route to logger, tunnel simulator, map, viewmodel, etc.
}

// Stop updates (safe to call multiple times)
gpsProvider.stop()
```

---

## How Data Flows From This Module to the Rest of the App

```
┌─────────────────────────────────────────────────────────────┐
│ OUR MODULE (Member 2)                                       │
│                                                             │
│  ┌──────────────┐          ┌──────────────┐                 │
│  │ ImuManager   │          │ GpsProvider  │                 │
│  │ (~50 Hz,     │          │ (~1 Hz,      │                 │
│  │  bg thread)  │          │  main thread)│                 │
│  └──────┬───────┘          └──────┬───────┘                 │
│         │ SensorSample            │ GpsSample               │
└─────────┼─────────────────────────┼─────────────────────────┘
          │                         │
          v                         v
┌─────────────────────────────────────────────────────────────┐
│ MainActivity (Member 3) — ROUTES EVERYTHING                 │
│                                                             │
│  imuManager.start { sample ->                               │
│      sensorLogger.logImu(sample)          → Member 4        │
│      mainViewModel.updateImu(sample)      → Member 5        │
│  }                                                          │
│                                                             │
│  gpsProvider.start { sample ->                              │
│      sensorLogger.logGps(sample)          → Member 4        │
│      tunnelSimulator.onRealGpsSample(sample) → Member 1     │
│      if (!tunnelSimulator.isActive) {                       │
│          mapController.moveVehicleTo(sample.latDeg,          │
│                                      sample.lonDeg) → M5    │
│      }                                                      │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
          │                    │                    │
          v                    v                    v
   ┌────────────┐    ┌────────────────┐    ┌──────────────┐
   │SensorLogger│    │TunnelSimulator │    │ MapController│
   │ (Member 4) │    │  (Member 1)    │    │  (Member 5)  │
   │            │    │                │    │              │
   │ CSV file   │    │ Dead reckoning │    │ Map marker   │
   └────────────┘    └────────────────┘    └──────────────┘
```

---

## What Our Module Does NOT Do (By Design)

These are intentionally excluded from our scope per the PRD architecture rule:

- ❌ Does NOT write to CSV — that's Member 4 (SensorLogger)
- ❌ Does NOT update the map — that's Member 5 (MapController)  
- ❌ Does NOT do dead reckoning — that's Member 1 (TunnelSimulator)
- ❌ Does NOT request permissions — that's Member 3 (MainActivity)
- ❌ Does NOT update UI / LiveData — that's Member 5 (MainViewModel)
- ❌ Does NOT call any other module directly — everything goes through MainActivity

---

## Dependencies Other Members Must Fulfill

| Dependency | Who Must Do It | Why |
|---|---|---|
| Add `implementation("com.google.android.gms:play-services-location:21.0.1")` to `build.gradle.kts` | Member 3 | GpsProvider imports from this library |
| Add `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>` to `AndroidManifest.xml` | Member 3 | Required by GpsProvider |
| Request `ACCESS_FINE_LOCATION` at runtime before calling `gpsProvider.start()` | Member 3 | GpsProvider assumes permission already granted |
| Handle `imuManager.hasAccelerometer == false` or `hasGyroscope == false` | Member 3 | Show user warning on budget devices without gyroscope |
| Handle `gpsProvider.isPlayServicesAvailable == false` | Member 3 | Show error on devices without Google Play Services |
| Handle `gpsProvider.start()` returning `false` | Member 3 | Indicates failed GPS startup |
