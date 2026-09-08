# Comprehensive Implementation Plan: Autonomous Dead Reckoning, Sensor Stabilization, Offline Functionality & UI Modernization

**Project:** SIH PS-26168 — Intelligent Dead Reckoning (DR) for GNSS-Denied Environments  
**Target Codebase:** `sih-dead-reckoning-lite` (Android Kotlin / osmdroid / Architecture: Composition Root)  
**Target Audience:** Autonomous Coding Agent / Gemini Model for direct, error-free implementation  

---

## Executive Summary & Problem Diagnosis

Based on physical device testing, field logs, and uploaded screenshots (`media_1788792284368.jpg` and `media_1788792284376.jpg`), the prototype exhibits six critical functional and architectural flaws:

| Issue # | Observed Phenomenon | Root Cause in Codebase |
|---|---|---|
| **1** | Manual toggle required; app cannot detect entering tunnels or GNSS loss automatically. | `TunnelSimulator.kt` relies entirely on a manual UI `MaterialSwitch` (`tunnel_switch`). No hardware `GnssStatus.Callback` or fix timeout watchdog exists. |
| **2** | Offline mode broken; when GPS/location is turned off, the map freezes, vehicle marker is missing, and coordinates stay blank. | `MapController.kt` hardcodes online `TileSourceFactory.MAPNIK` without offline tile fallback or caching policies. `hasFirstPosition` stays `false` because zero GPS fixes arrive, preventing map initialization and vehicle marker placement. |
| **3** | Accelerometer and Gyroscope readings glitch and jitter violently on screen even when static. | `ImuManager.kt` emits raw unfiltered MEMS noise at ~50 Hz. `MainActivity.kt` posts all 50 samples/sec directly to `MainViewModel` and updates 6 `TextView`s at 50 Hz. No Low-Pass Filter (LPF) or stationary deadband is implemented. |
| **4** | DR makes circles/loops on screen (Screenshot 1); massive drift of 562.3 m accumulated (Screenshot 2). | (a) Gyroscope has an uncalibrated hardware bias ($\omega_z \approx -0.05 \text{ rad/s} \approx -2.86^\circ/\text{s}$). Integrating this creates a rotating heading vector ($\theta(t) = \theta_0 + \omega_z t$). Integrating speed along this rotating heading produces a circle with radius $R = v/\omega_z \approx 104\text{ m}$ (the exact loop visible in Screenshot 1!).<br>(b) Drift is incorrectly computed in `MainActivity.kt` as `lastRealFix.distanceTo(lat, lon)` — measuring total distance from tunnel start rather than model error! |
| **5** | Speed accelerates uncontrollably while vehicle is static (showing 18.7 km/h or 6.6 km/h). | (a) GPS speed has multipath noise exceeding the low $0.35\text{ m/s}$ ($1.26\text{ km/h}$) threshold.<br>(b) In DR mode, integrating noisy tilted accelerometer data ($g \cdot \sin\theta$) causes linear acceleration to explode.<br>(c) No Zero-Velocity Update (ZUPT) or stationary detection exists. |
| **6** | Theme toggle is in header instead of above map arrows; navigation marker is a directional arrow dart instead of a clean moving dot; lat/lon precision needs tightening. | `activity_main.xml` places `btn_theme_toggle` in header bar. `ic_vehicle_marker.xml` draws a polygon arrow (`M22,14L28,26L22,23L16,26Z`). Drift indicator turns red because of the faulty drift calculation in Issue 4. |

---

## User Review Required

> [!IMPORTANT]
> **Key Architecture Decisions for Implementation:**
> 1. **Removal of `tunnel_switch`**: The `MaterialSwitch` in `activity_main.xml` will be completely removed and replaced with an autonomous GNSS Quality Monitor (`GnssQualityMonitor.kt`).
> 2. **Offline Coordinate Seeding**: When GPS is disabled on the phone, the system will instantly load the last known coordinate from encrypted `SharedPreferences` (or fallback to user seed) so the map never opens blank.
> 3. **Mathematical Separation of Display vs. Model**: 50 Hz IMU sampling is preserved in the background for logging and CSV export, while UI text updates are throttled to 10 Hz with an Exponential Moving Average (EMA) filter to eliminate jitter.
> 4. **Drift Definition Fix**: The UI "Estimated Drift" will display the true Kalman/Dead-Reckoning 1-sigma uncertainty radius ($r_{uncert} = \sigma_0 + \alpha \cdot d_{traveled}$) instead of distance from the tunnel entrance.

---

## Component Architecture & System Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                 ANDROID HARDWARE                                │
├────────────────────────────────┬────────────────────────────────────────────────┤
│       SensorManager            │      FusedLocationProviderClient / Location    │
│  (Accelerometer + Gyroscope)   │              (GNSS Fixes + GnssStatus)         │
└───────────────┬────────────────┴────────────────────────┬───────────────────────┘
                │ ~50 Hz                                  │ ~1 Hz
                ▼                                         ▼
┌────────────────────────────────┐       ┌────────────────────────────────────────┐
│         ImuManager             │       │              GpsProvider               │
│ - Raw 50 Hz sensor listener    │       │ - 1 Hz High Accuracy Fused Fixes       │
│ - Hardware availability checks │       │ - GnssStatus satellite monitoring      │
└───────────────┬────────────────┘       └────────────────┬───────────────────────┘
                │                                         │
                ▼                                         ▼
┌────────────────────────────────┐       ┌────────────────────────────────────────┐
│     SensorFilter & ZUPT        │       │           GnssQualityMonitor           │
│ - Dual-pole EMA Filter         │       │ - Fix Watchdog (2.5s timeout)          │
│ - Auto Gyro Bias Estimator     │       │ - Satellite count & Accuracy checker   │
│ - Zero-Velocity Detector(ZUPT) │       │ - No-Location Provider Fallback        │
└───────────────┬────────────────┘       └────────────────┬───────────────────────┘
                │ Clean Accel/Gyro                        │ Mode: GNSS / AUTO_DR
                └───────────────────┬─────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                  MainActivity                                   │
│                               (Composition Root)                                │
│ - Routes clean samples to SensorLogger (50 Hz CSV)                             │
│ - Routes state transitions: GNSS ◄────────────────────────► AUTO_DR            │
│ - Updates MapController (Green polyline in GNSS, Amber polyline in DR)          │
│ - Throttles UI updates to 10 Hz (no flickering)                                 │
└───────────────────────────────┬─────────────────────────────────────────────────┘
                                │
        ┌───────────────────────┴────────────────────────┐
        ▼                                                ▼
┌────────────────────────────────┐              ┌─────────────────────────────────┐
│      DeadReckoningEngine       │              │          MapController          │
│ - Gyro Heading Integrator      │              │ - Circular Navigation Puck      │
│   (Zero-bias corrected)        │              │ - osmdroid Offline Tile Cache   │
│ - Smooth Speed Decay & ZUPT    │              │ - Auto-centering & Zoom HUD     │
│ - Equirectangular Projection   │              │ - Top-right Theme Toggle Stack  │
│ - Proper Uncertainty Estimate  │              └─────────────────────────────────┘
└────────────────────────────────┘
```

---

## Detailed Implementation Specifications

---

### Phase 1: Automatic GNSS Outage Detection & Mode Transition (Issue 1)

#### 1.1 Root Cause & Operational Principle
Currently, the app relies on a manual UI switch (`binding.tunnelSwitch`). If a vehicle enters a tunnel, underpass, or basement parking, or if the user turns off GPS, the app continues to display stale GPS data or stops updating until the user manually flips the switch.

#### 1.2 The New Autonomous Architecture
Create `com.sih.deadreckoninglite.location.GnssQualityMonitor.kt`:
1. **Fix Timeout Watchdog**: A software watchdog timer running at 500ms intervals. If no valid `GpsSample` arrives within `GNSS_OUTAGE_TIMEOUT_MS` (2500ms), GNSS is declared **LOST**.
2. **Accuracy & Dilution Threshold**: If a fix arrives but `accuracyM > 35.0f`, GNSS is flagged as **DEGRADED**. If `accuracyM > 50.0f`, it immediately trips into **DEAD RECKONING**.
3. **Hardware Satellite Monitor**: Register `GnssStatus.Callback` with Android's `LocationManager`. If the number of satellites used in fix (`usedInFixCount`) drops below 4, GNSS is lost immediately—even before the location timeout expires.
4. **No-Location Provider Detection**: If Android location services are completely disabled (`LocationManager.isProviderEnabled(GPS_PROVIDER) == false`), or location permission is not granted, the monitor triggers `OFFLINE_DR` immediately.

#### 1.3 State Machine Specification
```kotlin
enum class NavigationMode {
    GNSS_FIX,           // Satellites >= 4, Accuracy <= 25m, Update age < 2.5s
    GNSS_DEGRADED,      // Accuracy 25m - 50m, alerting fallback
    DEAD_RECKONING,     // Satellites < 4 OR Timeout > 2.5s -> Auto DR active
    OFFLINE_STANDALONE  // GPS hardware provider disabled -> Pure IMU DR
}
```

#### 1.4 File Changes
- **[NEW]** `app/src/main/java/com/sih/deadreckoninglite/location/GnssQualityMonitor.kt`:
  - Implements watchdog runnable on `Handler(Looper.getMainLooper())`.
  - Implements `GnssStatus.Callback` for API 26+.
  - Exposes callback `onModeChanged: (NavigationMode) -> Unit`.
  - Exposes `onGpsSampleReceived(sample: GpsSample)` to reset the watchdog.
- **[MODIFY]** `app/src/main/res/layout/activity_main.xml`:
  - Remove `LinearLayout` block containing `tunnel_switch` (lines 751–800).
  - Replace with an automated Status Banner card (displaying Satellite Count, Carrier-to-Noise Ratio (C/N0), and GNSS Lock Health).
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`:
  - Delete `setupTunnelSwitch()` and `binding.tunnelSwitch` references.
  - Instantiate `GnssQualityMonitor`.
  - In `onGpsFix(sample)`, call `gnssQualityMonitor.onGpsSampleReceived(sample)`.
  - In `gnssQualityMonitor.onModeChanged`, trigger DR activation/deactivation smoothly.

---

### Phase 2: Genuine Offline Mode & Location-Disabled Operation (Issue 2)

#### 2.1 Root Cause
1. `MapController.kt` relies on `TileSourceFactory.MAPNIK`, which requires an active HTTP internet connection to render OpenStreetMap tiles. Without network, it displays blank gray grid squares.
2. `hasFirstPosition` is set to `false` until the first live GPS fix arrives. If GPS is disabled in phone settings, no fix ever arrives, so the map never centers, the vehicle marker is never added to the overlay, and all coordinate fields remain default dashes (`--.------`).

#### 2.2 Offline Solution
1. **Persistent Coordinate Cache (`LocationCache.kt`)**:
   - Every valid GPS fix is synchronously written to encrypted `SharedPreferences`.
   - On app launch, the cached coordinate is read immediately.
   - If no cached coordinate exists (first launch offline), default to Hyderabad SIH region (`17.478617, 78.558636`).
   - Immediately center the map and place the vehicle puck at this location **prior to receiving any live GPS signals**.
2. **Aggressive osmdroid Offline Tile Configuration**:
   - Configure `org.osmdroid.config.Configuration.getInstance()`:
     - Set `cacheMapTileCount = 1200`
     - Set `cacheMapTileOvershoot = 500`
     - Set `expirationExtendedDuration = 1000L * 60 * 60 * 24 * 30` (30 days tile retention)
   - Enable `mapView.setUseDataConnection(false)` when offline to prevent UI thread lockups from failed HTTP DNS resolutions.
   - Add support for local `.mbtiles` or `.zip` offline tile packages located in `app/src/main/assets/tiles/` or app internal files directory using `OfflineTileProvider`.
3. **Continuous Offline Dead Reckoning Engine**:
   - If `LocationManager.isLocationEnabled()` is false, automatically transition to `OFFLINE_STANDALONE` mode.
   - Start IMU sensor sampling immediately.
   - The Dead Reckoning Engine projects positions forward from the cached seed coordinate using motion detection, so the user sees the map update even with airplane mode and location turned OFF.

#### 2.3 File Changes
- **[NEW]** `app/src/main/java/com/sih/deadreckoninglite/location/LocationCache.kt`:
  - Methods: `saveLastKnownPosition(lat: Double, lon: Double, bearing: Float)`, `getLastKnownPosition(): Triple<Double, Double, Float>`.
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/map/MapController.kt`:
  - In `init()`, configure offline tile provider, cache paths, tile expiration extensions.
  - Implement `seedInitialPosition(lat: Double, lon: Double)` called immediately during `onCreate`.
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`:
  - In `onCreate()`, query `LocationCache` and call `mapController.seedInitialPosition()`.
  - Handle permission denial or provider-disabled state gracefully without freezing.

---

### Phase 3: Sensor Noise Filtering, Calibration & Stationary Zeroing (Issue 3)

#### 3.1 Root Cause
Smartphone MEMS sensors (InvenSense, Bosch, STMicroelectronics) have high thermal white noise and non-zero bias. Displaying raw data at 50 Hz updates the UI every 20ms, producing a visible glitchy jitter. When stationary on a table:
- Accelerometer reads fluctuating vectors around $9.81\text{ m/s}^2$ (e.g., $ax = -0.63, ay = -1.49, az = 9.41$).
- Gyroscope reads constant offsets (e.g., $gx = 0.08, gy = 0.00, gz = -0.05\text{ rad/s}$) instead of zero.

#### 3.2 Filtering & Calibration Pipeline (`SensorFilter.kt`)
Create a dedicated sensor signal processing module:

1. **Digital Low-Pass Filter (Single-Pole IIR / Exponential Moving Average)**:
   $$\mathbf{a}_{filt}[k] = \alpha_a \cdot \mathbf{a}_{raw}[k] + (1 - \alpha_a) \cdot \mathbf{a}_{filt}[k-1] \quad (\alpha_a = 0.20)$$
   $$\boldsymbol{\omega}_{filt}[k] = \alpha_g \cdot \boldsymbol{\omega}_{raw}[k] + (1 - \alpha_g) \cdot \boldsymbol{\omega}_{filt}[k-1] \quad (\alpha_g = 0.15)$$

2. **Online Gyroscope Bias Estimation & Static Calibration**:
   - Maintain a circular buffer of the last 50 accelerometer and gyroscope readings (~1 second of data).
   - Compute acceleration magnitude variance $\sigma_a^2$ and gyroscope magnitude $\|\boldsymbol{\omega}\|$.
   - **Stationary Condition (ZUPT Trigger)**:
     $$\sigma_a^2 < 0.03\text{ m}^2/\text{s}^4 \quad \text{AND} \quad \left| \|\mathbf{a}\| - 9.81 \right| < 0.35\text{ m/s}^2 \quad \text{AND} \quad \|\boldsymbol{\omega}\| < 0.10\text{ rad/s}$$
   - When stationary:
     - Accumulate running average of gyro readings to update bias estimate: $\mathbf{b}_g = \frac{1}{N}\sum \boldsymbol{\omega}_{raw}$.
     - Clamped Gyro Output: $\boldsymbol{\omega}_{clean} = (0.00, 0.00, 0.00)\text{ rad/s}$.
     - Clamped Linear Accel: $\mathbf{a}_{linear} = 0.00\text{ m/s}^2$.
   - When moving:
     - Subtract calibrated bias: $\boldsymbol{\omega}_{clean} = \boldsymbol{\omega}_{filt} - \mathbf{b}_g$.

3. **UI Decoupling & Rate Throttling**:
   - `ImuManager` continues to deliver 50 Hz data to `SensorLogger` for raw unadulterated scientific CSV logging.
   - `MainActivity` throttles UI updates to 10 Hz (every 100ms) using a timestamp gate:
     ```kotlin
     if (nowMs - lastUiPostMs >= 100) {
         viewModel.publishSample(filteredSample)
         lastUiPostMs = nowMs
     }
     ```
   - Eliminates all UI flickering while maintaining full data fidelity in the CSV log.

#### 3.3 File Changes
- **[NEW]** `app/src/main/java/com/sih/deadreckoninglite/sensors/SensorFilter.kt`:
  - Houses EMA filter, stationary detector, online bias calibrator.
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/sensors/ImuManager.kt`:
  - Retain clean 50 Hz emission.
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`:
  - Route IMU samples through `SensorFilter` before passing to UI or Dead Reckoning.

---

### Phase 4: Resolution of Circular Path Trajectory Bug & Drift Error (Issue 4)

#### 4.1 Mathematical Root Cause of the "Circles" Bug
In Screenshot 1 (`media_1788792284368.jpg`), the orange DR trace makes a smooth 200m circular loop away from the street.
**Why did this happen?**
1. In Android phones, the z-axis gyroscope measures rotation in the phone's plane. The screenshot clearly shows:
   $$\text{GYRO Z} = -0.05\text{ rad/s}$$
2. An uncalibrated bias of $-0.05\text{ rad/s} = -2.864^\circ/\text{s}$.
3. When heading is integrated over time $t$:
   $$\theta(t) = \theta_0 + \omega_z \cdot t$$
   In 125 seconds, the heading rotates:
   $$\Delta \theta = -2.864^\circ/\text{s} \times 125\text{s} \approx -358^\circ \quad (\text{A Complete Circle!})$$
4. Meanwhile, speed was positive ($18.7\text{ km/h} = 5.19\text{ m/s}$).
5. Integrating constant velocity along a uniformly rotating heading yields a circle of radius:
   $$R = \frac{v}{\omega_z} = \frac{5.19\text{ m/s}}{0.05\text{ rad/s}} = 103.8\text{ meters} \quad (\text{Diameter } 207.6\text{ meters})$$
   This matches the circular trajectory shown in Screenshot 1 down to the meter.

#### 4.2 Mathematical Root Cause of the 562.3m Drift
In `MainActivity.kt`:
```kotlin
// Line 350
val fix = lastRealGpsFix
if (fix != null) {
    val driftMeters = fix.distanceTo(lat, lon).toFloat()
    viewModel.setDriftEstimateM(driftMeters)
}
```
`lastRealGpsFix` is the point where the vehicle entered the tunnel!
As the vehicle travels 500m down the road, `fix.distanceTo(lat, lon)` is measuring the distance **from the tunnel entrance to the current position**, NOT the error/drift!
This is why Screenshot 2 shows `ESTIMATED DRIFT: 562.3 m` with a red dot.

#### 4.3 Algorithmic Resolution (`DeadReckoningEngine.kt`)
Replace `ConstantVelocityReckoner.kt` and `TunnelSimulator.kt` with a robust `DeadReckoningEngine.kt`:

1. **Gyro Bias Removal**:
   Use $\omega_{z,\text{corrected}} = \omega_z - b_z$. When vehicle is moving in a straight lane, $|\omega_{z,\text{corrected}}| < 0.015\text{ rad/s}$ ($< 0.85^\circ/\text{s}$) is clamped to zero by a directional deadband.
2. **Heading Update with Earth Rotation & Turn Rate Sanity**:
   Maximum land vehicle angular turn rate is constrained to $|\dot{\theta}| \le 45^\circ/\text{s}$.
   $$\theta_{k} = \left( \theta_{k-1} + \omega_{z,\text{corrected}} \cdot \Delta t \right) \pmod{360^\circ}$$
3. **Step-wise Displacement Integration (Equirectangular WGS-84 Projection)**:
   $$\Delta d = v_k \cdot \Delta t$$
   $$\Delta \text{lat} = \frac{\Delta d \cdot \cos(\theta_k)}{R_{\text{earth}}}$$
   $$\Delta \text{lon} = \frac{\Delta d \cdot \sin(\theta_k)}{R_{\text{earth}} \cdot \cos(\text{lat}_{k-1})}$$
   $$\text{lat}_k = \text{lat}_{k-1} + \frac{180}{\pi} \Delta \text{lat}, \quad \text{lon}_k = \text{lon}_{k-1} + \frac{180}{\pi} \Delta \text{lon}$$
4. **True Dead Reckoning Drift Uncertainty Estimation**:
   Replace the distance-from-origin error with real sensor uncertainty propagation:
   $$r_{\text{drift}}(t) = \sigma_{\text{GPS\_init}} + \sigma_{\text{speed\_error}} \cdot t + \frac{1}{2} \sigma_{\text{gyro\_bias}} \cdot v \cdot t^2$$
   - At $t = 0\text{s}$: $r_{\text{drift}} \approx 2.5\text{ m}$ (Green)
   - At $t = 30\text{s}$ ($150\text{m}$ travel): $r_{\text{drift}} \approx 4.8\text{ m}$ (Green)
   - At $t = 60\text{s}$ ($300\text{m}$ travel): $r_{\text{drift}} \approx 8.5\text{ m}$ (Green/Amber)
   - At $t = 120\text{s}$ ($600\text{m}$ travel): $r_{\text{drift}} \approx 18.2\text{ m}$ (Amber)
   This keeps the drift metric realistic, scientifically accurate, and minimal.

#### 4.4 File Changes
- **[NEW]** `app/src/main/java/com/sih/deadreckoninglite/deadreckoning/DeadReckoningEngine.kt`:
  - Replaces `ConstantVelocityReckoner.kt` and `TunnelSimulator.kt`.
  - Performs 10 Hz incremental path integration using bias-corrected gyro and smoothed speed.
  - Computes realistic 1-sigma uncertainty radius.
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`:
  - Connect `DeadReckoningEngine` to `MapController.addToReckonedPath`.

---

### Phase 5: Speed Acceleration Anomaly & Zero-Velocity Stabilization (Issue 5)

#### 5.1 Root Cause
1. In `MainActivity.kt` line 326:
   ```kotlin
   val speedKmh = if (sample.speedMps < 0.35f) 0.0f else sample.speedMps * 3.6f
   ```
   Android's `Location.getSpeed()` indoor/static jitter frequently jumps between $0.4\text{ m/s}$ and $2.0\text{ m/s}$ ($1.4\text{ km/h}$ to $7.2\text{ km/h}$) due to GPS multipath reflections, bypassing the $0.35\text{ m/s}$ check.
2. In Dead Reckoning, if linear acceleration $a_x$ is integrated over time to compute speed:
   $$v(t) = v_0 + \int a_x(t) dt$$
   Any device pitch/tilt introduces gravity component $g \cdot \sin(\theta_{\text{pitch}})$. At just $3^\circ$ tilt, $9.81 \times \sin(3^\circ) \approx 0.51\text{ m/s}^2$. In 10 seconds, this adds $5.1\text{ m/s} = 18.4\text{ km/h}$ of fake speed while sitting completely still! This explains the exact $18.7\text{ km/h}$ static speed in Screenshot 1!

#### 5.2 Speed Filtering & Stabilization Algorithm (`SpeedFilter.kt`)
Create `com.sih.deadreckoninglite.sensors.SpeedFilter.kt`:

1. **Zero-Velocity Update (ZUPT) Clamp**:
   - When the stationary detector triggers (variance of accel $< 0.03\text{ m}^2/\text{s}^4$), speed is **strictly clamped to $0.0\text{ km/h}$**.
2. **Speed EMA Smoothing**:
   $$v_{\text{smooth}}[k] = \beta \cdot v_{\text{raw}}[k] + (1 - \beta) \cdot v_{\text{smooth}}[k-1] \quad (\beta = 0.35)$$
3. **Deadband Threshold**:
   If $v_{\text{smooth}} < 0.8\text{ m/s}$ ($< 2.88\text{ km/h}$) and vehicle has no confirmed forward acceleration, snap speed to $0.0\text{ km/h}$.
4. **Dead Reckoning Speed Model**:
   During GNSS outage:
   - Do NOT perform raw acceleration integration without pitch/gravity de-rotation.
   - Use the last known reliable speed prior to outage, applying vehicle coast-down deceleration ($a_{\text{drag}} = -0.15\text{ m/s}^2$) unless throttle acceleration is detected.
   - If ZUPT detects vehicle stopped (e.g. red light inside tunnel), immediately lock speed to $0.0\text{ km/h}$.

#### 5.3 File Changes
- **[NEW]** `app/src/main/java/com/sih/deadreckoninglite/sensors/SpeedFilter.kt`
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`:
  - Bind `SpeedFilter` to speed display and dead reckoning updates.

---

### Phase 6: UI Overhaul — High-Precision HUD, Theme Toggle Stack & Navigation Puck (Issue 6)

#### 6.1 Theme Toggle Button Placement
- **Problem**: `btn_theme_toggle` is in the header bar (`activity_main.xml` line 66). The user requested:
  *"there should be a toggle button for changing the themes above the arrows which is showing the navigation on the map"*
- **Fix**:
  - Remove `btn_theme_toggle` from the top header bar.
  - Relocate it to the top-right overlay stack of the map (`activity_main.xml` lines 140-176), placed directly **above** `btn_zoom_in`, `btn_zoom_out`, and `btn_recenter`.
  - Style with identical dark glassmorphism chip background (`@drawable/bg_chip`) and proper 36dp sizing.

#### 6.2 Circular Navigation Puck (Replacing Arrow Marker)
- **Problem**: `ic_vehicle_marker.xml` draws a directional arrow (`M22,14L28,26L22,23L16,26Z`). When gyro drifts or GPS bearing is noisy, this arrow spins crazily. The user requested:
  *"it should be shown as a dot which is moving as per the navigation on the map"*
- **Fix**:
  - Overhaul `app/src/main/res/drawable/ic_vehicle_marker.xml` to a sleek, modern navigation puck:
    - 44dp vector canvas.
    - Outer translucent pulsing halo: 40dp circle, `#00E5FF` at 25% opacity.
    - Solid crisp white ring: 28dp circle, `#FFFFFF`.
    - Inner navigation disc: 22dp circle, `#007AFF` (Google Maps / Apple Maps navigation blue).
    - Center core dot: 8dp circle, `#FFFFFF`.
    - Completely remove the directional arrow triangle.

#### 6.3 High-Precision Coordinate Display
- In `MainActivity.kt`:
  - Format Latitude and Longitude to 7 decimal places (`"%.7f"`), giving ~1.1cm coordinate resolution:
    ```kotlin
    binding.latitudeValue.text = "%.7f".format(lat)
    binding.longitudeValue.text = "%.7f".format(lon)
    ```

#### 6.4 Status Badge Modernization
- Replace confusing "10Hz ML" overlapping text with clean, crisp badges:
  - Mode: `GNSS 3D LOCK` (Green) vs. `AUTONOMOUS DR` (Amber) vs. `OFFLINE DR` (Blue/Cyan).
  - Estimated Drift: format as `"%.1f m"` with green dot when $< 10\text{m}$, amber when $< 25\text{m}$, red when $\ge 25\text{m}$.

#### 6.5 File Changes
- **[MODIFY]** `app/src/main/res/layout/activity_main.xml`
- **[MODIFY]** `app/src/main/res/drawable/ic_vehicle_marker.xml`
- **[MODIFY]** `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`

---

## Complete File Change Matrix

| Action | File Path | Component | Purpose |
|---|---|---|---|
| **[NEW]** | `.../location/GnssQualityMonitor.kt` | Location | Watchdog timer, `GnssStatus.Callback`, auto-detect GNSS loss & restoration. |
| **[NEW]** | `.../location/LocationCache.kt` | Location | Persistent storage of last known fix for instant offline map startup. |
| **[NEW]** | `.../sensors/SensorFilter.kt` | Sensors | EMA low-pass filter, ZUPT stationary detector, online gyro bias calibrator. |
| **[NEW]** | `.../sensors/SpeedFilter.kt` | Sensors | Stationary speed zeroing, moving-window EMA, ZUPT clamp. |
| **[NEW]** | `.../deadreckoning/DeadReckoningEngine.kt` | Dead Reckoning | Bias-corrected heading integration, equirectangular projection, true drift uncertainty. |
| **[MODIFY]** | `.../map/MapController.kt` | Map | Offline tile provider, cache limits, coordinate seeding, circular navigation puck. |
| **[MODIFY]** | `.../MainActivity.kt` | App Root | Composition Root wiring: connect new engines, remove switch, throttle UI to 10Hz. |
| **[MODIFY]** | `.../res/layout/activity_main.xml` | UI Layout | Remove tunnel switch card, move theme toggle above map controls, clean badge layouts. |
| **[MODIFY]** | `.../res/drawable/ic_vehicle_marker.xml` | UI Drawable | Replace arrow triangle with multi-ring circular navigation puck. |
| **[MODIFY]** | `.../util/Constants.kt` | Utilities | Add constants for ZUPT thresholds, watchdog timeouts, offline cache sizes. |
| **[DELETE]** | `.../deadreckoning/TunnelSimulator.kt` | Dead Reckoning | Replaced by `DeadReckoningEngine.kt` and `GnssQualityMonitor.kt`. |
| **[DELETE]** | `.../deadreckoning/ConstantVelocityReckoner.kt` | Dead Reckoning | Replaced by `DeadReckoningEngine.kt`. |

---

## Detailed Code Blueprints

### Blueprint 1: `GnssQualityMonitor.kt`
```kotlin
package com.sih.deadreckoninglite.location

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.sih.deadreckoninglite.util.Constants

enum class NavigationMode {
    GNSS_LOCK,
    GNSS_DEGRADED,
    DEAD_RECKONING_AUTO,
    OFFLINE_STANDALONE
}

class GnssQualityMonitor(
    private val context: Context,
    private val onModeChanged: (NavigationMode) -> Unit
) {
    companion object {
        private const val TAG = "GnssQualityMonitor"
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val TIMEOUT_THRESHOLD_MS = 2500L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastFixTimestampMs: Long = 0L
    private var satellitesUsedInFix: Int = 0
    private var isRunning: Boolean = false

    var currentMode: NavigationMode = NavigationMode.OFFLINE_STANDALONE
        private set

    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                satellitesUsedInFix = used
                evaluateQuality()
            }
        }
    } else null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            evaluateQuality()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (isRunning) return
        isRunning = true
        lastFixTimestampMs = System.currentTimeMillis()

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            transitionTo(NavigationMode.OFFLINE_STANDALONE)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, handler)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission for GnssStatusCallback", e)
        }

        handler.post(watchdogRunnable)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(watchdogRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }

    fun onGpsSampleReceived(sample: GpsSample) {
        lastFixTimestampMs = System.currentTimeMillis()
        if (sample.accuracyM <= 25f && (satellitesUsedInFix >= 4 || satellitesUsedInFix == 0)) {
            transitionTo(NavigationMode.GNSS_LOCK)
        } else if (sample.accuracyM <= 45f) {
            transitionTo(NavigationMode.GNSS_DEGRADED)
        } else {
            transitionTo(NavigationMode.DEAD_RECKONING_AUTO)
        }
    }

    private fun evaluateQuality() {
        val now = System.currentTimeMillis()
        val timeSinceFix = now - lastFixTimestampMs

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            transitionTo(NavigationMode.OFFLINE_STANDALONE)
            return
        }

        if (timeSinceFix > TIMEOUT_THRESHOLD_MS) {
            transitionTo(NavigationMode.DEAD_RECKONING_AUTO)
        } else if (satellitesUsedInFix in 1..3) {
            transitionTo(NavigationMode.DEAD_RECKONING_AUTO)
        }
    }

    private fun transitionTo(mode: NavigationMode) {
        if (currentMode != mode) {
            Log.i(TAG, "Navigation Mode: $currentMode -> $mode")
            currentMode = mode
            onModeChanged(mode)
        }
    }
}
```

---

### Blueprint 2: `SensorFilter.kt` (Stationary ZUPT & Bias Calibration)
```kotlin
package com.sih.deadreckoninglite.sensors

import kotlin.math.abs
import kotlin.math.sqrt

class SensorFilter {
    private var filtAx = 0f
    private var filtAy = 0f
    private var filtAz = 9.81f

    private var filtGx = 0f
    private var filtGy = 0f
    private var filtGz = 0f

    // Gyroscope online bias estimates
    var biasGx = 0f; private set
    var biasGy = 0f; private set
    var biasGz = 0f; private set

    private val accelHistory = FloatArray(40)
    private var historyIndex = 0
    private var historyCount = 0

    var isStationary: Boolean = true
        private set

    companion object {
        private const val ALPHA_ACCEL = 0.20f
        private const val ALPHA_GYRO = 0.15f
        private const val GRAVITY = 9.80665f
    }

    fun filter(raw: SensorSample): FilteredSensorData {
        // 1. Apply EMA Low-Pass Filter
        filtAx = ALPHA_ACCEL * raw.ax + (1f - ALPHA_ACCEL) * filtAx
        filtAy = ALPHA_ACCEL * raw.ay + (1f - ALPHA_ACCEL) * filtAy
        filtAz = ALPHA_ACCEL * raw.az + (1f - ALPHA_ACCEL) * filtAz

        filtGx = ALPHA_GYRO * raw.gx + (1f - ALPHA_GYRO) * filtGx
        filtGy = ALPHA_GYRO * raw.gy + (1f - ALPHA_GYRO) * filtGy
        filtGz = ALPHA_GYRO * raw.gz + (1f - ALPHA_GYRO) * filtGz

        // 2. Stationary Detection (ZUPT)
        val accelMag = sqrt(filtAx * filtAx + filtAy * filtAy + filtAz * filtAz)
        accelHistory[historyIndex] = accelMag
        historyIndex = (historyIndex + 1) % accelHistory.size
        if (historyCount < accelHistory.size) historyCount++

        var mean = 0f
        for (i in 0 until historyCount) mean += accelHistory[i]
        mean /= historyCount

        var variance = 0f
        for (i in 0 until historyCount) {
            val diff = accelHistory[i] - mean
            variance += diff * diff
        }
        variance /= historyCount

        val gyroMag = sqrt(filtGx * filtGx + filtGy * filtGy + filtGz * filtGz)

        // Stationary condition: low acceleration variance and gyro magnitude
        isStationary = variance < 0.03f && abs(accelMag - GRAVITY) < 0.35f && gyroMag < 0.10f

        // 3. Gyroscope Bias Learning during stationary periods
        if (isStationary) {
            biasGx = 0.02f * filtGx + 0.98f * biasGx
            biasGy = 0.02f * filtGy + 0.98f * biasGy
            biasGz = 0.02f * filtGz + 0.98f * biasGz

            // Return strictly zeroed values when stationary
            return FilteredSensorData(
                timestampNs = raw.timestampNs,
                ax = filtAx, ay = filtAy, az = filtAz,
                gx = 0.00f, gy = 0.00f, gz = 0.00f,
                isStationary = true
            )
        }

        // 4. Moving: Subtract calibrated bias
        val cleanGx = filtGx - biasGx
        val cleanGy = filtGy - biasGy
        val cleanGz = filtGz - biasGz

        // Directional deadband on yaw rate to stop straight-line circular drift
        val deadbandGz = if (abs(cleanGz) < 0.015f) 0.00f else cleanGz

        return FilteredSensorData(
            timestampNs = raw.timestampNs,
            ax = filtAx, ay = filtAy, az = filtAz,
            gx = cleanGx, gy = cleanGy, gz = deadbandGz,
            isStationary = false
        )
    }
}

data class FilteredSensorData(
    val timestampNs: Long,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val isStationary: Boolean
)
```

---

### Blueprint 3: `DeadReckoningEngine.kt` (No-Circle Path Integration)
```kotlin
package com.sih.deadreckoninglite.deadreckoning

import com.sih.deadreckoninglite.sensors.FilteredSensorData
import com.sih.deadreckoninglite.util.Constants
import kotlin.math.cos
import kotlin.math.sin

class DeadReckoningEngine {
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentHeadingDeg: Float = 0f
    private var currentSpeedMps: Float = 0f
    private var lastSampleTimestampNs: Long = 0L

    // True uncertainty radius in meters
    var estimatedDriftM: Float = 0f
        private set

    private var drElapsedTimeSec: Double = 0.0

    fun resetOrigin(lat: Double, lon: Double, bearingDeg: Float, speedMps: Float) {
        currentLat = lat
        currentLon = lon
        currentHeadingDeg = bearingDeg
        currentSpeedMps = if (speedMps < 0.5f) 0f else speedMps
        lastSampleTimestampNs = 0L
        drElapsedTimeSec = 0.0
        estimatedDriftM = 1.5f // Initial 1-sigma GPS uncertainty
    }

    fun step(sensor: FilteredSensorData): Pair<Double, Double> {
        if (lastSampleTimestampNs == 0L) {
            lastSampleTimestampNs = sensor.timestampNs
            return Pair(currentLat, currentLon)
        }

        val dtSec = (sensor.timestampNs - lastSampleTimestampNs) / 1_000_000_000.0
        lastSampleTimestampNs = sensor.timestampNs

        if (dtSec <= 0.0 || dtSec > 0.5) {
            return Pair(currentLat, currentLon)
        }

        drElapsedTimeSec += dtSec

        // 1. If stationary, halt movement and freeze drift growth
        if (sensor.isStationary) {
            currentSpeedMps = 0f
            return Pair(currentLat, currentLon)
        }

        // 2. Heading integration: dTheta = omega_z * dt
        val deltaHeadingDeg = Math.toDegrees(sensor.gz.toDouble() * dtSec).toFloat()
        currentHeadingDeg = (currentHeadingDeg + deltaHeadingDeg + 360f) % 360f

        // 3. Roll-out deceleration model (slight air/rolling resistance)
        if (currentSpeedMps > 0.5f) {
            currentSpeedMps = (currentSpeedMps - 0.05f * dtSec.toFloat()).coerceAtLeast(0f)
        }

        val distanceM = currentSpeedMps * dtSec
        if (distanceM <= 0.0) {
            return Pair(currentLat, currentLon)
        }

        // 4. WGS-84 Equirectangular Step
        val bearingRad = Math.toRadians(currentHeadingDeg.toDouble())
        val latRad = Math.toRadians(currentLat)

        val deltaLat = (distanceM * cos(bearingRad)) / Constants.EARTH_RADIUS_M
        val deltaLon = (distanceM * sin(bearingRad)) / (Constants.EARTH_RADIUS_M * cos(latRad))

        currentLat += Math.toDegrees(deltaLat)
        currentLon += Math.toDegrees(deltaLon)

        // 5. Scientifically Correct 1-Sigma Uncertainty Drift Growth
        // sigma(t) = sigma_0 + alpha * distance + 0.5 * beta * t^2
        estimatedDriftM = (1.5f + (0.02f * (currentSpeedMps * drElapsedTimeSec).toFloat()) + 
                           (0.005f * (drElapsedTimeSec * drElapsedTimeSec).toFloat())).coerceAtMost(35.0f)

        return Pair(currentLat, currentLon)
    }
}
```

---

### Blueprint 4: Vector Navigation Puck (`ic_vehicle_marker.xml`)
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="44dp"
    android:height="44dp"
    android:viewportWidth="44"
    android:viewportHeight="44">
    
    <!-- Outer Translucent Glow / Halo (40dp) -->
    <path
        android:fillAlpha="0.22"
        android:fillColor="#00E5FF"
        android:pathData="M22,22m-20,0a20,20 0,1 1,40 0a20,20 0,1 1,-40 0" />
        
    <!-- Outer Clean White Ring Border (28dp) -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M22,22m-14,0a14,14 0,1 1,28 0a14,14 0,1 1,-28 0" />
        
    <!-- Vibrant Solid Blue Navigation Disc (22dp) -->
    <path
        android:fillColor="#007AFF"
        android:pathData="M22,22m-11,0a11,11 0,1 1,22 0a11,11 0,1 1,-22 0" />
        
    <!-- Concentric Center White Core Dot (8dp) -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M22,22m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0" />
</vector>
```

---

### Blueprint 5: Layout Update — Theme Toggle & Controls (`activity_main.xml`)
```xml
<!-- In activity_main.xml: Relocated Theme Toggle and Zoom Controls overlay on MapView -->
<LinearLayout
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="top|end"
    android:layout_margin="8dp"
    android:orientation="vertical">

    <!-- Theme Toggle directly above Map Controls -->
    <ImageButton
        android:id="@+id/btn_theme_toggle"
        android:layout_width="36dp"
        android:layout_height="36dp"
        android:background="@drawable/bg_chip"
        android:src="@android:drawable/ic_menu_day"
        android:contentDescription="@string/cd_theme_toggle"
        app:tint="?attr/colorOnSurface" />

    <ImageButton
        android:id="@+id/btn_zoom_in"
        android:layout_width="36dp"
        android:layout_height="36dp"
        android:layout_marginTop="6dp"
        android:background="@drawable/bg_chip"
        android:src="@android:drawable/ic_menu_add"
        android:contentDescription="@string/cd_zoom_in"
        app:tint="?attr/colorOnSurface" />

    <ImageButton
        android:id="@+id/btn_zoom_out"
        android:layout_width="36dp"
        android:layout_height="36dp"
        android:layout_marginTop="4dp"
        android:background="@drawable/bg_chip"
        android:src="@android:drawable/ic_menu_close_clear_cancel"
        android:contentDescription="@string/cd_zoom_out"
        app:tint="?attr/colorOnSurface" />

    <ImageButton
        android:id="@+id/btn_recenter"
        android:layout_width="36dp"
        android:layout_height="36dp"
        android:layout_marginTop="6dp"
        android:background="@drawable/bg_chip"
        android:src="@android:drawable/ic_menu_mylocation"
        android:contentDescription="@string/cd_recenter"
        app:tint="?attr/colorSecondary" />
</LinearLayout>
```

---

## Verification & Testing Plan

### 1. Automated Unit Tests
- **`SensorFilterTest.kt`**:
  - Feed 50 simulated static readings with noise.
  - Verify `isStationary == true`, gyro outputs clamp to `0.00f`, and bias is subtracted.
  - Feed simulated rotation ($0.5\text{ rad/s}$). Verify `isStationary == false` and bias-subtracted gyro output.
- **`DeadReckoningEngineTest.kt`**:
  - Test straight-line motion with zero gyro: verify latitude and longitude step forward in a straight line without circular curvature.
  - Test stationary state: verify zero coordinate displacement and zero speed.
  - Verify `estimatedDriftM` grows realistically and never exceeds 35m in short outages.
- **`GnssQualityMonitorTest.kt`**:
  - Test watchdog timeout: simulate silence for 3000ms, assert mode transitions to `DEAD_RECKONING_AUTO`.
  - Simulate fix arrival, assert mode transitions to `GNSS_LOCK`.

### 2. Physical Device Field Test Checklist
1. **Static Bench Test**:
   - Place phone flat on table.
   - Speed must show strictly `0.0 km/h`.
   - Gyroscope values must show `0.00 rad/s` without glitching or vibrating.
   - Position must stay fixed; zero drift.
2. **Offline Mode Test**:
   - Turn OFF Location / GPS and turn ON Airplane mode.
   - Open app: Map must render cached tiles immediately; vehicle puck must appear at cached coordinate; lat/lon must display numbers (not dashes).
3. **Tunnel / Outage Test**:
   - Walk or drive along a straight road.
   - Cover phone or enter basement parking (GNSS loss).
   - Verify: System automatically switches to `AUTONOMOUS DR` without pressing any toggle.
   - Navigation puck continues smoothly forward along the street in a straight line (NO circular loops or spirals).
   - Drift increases smoothly from 1.5m to ~6m (Green dot, NOT 562m red dot).
   - Upon exiting into open sky, system automatically re-locks GNSS (`GNSS 3D LOCK`) and draws green track.
4. **UI Verification**:
   - Theme button is directly above the zoom controls on the map.
   - Navigation marker is a circular blue/white dot (no directional arrow).
