# Member 1 — Dead Reckoning & Tunnel Simulation Module

## 1. Overview & Commit Context

- **Task**: SIH PS 26168 — Dead Reckoning Lite (Phase 0–1 Prototype)
- **Branch**: `member-1`
- **Commit**: `4ff01d4` — *"member-1 dead reckoning tunnel situtaion"*
- **Module Ownership**: `app/src/main/java/com/sih/deadreckoninglite/deadreckoning/`
- **Scope**: Rule-based dead-reckoning mathematics, simulated GNSS outage mode management, and ~1 Hz position projection ticker.

---

## 2. Executive Summary of Changes

In this session, Member 1's entire deliverable was designed, implemented, validated, and committed:

1. **Two Core Files Implemented**:
   - `deadreckoning/ConstantVelocityReckoner.kt` (76 lines) — Pure mathematical function-holder implementing rule-based equirectangular position projection.
   - `deadreckoning/TunnelSimulator.kt` (205 lines) — State and ticker controller managing the "Simulate Tunnel" mode, timing anchors, and callback dispatching.

2. **Total Code Added**: 281 lines of clean, idiomatic, fully documented Kotlin.
3. **Zero Contamination**: Strictly zero modifications to any files outside `deadreckoning/`. No changes to Member 2 (`sensors/`, `location/`), Member 3 (`MainActivity.kt`), Member 4 (`logging/`), or Member 5 (`map/`, `ui/`).

---

## 3. Detailed Features Added

### 3.1 `ConstantVelocityReckoner.kt` (Rule-Based Position Projector)

The reckoner is a pure function-holder designed in strict accordance with **PRD §9.1**.

#### Responsibilities
- Receives the last known real GPS fix (`GpsSample`) and the elapsed time ($t$) in seconds since the tunnel mode was engaged.
- Computes the new projected latitude and longitude assuming the vehicle continues along its last recorded heading (`bearingDeg`) and speed (`speedMps`).
- Remains 100% decoupled from Android Views, UI state, SensorManager, and GPS callbacks.

#### Mathematical Algorithm (PRD §9.1 Equirectangular Approximation)
```kotlin
fun project(lastFix: GpsSample, elapsedSeconds: Double): Pair<Double, Double> {
    val earthRadiusM = Constants.EARTH_RADIUS_M

    // 1. Total distance traveled along the heading line (meters)
    val distanceM = lastFix.speedMps * elapsedSeconds

    // 2. Convert bearing and latitude to radians
    val bearingRad = Math.toRadians(lastFix.bearingDeg.toDouble())
    val latRad = Math.toRadians(lastFix.latDeg)

    // 3. North/South displacement component
    val deltaLat = (distanceM * cos(bearingRad)) / earthRadiusM

    // 4. East/West displacement component (adjusted for latitude convergence)
    val deltaLon = (distanceM * sin(bearingRad)) /
            (earthRadiusM * cos(latRad))

    // 5. Offset coordinates in decimal degrees
    val newLat = lastFix.latDeg + Math.toDegrees(deltaLat)
    val newLon = lastFix.lonDeg + Math.toDegrees(deltaLon)

    return Pair(newLat, newLon)
}
```

#### Key Technical Decisions & Invariants
- **Non-Recursive Formulation**: Projection always uses `project(lastFix, totalElapsedSeconds)` ($t = 1.0\text{s}, 2.0\text{s}, 3.0\text{s}, \dots$) rather than recursively projecting from the previous projected coordinate. This eliminates floating-point drift accumulation and truncation errors.
- **Shared Geodesic Constant**: Uses `Constants.EARTH_RADIUS_M` ($6,371,000.0\text{ m}$), guaranteeing numerical consistency with Member 2’s Haversine calculation in `GpsSample.distanceTo()`.
- **Zero Sensor Fusion / Zero ML**: Deliberately avoids consuming accelerometer or gyroscope data. This serves as the transparent, rule-based baseline placeholder required for Phase 0–1.

---

### 3.2 `TunnelSimulator.kt` (Mode & Ticker Controller)

The `TunnelSimulator` acts as the decision-maker for position authority during simulated GNSS blackouts.

#### Responsibilities
- Owns the internal `ConstantVelocityReckoner` instance.
- Tracks `isActive` state (controlled via UI switch in `MainActivity`).
- Continuously retains the latest real GPS sample (`onRealGpsSample()`).
- Runs an internal ~1 Hz ticker during active simulation to calculate elapsed time and trigger projections.
- Dispatches projected coordinates to `MainActivity` via the decoupled `onProjectedPosition` callback.

#### Lifecycle & State Machine

```
[Idle / Inactive]
       |
       | setActive(true)
       v
[Capture tunnelStartTimeMs]
       |
       +--> [Start ~1 Hz Ticker via Android Handler]
                 |
                 | (Every 1000 ms)
                 +---> Compute elapsedSeconds = (now - tunnelStartTimeMs) / 1000.0
                 +---> reckoner.project(lastRealFix, elapsedSeconds)
                 +---> onProjectedPosition?.invoke(projLat, projLon)
                 |
       | setActive(false)
       v
[Stop Ticker & Remove Callbacks]
       |
       v
[Reset to Idle]
```

#### Guard Rails & Robustness
1. **Double-Start Guard**:
   If `setActive(true)` is called when already running, it immediately returns (`return`), preventing duplicate ticker instances or memory leaks.
2. **Immediate First Tick**:
   Dispatches the first projection immediately (`handler.post(tickRunnable)`) so the UI does not experience an initial 1-second freeze when the switch is flipped.
3. **Missing / Null Fix Handling**:
   If `setActive(true)` is turned on before any GPS fix has been acquired, the ticker runs safely without crashing, skipping calculation until a valid fix arrives.
4. **Fix Caching While Active**:
   `onRealGpsSample(sample)` continues updating `lastRealFix` even while tunnel mode is active. This ensures the baseline fix remains available if needed.
5. **Monotonic Timing Clock**:
   Uses `SystemClock.elapsedRealtime()` for tracking elapsed milliseconds. This is impervious to system clock jumps, timezone updates, or user adjustments.
6. **Clean Teardown**:
   `setActive(false)` invokes `handler.removeCallbacks(tickRunnable)` and clears `tickerRunning`, leaving zero residual background tasks.

---

## 4. Integration Guide for Member 3 (`MainActivity.kt`)

Member 1's classes do not import or call `MapController`, `MainViewModel`, or any Android Views. The composition root (`MainActivity`) connects them as follows:

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var tunnelSimulator: TunnelSimulator
    private lateinit var mapController: MapController
    private lateinit var gpsProvider: GpsProvider
    private lateinit var sensorLogger: SensorLogger
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tunnelSimulator = TunnelSimulator()

        // 1. Hook the projected output callback into MapController & ViewModel
        tunnelSimulator.onProjectedPosition = { projLat, projLon ->
            mapController.moveVehicleTo(projLat, projLon)
            mapController.addToReckonedPath(projLat, projLon)
            mainViewModel.updateReckonedPosition(projLat, projLon)
        }

        // 2. Feed real GPS stream continuously
        gpsProvider.start { sample ->
            sensorLogger.logGps(sample)
            tunnelSimulator.onRealGpsSample(sample)

            // Only route real GPS to map if Tunnel Mode is OFF
            if (!tunnelSimulator.isActive) {
                mapController.moveVehicleTo(sample.latDeg, sample.lonDeg)
                mapController.addToRealPath(sample.latDeg, sample.lonDeg)
            }
        }

        // 3. Connect UI switch to TunnelSimulator
        binding.switchSimulateTunnel.setOnCheckedChangeListener { _, isChecked ->
            tunnelSimulator.setActive(isChecked)
            mainViewModel.setMode(if (isChecked) Mode.DEAD_RECKONING else Mode.GNSS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelSimulator.setActive(false) // Safety cleanup
    }
}
```

---

## 5. Scope Boundaries & What Was Deferred

In strict compliance with PRD §13 and the Phase 0–1 mandate:

| Feature | Status | Justification |
|---|:---:|---|
| **Constant-Velocity Trig Projection** | **Shipped** | Core MVP deliverable for demonstrating mode-switch UX. |
| **~1 Hz Ticker & Switch Lifecycle** | **Shipped** | Delivers live position progression during outage simulation. |
| **IMU (Accel/Gyro) Fusion** | *Deferred* | IMU data is collected by Member 2/4 for model training; no manual integration before ML is ready. |
| **Kalman Filter (EKF/UKF)** | *Deferred* | Replaced by clean boolean mode switch; full filter slated for Phase 2. |
| **Map Matching / Road Snapping** | *Deferred* | Projected coordinates intentionally follow pure physics heading. |
| **C++ / NDK Core** | *Deferred* | 100% native Kotlin implementation for maximum prototype development speed. |

---

## 6. Verification Checklist

- [x] Exact PRD §9.1 equirectangular formula implemented.
- [x] Frozen public signatures preserved (`project()`, `isActive`, `onRealGpsSample()`, `setActive()`).
- [x] Zero references to `MapController`, `MainViewModel`, or UI layouts inside `deadreckoning/`.
- [x] Monotonic timing clock used (`SystemClock.elapsedRealtime()`).
- [x] No thread-safety or duplicate ticker issues on rapid toggling.
- [x] Changes committed to git branch `member-1` (`4ff01d4`).
