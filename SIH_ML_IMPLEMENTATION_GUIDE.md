# SIH PS-26168 — Complete ML Integration Implementation Guide

> **Purpose**: A single, self-contained specification that any AI coding agent (Gemini, Claude, GPT-4, etc.)
> can follow from start to finish to build the complete ML-powered Dead Reckoning system on top of the
> existing prototype app. Every code block is copy-paste ready. Every file path is exact. No step is skipped.

---

## Project State: What Already Exists (DO NOT TOUCH)

### Repository
```
C:\Users\manas\OneDrive\Desktop\sihhhh\sih26_prototype1\
└── sih-dead-reckoning-lite\
    └── app\src\main\java\com\sih\deadreckoninglite\
        ├── MainActivity.kt                  ← Composition root (DO NOT restructure)
        ├── deadreckoning\
        │   ├── ConstantVelocityReckoner.kt  ← Rule-based fallback (WILL BE REPLACED as primary)
        │   └── TunnelSimulator.kt           ← DR mode controller (WILL BE UPGRADED)
        ├── location\
        │   └── GpsSample.kt                ← GPS data class + CSV schema
        ├── logging\
        │   └── SensorLogger.kt             ← CSV file writer
        ├── map\
        │   └── MapController.kt            ← osmdroid map (DO NOT restructure)
        ├── sensors\
        │   ├── ImuManager.kt               ← Accelerometer + Gyroscope capture (WILL BE UPGRADED)
        │   └── SensorSample.kt             ← IMU data class (WILL BE UPGRADED)
        ├── ui\
        │   ├── MainViewModel.kt            ← LiveData state store (WILL BE UPGRADED)
        │   ├── DriveLogActivity.kt
        │   ├── DriveLogAdapter.kt
        │   ├── PermissionActivity.kt
        │   ├── SplashActivity.kt
        │   ├── TelemetryOverlay.kt
        │   └── ThemeManager.kt
        └── util\
            ├── Constants.kt                ← All shared constants (WILL BE UPGRADED)
            └── TimeUtils.kt
```

### Current CSV Schema (produced by SensorLogger.kt)
```
timestamp_ns, ax, ay, az, gx, gy, gz, gnss_lat, gnss_lon, gnss_speed, gnss_accuracy
```
(11 columns total)

### Current Dead Reckoning Logic
`TunnelSimulator` fires a 1-Hz ticker. Each tick calls `ConstantVelocityReckoner.project(lastFix, elapsedSeconds)`
which computes: `distance = lastFix.speedMps x elapsedSeconds`, then does equirectangular
lat/lon projection. **This is the logic we are upgrading with an ML model as the primary speed source.**

### Build Config (app/build.gradle.kts)
- `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`
- JVM target: `17`
- Dependencies: osmdroid 6.1.18, play-services-location 21.1.0, coroutines 1.7.3, ViewModel 2.7.0

---

## Architecture Rule (MUST FOLLOW AT ALL TIMES)
`MainActivity.kt` is the **Composition Root**. It is the ONLY class that
holds references to and wires together all modules. No module calls another module directly.
This isolation rule must be maintained in every new file added.

---

## Phase 1 — Dataset Acquisition

### 1.1 Why You Cannot Use `git clone`
The IO-VNBD repository stores every CSV as a Git LFS pointer. Cloning gives you tiny
placeholder text files (approximately 135 bytes each), not real data.

### 1.2 Download Steps (browser only)
1. Open: `https://github.com/onyekpeu/IO-VNBD`
2. Click on: `Synchronised V and S datasets.zip`
3. Click the **Download** raw file button (top-right, download icon)
4. Wait for download to complete (file is approximately 200-400 MB)
5. Extract to: `C:\Users\manas\Desktop\sih_ml_training\data\`

### 1.3 Expected Directory Structure After Extraction
```
C:\Users\manas\Desktop\sih_ml_training\data\
└── Synchronised V and S datasets\
    ├── S-Dataset\           <- USE THESE (smartphone sensor files)
    │   ├── S-Drive-A1.csv
    │   ├── S-Drive-A2.csv
    │   ├── S-Drive-B1.csv
    │   └── ... (multiple drives)
    └── V-Dataset\           <- SKIP (vehicle CAN bus data, not needed)
```

### 1.4 Confirmed Real Column Schema of S-*.csv Files

| Column Name (exact) | Unit | Maps to Your App |
|---|---|---|
| `GPS Latitude` | degrees | `gnss_lat` |
| `GPS Longitude` | degrees | `gnss_lon` |
| `GPS Altitude` | m | (not used) |
| `GPS Speed` | **km/h** | `gnss_speed` (your app uses m/s, convert divide by 3.6) |
| `GPS Accuracy` | m | `gnss_accuracy` |
| `GPS Orientation` | degrees | `gnss_bearing` |
| `GPS Satellites In Range` | count | (not used) |
| `Time Since Start` | ms | (not used) |
| `Date` | timestamp | (not used) |
| `Accelerometer X/Y/Z` | m/s squared | `ax`, `ay`, `az` |
| `Gravity X/Y/Z` | m/s squared | **NEW** — subtract from Accel for linear accel |
| `Gyroscope Yaw/Pitch/Roll` | rad/s | `gx`, `gy`, `gz` |
| `Magnetic Field X/Y/Z` | uT | (optional future use) |
| `Orientation Yaw/Roll/Pitch` | degrees | (optional future use) |

> **Critical**: `linear_ax = Accelerometer X minus Gravity X` (same for Y and Z).
> This removes the 9.8 m/s squared gravity component that would otherwise dominate the model input.
> The dataset provides `Gravity X/Y/Z` separately, which makes this step trivial.

> **Critical**: `GPS Speed` is in **km/h** in the dataset. Your Android app reports in **m/s**.
> Always convert: `speed_mps = gps_speed_kmh / 3.6`

---

## Phase 2 — Python Training Environment Setup

### 2.1 Create the Training Directory
```
C:\Users\manas\Desktop\sih_ml_training\
├── data\
│   └── Synchronised V and S datasets\
│       └── S-Dataset\
│           └── S-*.csv  (all drive files here)
├── train_speed_model.py   <- create this (full script in Phase 3)
└── outputs\               <- auto-created by the script
    ├── speed_estimator.tflite
    ├── norm_stats.json
    └── drift_simulation.png
```

### 2.2 Install Python Dependencies
```bash
pip install tensorflow pandas numpy matplotlib scikit-learn
```

> **Version note**: TensorFlow 2.15+ required for the `unroll=True` LSTM parameter
> needed for TFLite static shape export. Upgrade if needed:
> `pip install --upgrade tensorflow`

---

## Phase 3 — Complete Training Script

Save this file verbatim as `C:\Users\manas\Desktop\sih_ml_training\train_speed_model.py`.
Run with: `python train_speed_model.py`

```python
"""
SIH PS-26168 — IMU-to-Velocity CNN+LSTM Model
Dataset:  IO-VNBD Smartphone S-* drives (Synchronised dataset)
Outputs:  speed_estimator.tflite  — Android TFLite model
          norm_stats.json         — normalization stats for Android app
          drift_simulation.png    — SIH submission evidence plot
"""

import os
import json
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models
from sklearn.model_selection import train_test_split
import matplotlib
matplotlib.use("Agg")   # non-interactive backend for headless servers
import matplotlib.pyplot as plt

# ─────────────────────────────────────────────────────────────
#  CONFIGURATION — EDIT DATA_DIR TO YOUR ACTUAL PATH
# ─────────────────────────────────────────────────────────────
DATA_DIR = r"C:\Users\manas\Desktop\sih_ml_training\data\Synchronised V and S datasets\S-Dataset"
OUT_DIR  = r"C:\Users\manas\Desktop\sih_ml_training\outputs"

WINDOW_SIZE  = 20    # 2 seconds at 10Hz
STRIDE       = 10    # 50% overlap -> more training windows
GPS_ACC_THRESH = 20.0  # drop rows where GPS accuracy > 20m (unreliable labels)

os.makedirs(OUT_DIR, exist_ok=True)

# ─────────────────────────────────────────────────────────────
#  FEATURES & LABEL (exact column names from IO-VNBD S-*.csv)
# ─────────────────────────────────────────────────────────────
FEATURE_COLS = [
    "linear_ax", "linear_ay", "linear_az",   # computed = Accel - Gravity
    "Gyroscope Yaw", "Gyroscope Pitch", "Gyroscope Roll",
]
LABEL_COL = "GPS Speed"   # unit: km/h in the dataset

# ─────────────────────────────────────────────────────────────
#  STEP 1: LOAD & PREPROCESS
# ─────────────────────────────────────────────────────────────
def load_drive(csv_path: str) -> pd.DataFrame:
    """Load one S-*.csv, compute derived columns, forward-fill GPS labels."""
    df = pd.read_csv(csv_path)

    # Gravity-compensated linear acceleration
    df["linear_ax"] = df["Accelerometer X"] - df["Gravity X"]
    df["linear_ay"] = df["Accelerometer Y"] - df["Gravity Y"]
    df["linear_az"] = df["Accelerometer Z"] - df["Gravity Z"]

    # Forward-fill GPS speed across the slower 1Hz updates
    # IMU runs at 10Hz; GPS Speed only updates at 1Hz, leaving NaN rows.
    # Forward-fill propagates the last known GPS speed to all IMU rows between updates.
    df["GPS Speed"] = df["GPS Speed"].ffill()

    # Drop rows with poor GPS accuracy (unreliable training label)
    if "GPS Accuracy" in df.columns:
        df = df[df["GPS Accuracy"] <= GPS_ACC_THRESH]

    # Drop any remaining NaN rows
    df = df.dropna(subset=FEATURE_COLS + [LABEL_COL])

    return df

def make_windows(df: pd.DataFrame):
    """Sliding window extraction -> returns X [N, W, F] and y [N]."""
    X, y = [], []
    arr  = df[FEATURE_COLS].values.astype(np.float32)
    lbl  = df[LABEL_COL].values.astype(np.float32)
    for start in range(0, len(df) - WINDOW_SIZE, STRIDE):
        X.append(arr[start : start + WINDOW_SIZE])
        y.append(np.mean(lbl[start : start + WINDOW_SIZE]))   # avg GPS speed over window
    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32)

# Enumerate and load all S- drives
all_files = sorted([
    os.path.join(DATA_DIR, f)
    for f in os.listdir(DATA_DIR)
    if f.startswith("S-") and f.endswith(".csv")
])
if not all_files:
    raise FileNotFoundError(
        f"No S-*.csv files found in:\n  {DATA_DIR}\n"
        "Did you download the Synchronised dataset via browser and extract it there?"
    )

print(f"Found {len(all_files)} drives:")
all_X, all_y = [], []
for fpath in all_files:
    try:
        df = load_drive(fpath)
        Xi, yi = make_windows(df)
        if len(Xi) == 0:
            print(f"  SKIP {os.path.basename(fpath)} — zero windows after filtering")
            continue
        all_X.append(Xi)
        all_y.append(yi)
        print(f"  {os.path.basename(fpath)}: {len(Xi)} windows, "
              f"speed range [{yi.min():.1f}, {yi.max():.1f}] km/h")
    except Exception as e:
        print(f"  ERROR {os.path.basename(fpath)}: {e}")

X = np.concatenate(all_X)
y = np.concatenate(all_y)
print(f"\nTotal dataset: {X.shape[0]} windows, shape {X.shape}, "
      f"speed [{y.min():.1f}, {y.max():.1f}] km/h")

# ─────────────────────────────────────────────────────────────
#  STEP 2: TRAIN / VAL / TEST SPLIT
# ─────────────────────────────────────────────────────────────
X_train, X_temp, y_train, y_temp = train_test_split(
    X, y, test_size=0.3, random_state=42, shuffle=True
)
X_val, X_test, y_val, y_test = train_test_split(
    X_temp, y_temp, test_size=0.5, random_state=42
)
print(f"Split -> train: {len(X_train)}, val: {len(X_val)}, test: {len(X_test)}")

# ─────────────────────────────────────────────────────────────
#  STEP 3: NORMALIZE (fit on train only — no data leakage)
# ─────────────────────────────────────────────────────────────
mean = X_train.mean(axis=(0, 1))   # shape [NUM_FEATURES]
std  = X_train.std(axis=(0, 1)) + 1e-8   # epsilon prevents div-by-zero

X_train = (X_train - mean) / std
X_val   = (X_val   - mean) / std
X_test  = (X_test  - mean) / std

# Save normalization stats — the Android app will load these from norm_stats.json
norm_stats = {
    "mean":        mean.tolist(),
    "std":         std.tolist(),
    "features":    FEATURE_COLS,
    "window_size": WINDOW_SIZE
}
with open(os.path.join(OUT_DIR, "norm_stats.json"), "w") as f:
    json.dump(norm_stats, f, indent=2)
print(f"Normalization stats saved -> {OUT_DIR}\\norm_stats.json")

# ─────────────────────────────────────────────────────────────
#  STEP 4: DEFINE CNN + LSTM MODEL
# ─────────────────────────────────────────────────────────────
# unroll=True is REQUIRED for TFLite export (static computation graph)

NUM_FEATURES = len(FEATURE_COLS)

model = models.Sequential([
    layers.Input(shape=(WINDOW_SIZE, NUM_FEATURES), name="imu_window"),
    layers.Conv1D(32, kernel_size=3, activation="relu", padding="same", name="conv1"),
    layers.Conv1D(64, kernel_size=3, activation="relu", padding="same", name="conv2"),
    layers.BatchNormalization(name="bn1"),
    layers.LSTM(128, return_sequences=True, unroll=True, name="lstm1"),
    layers.Dropout(0.2, name="dropout1"),
    layers.LSTM(64, return_sequences=False, unroll=True, name="lstm2"),
    layers.Dropout(0.2, name="dropout2"),
    layers.Dense(32, activation="relu", name="dense1"),
    layers.Dense(1, name="speed_output"),   # predicted speed in km/h
])

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
    loss="huber",    # robust to GPS speed outliers
    metrics=["mae"]
)
model.summary()

# ─────────────────────────────────────────────────────────────
#  STEP 5: TRAIN
# ─────────────────────────────────────────────────────────────
callbacks = [
    tf.keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=10, restore_best_weights=True, verbose=1
    ),
    tf.keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss", factor=0.5, patience=5, min_lr=1e-5, verbose=1
    ),
    tf.keras.callbacks.ModelCheckpoint(
        filepath=os.path.join(OUT_DIR, "best_model.keras"),
        save_best_only=True, monitor="val_loss", verbose=0
    )
]

print("\nTraining...")
history = model.fit(
    X_train, y_train,
    validation_data=(X_val, y_val),
    epochs=100,
    batch_size=64,
    callbacks=callbacks,
    verbose=1
)

# ─────────────────────────────────────────────────────────────
#  STEP 6: EVALUATE & GENERATE SUBMISSION EVIDENCE
# ─────────────────────────────────────────────────────────────
test_loss, test_mae = model.evaluate(X_test, y_test, verbose=0)
print(f"\n{'='*50}")
print(f"TEST MAE:  {test_mae:.2f} km/h  (target: < 3.5 km/h)")
print(f"TEST LOSS: {test_loss:.4f}")
print(f"{'='*50}\n")

# Drift simulation
y_pred = model.predict(X_test, verbose=0).flatten()
y_pred_deadzoned = np.where(y_pred < 1.26, 0.0, y_pred)

dt = STRIDE / 10.0   # seconds per stride at 10Hz
dist_true = np.cumsum((y_test           / 3.6) * dt)
dist_pred = np.cumsum((y_pred_deadzoned  / 3.6) * dt)
drift_m   = np.abs(dist_true - dist_pred)
time_axis = np.arange(len(y_pred)) * dt

print(f"Drift simulation over {time_axis[-1]:.0f} s of test data:")
print(f"  Final position error: {drift_m[-1]:.1f} m")
print(f"  Max error:            {drift_m.max():.1f} m")
print(f"  Mean error:           {drift_m.mean():.1f} m")

fig, axes = plt.subplots(1, 2, figsize=(14, 5))
fig.suptitle("SIH PS-26168 — ML Dead Reckoning Evaluation", fontsize=14, fontweight="bold")

n_plot = min(300, len(y_test))
axes[0].plot(y_test[:n_plot],  label="GPS Speed (ground truth)", alpha=0.85, linewidth=1.5)
axes[0].plot(y_pred[:n_plot],  label="ML Predicted Speed",       alpha=0.85, linewidth=1.5, linestyle="--")
axes[0].set_xlabel("Window Index"); axes[0].set_ylabel("Speed (km/h)")
axes[0].set_title(f"Speed Prediction  |  MAE = {test_mae:.2f} km/h")
axes[0].legend(); axes[0].grid(True, alpha=0.3)

axes[1].plot(time_axis, dist_true, label="True Distance (GPS integrated)",   linewidth=1.5)
axes[1].plot(time_axis, dist_pred, label="ML Dead-Reckoned Distance",         linewidth=1.5, linestyle="--")
axes[1].fill_between(time_axis, dist_true, dist_pred, alpha=0.15,
                     label=f"Error (final={drift_m[-1]:.1f} m)")
axes[1].set_xlabel("Time (s)"); axes[1].set_ylabel("Distance (m)")
axes[1].set_title(f"Drift Simulation  |  Final Error = {drift_m[-1]:.1f} m")
axes[1].legend(); axes[1].grid(True, alpha=0.3)

plt.tight_layout()
plot_path = os.path.join(OUT_DIR, "drift_simulation.png")
plt.savefig(plot_path, dpi=150, bbox_inches="tight")
print(f"\nEvidence plot saved -> {plot_path}")

# ─────────────────────────────────────────────────────────────
#  STEP 7: EXPORT TO TFLITE
# ─────────────────────────────────────────────────────────────
print("\nExporting to TFLite...")
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

tflite_path = os.path.join(OUT_DIR, "speed_estimator.tflite")
with open(tflite_path, "wb") as f:
    f.write(tflite_model)

print(f"TFLite model saved -> {tflite_path}")
print(f"TFLite model size:   {len(tflite_model) / 1024:.1f} KB")

print("\n" + "="*60)
print("DONE. Next steps:")
print(f"  1. Copy speed_estimator.tflite -> app/src/main/assets/")
print(f"  2. Copy norm_stats.json        -> app/src/main/assets/")
print(f"  3. Attach drift_simulation.png to your SIH proposal")
print(f"  4. Report: Test MAE = {test_mae:.2f} km/h")
print("="*60)
```

### Expected Training Output
```
Test MAE:  1.5–3.5 km/h     <- target is < 3.5 km/h
Final position error: 20–80 m over 120 s test window
TFLite model size:   200–600 KB
```

---

## Phase 4 — Android App Integration

All files below are in the project at:
`C:\Users\manas\OneDrive\Desktop\sihhhh\sih26_prototype1\sih-dead-reckoning-lite\`

### 4.1 Add TFLite Dependency

**File to modify**: `app/build.gradle.kts`

Add inside the `dependencies { }` block, after the existing dependencies:

```kotlin
    // TFLite for ML speed estimation
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
```

Also add inside the `android { }` block (prevents asset stripping by AAPT):
```kotlin
    aaptOptions {
        noCompress("tflite")
    }
```

### 4.2 Copy Model Assets

After training completes, copy the two output files into:
```
sih-dead-reckoning-lite\app\src\main\assets\speed_estimator.tflite
sih-dead-reckoning-lite\app\src\main\assets\norm_stats.json
```

Create the `assets` directory first if it doesn't exist.

### 4.3 Upgrade SensorSample.kt

**File**: `app/src/main/java/com/sih/deadreckoninglite/sensors/SensorSample.kt`

**What changes**: Add `gravX`, `gravY`, `gravZ` gravity-vector fields plus
computed `linearAx/Y/Z` properties used by `MlSpeedEstimator`.

Replace the entire file with:

```kotlin
package com.sih.deadreckoninglite.sensors

/**
 * Merged IMU sample: accelerometer + gyroscope + gravity sensor readings.
 *
 * ## CSV Schema (PRD §10.1 — extended for ML training)
 * `ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z`
 *
 * ## Linear Acceleration (for ML model input)
 * `linear_ax = ax - gravX` (same for Y, Z)
 * Removes the 9.8 m/s² gravity component that would otherwise saturate
 * the CNN+LSTM model input.
 *
 * ## Units
 *   - ax, ay, az          — raw accelerometer, m/s² (includes gravity)
 *   - gx, gy, gz          — gyroscope, rad/s
 *   - gravX, gravY, gravZ — gravity vector from TYPE_GRAVITY sensor, m/s²
 *   - timestampNs         — nanoseconds, elapsedRealtimeNanos clock domain
 *
 * ## Thread Safety
 * Immutable data class — safe to share across threads without synchronization.
 *
 * ## Downstream Consumers
 * - SensorLogger: uses toCsvValues() and CSV_HEADER
 * - MlSpeedEstimator: uses linearAx, linearAy, linearAz, gx, gy, gz
 * - MainViewModel/TelemetryOverlay: uses field values for UI display
 * - MainActivity: routes instances to all of the above
 */
data class SensorSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    // NEW: gravity vector from Android TYPE_GRAVITY sensor
    // Default: 0f for X/Y, 9.80665f for Z (standard gravity fallback on devices lacking TYPE_GRAVITY)
    val gravX: Float = 0f,
    val gravY: Float = 0f,
    val gravZ: Float = 9.80665f
) {
    /** Linear (motion) acceleration with gravity removed. Required by MlSpeedEstimator. */
    val linearAx: Float get() = ax - gravX
    val linearAy: Float get() = ay - gravY
    val linearAz: Float get() = az - gravZ

    fun toLogString(): String =
        "IMU[ts=$timestampNs] A(${f(ax)}, ${f(ay)}, ${f(az)}) " +
        "G(${f(gx)}, ${f(gy)}, ${f(gz)}) " +
        "Grav(${f(gravX)}, ${f(gravY)}, ${f(gravZ)}) " +
        "Lin(${f(linearAx)}, ${f(linearAy)}, ${f(linearAz)})"

    /**
     * CSV values for the unified schema.
     * Column order: ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z
     * (9 IMU columns total — was 6 in the prototype)
     */
    fun toCsvValues(): String =
        "${f6(ax)},${f6(ay)},${f6(az)}," +
        "${f6(gx)},${f6(gy)},${f6(gz)}," +
        "${f6(gravX)},${f6(gravY)},${f6(gravZ)}"

    companion object {
        /** CSV header for IMU columns — updated to include gravity. */
        const val CSV_HEADER = "ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z"

        private fun f(v: Float): String  = "%.3f".format(v)
        private fun f6(v: Float): String = "%.6f".format(v)
    }
}
```

### 4.4 Upgrade ImuManager.kt

**File**: `app/src/main/java/com/sih/deadreckoninglite/sensors/ImuManager.kt`

**What changes**: Register `TYPE_GRAVITY` sensor alongside accelerometer and gyroscope.
Cache gravity readings and include them in every emitted `SensorSample`.

Replace the entire file with:

```kotlin
package com.sih.deadreckoninglite.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.sih.deadreckoninglite.util.Constants

/**
 * Manages accelerometer + gyroscope + gravity sensor registration.
 *
 * ## Design Contract (PRD §4.2 point 2, §9)
 * - ONLY file that touches Android SensorManager.
 * - Emits merged SensorSample objects via callback.
 * - Isolation: swap internals without touching any other file.
 *
 * ## What changed vs. prototype
 * Now also captures TYPE_GRAVITY sensor, which provides the gravity vector
 * needed to compute linear (motion) acceleration:
 *   linear_a = raw_accelerometer - gravity
 * This is required as input to the CNN+LSTM speed estimator (MlSpeedEstimator).
 *
 * ## Merging Strategy
 * Emission trigger: accelerometer tick (~50 Hz). Gyroscope and gravity readings
 * are cached and picked up on the next accelerometer event.
 *
 * ## Threading
 * Sensor callbacks arrive on a SensorManager-internal thread (NOT the main thread).
 * Snapshot references are replaced atomically (JVM object-reference writes are atomic).
 */
class ImuManager(context: Context) {

    companion object {
        private const val TAG = "ImuManager"
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)   // NEW

    // Immutable snapshots — JVM guarantees atomic reference replacement
    private data class AxisSnapshot(val x: Float, val y: Float, val z: Float)

    @Volatile private var latestAccel: AxisSnapshot? = null
    @Volatile private var latestGyro: AxisSnapshot? = null
    @Volatile private var latestGravity: AxisSnapshot? = null   // NEW

    private var callback: ((SensorSample) -> Unit)? = null

    @Volatile private var isRunning: Boolean = false

    val hasAccelerometer: Boolean get() = accelSensor != null
    val hasGyroscope: Boolean get() = gyroSensor != null
    val hasGravitySensor: Boolean get() = gravitySensor != null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAccel = AxisSnapshot(event.values[0], event.values[1], event.values[2])
                    emitIfReady(event.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyro = AxisSnapshot(event.values[0], event.values[1], event.values[2])
                }
                Sensor.TYPE_GRAVITY -> {
                    latestGravity = AxisSnapshot(event.values[0], event.values[1], event.values[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "Accuracy changed: sensor=${sensor?.name}, accuracy=$accuracy")
        }
    }

    /**
     * Emit a merged SensorSample when accelerometer and gyroscope have reported.
     * Gravity snapshot included if available (falls back to standard gravity on Z if not).
     */
    private fun emitIfReady(timestampNs: Long) {
        val accel = latestAccel ?: return
        val gyro  = latestGyro  ?: return
        val grav  = latestGravity   // nullable — some devices lack TYPE_GRAVITY

        val sample = SensorSample(
            timestampNs = timestampNs,
            ax = accel.x, ay = accel.y, az = accel.z,
            gx = gyro.x,  gy = gyro.y,  gz = gyro.z,
            gravX = grav?.x ?: 0f,
            gravY = grav?.y ?: 0f,
            gravZ = grav?.z ?: 9.80665f   // standard gravity fallback
        )

        try {
            callback?.invoke(sample)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sensor callback — swallowed to protect SensorManager thread", e)
        }
    }

    /**
     * Begin continuous sensor sampling.
     * Double-start guard: if already running, stops cleanly before re-registering.
     *
     * @param callback Receives merged SensorSample on each accelerometer tick (~50 Hz).
     *                 Called on the sensor-delivery thread (NOT the main thread).
     *                 MainActivity is responsible for routing to MlSpeedEstimator / SensorLogger.
     */
    fun start(callback: (SensorSample) -> Unit) {
        if (isRunning) {
            Log.w(TAG, "start() called while already running — stopping previous session first")
            stop()
        }

        this.callback = callback
        latestAccel   = null
        latestGyro    = null
        latestGravity = null

        accelSensor?.let {
            sensorManager.registerListener(sensorListener, it, Constants.IMU_SENSOR_DELAY)
            Log.i(TAG, "Accelerometer registered: ${it.name}")
        } ?: Log.w(TAG, "No accelerometer found")

        gyroSensor?.let {
            sensorManager.registerListener(sensorListener, it, Constants.IMU_SENSOR_DELAY)
            Log.i(TAG, "Gyroscope registered: ${it.name}")
        } ?: Log.w(TAG, "No gyroscope found")

        // NEW: register gravity sensor
        gravitySensor?.let {
            sensorManager.registerListener(sensorListener, it, Constants.IMU_SENSOR_DELAY)
            Log.i(TAG, "Gravity sensor registered: ${it.name}")
        } ?: Log.w(TAG, "No TYPE_GRAVITY sensor — using fallback gravity vector (9.81 on Z)")

        isRunning = true
        Log.i(TAG, "Started — accel=$hasAccelerometer, gyro=$hasGyroscope, gravity=$hasGravitySensor")
    }

    /**
     * Stop all sensor listeners and clear the callback reference.
     * Safe to call multiple times or before start() has been called.
     */
    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(sensorListener)
        callback = null
        isRunning = false
        Log.i(TAG, "Stopped")
    }
}
```

### 4.5 Update SensorLogger.kt Column Constants

**File**: `app/src/main/java/com/sih/deadreckoninglite/logging/SensorLogger.kt`

The new CSV has 14 columns: `timestamp_ns` + 9 IMU columns + 4 GPS columns.

Find and replace these two constants in `SensorLogger.kt`:

```kotlin
// FIND AND REPLACE — IMU_EMPTY_COLS (was 6 commas for 6 IMU fields, now 9 commas for 9 IMU fields)
// OLD:
private const val IMU_EMPTY_COLS = ",,,,,,"
// NEW:
private const val IMU_EMPTY_COLS = ",,,,,,,,,"   // 9 commas for 9 IMU fields (ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z)

// GNSS_EMPTY_COLS is unchanged (still 4 GPS columns):
private const val GNSS_EMPTY_COLS = ",,,,"
```

Also update the docstring trace comment nearby to reflect the new schema:
```
// New schema: ts + 9 IMU + 4 GPS = 14 columns
// IMU row:  ts,ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z,,,,  (14 cols)
// GPS row:  ts,,,,,,,,,,gnss_lat,gnss_lon,gnss_speed,gnss_accuracy  (14 cols)
```

### 4.6 Create MlSpeedEstimator.kt (NEW FILE)

**Create directory**: `app/src/main/java/com/sih/deadreckoninglite/ml/`
**Create file**: `app/src/main/java/com/sih/deadreckoninglite/ml/MlSpeedEstimator.kt`

```kotlin
package com.sih.deadreckoninglite.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * CNN+LSTM speed estimator running on TFLite.
 *
 * ## Isolation Contract (Architecture Rule)
 * This class has ZERO knowledge of:
 * - Android UI / Views / ViewModel
 * - MapController, TunnelSimulator, SensorLogger
 * - GPS / SensorManager
 * It is a pure function: IMU window -> speed in m/s.
 * MainActivity is the ONLY class that creates and holds a reference to this.
 *
 * ## Input
 * Sliding window of WINDOW_SIZE=20 IMU frames x NUM_FEATURES=6 channels:
 *   [linear_ax, linear_ay, linear_az, gx, gy, gz]
 * Each channel is Z-score normalized using stats from norm_stats.json.
 * SensorSample.linearAx/Y/Z provides the gravity-compensated acceleration.
 *
 * ## Output
 * Predicted vehicle speed in m/s (predicted as km/h internally, then converted).
 *
 * ## Usage by MainActivity
 * 1. Create: mlEstimator = MlSpeedEstimator(this)
 * 2. Wire:   tunnelSimulator.mlEstimator = mlEstimator
 * 3. Feed:   mlEstimator.addSample(...) on every ImuManager callback
 * 4. Destroy: mlEstimator.close() in onDestroy()
 *
 * ## Assets required (in app/src/main/assets/)
 * - speed_estimator.tflite  (trained CNN+LSTM model)
 * - norm_stats.json         (mean/std from Python training script)
 */
class MlSpeedEstimator(context: Context) {

    companion object {
        private const val TAG            = "MlSpeedEstimator"
        private const val WINDOW_SIZE    = 20       // must match Python WINDOW_SIZE
        private const val NUM_FEATURES   = 6        // linear_ax/ay/az + gx/gy/gz
        private const val MODEL_FILE     = "speed_estimator.tflite"
        private const val NORM_FILE      = "norm_stats.json"
        // Stationary deadband: predictions below 1.26 km/h (0.35 m/s) treated as zero
        private const val SPEED_DEADBAND_KMH = 1.26f
        // Downsample: ImuManager emits at ~50 Hz, model trained at 10 Hz
        private const val DOWNSAMPLE_FACTOR = 5   // feed every 5th sample to model
    }

    private val interpreter: Interpreter
    private val mean: FloatArray
    private val std:  FloatArray

    // Rolling window of WINDOW_SIZE normalized IMU frames
    private val window = ArrayDeque<FloatArray>(WINDOW_SIZE)

    // Skip counter for downsampling
    private var skipCounter = 0

    // Latest prediction (cached between model calls)
    @Volatile private var lastPrediction: Float? = null

    init {
        val modelBuffer = loadModelFile(context)
        val options = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(modelBuffer, options)
        Log.i(TAG, "TFLite interpreter created from $MODEL_FILE")

        val normJson = context.assets.open(NORM_FILE).bufferedReader().readText()
        val json = JSONObject(normJson)
        val meanArr = json.getJSONArray("mean")
        val stdArr  = json.getJSONArray("std")
        mean = FloatArray(NUM_FEATURES) { meanArr.getDouble(it).toFloat() }
        std  = FloatArray(NUM_FEATURES) { stdArr.getDouble(it).toFloat() }

        Log.i(TAG, "Normalization stats loaded from $NORM_FILE")
        Log.d(TAG, "  means: ${mean.toList()}")
        Log.d(TAG, "  stds:  ${std.toList()}")
    }

    /**
     * Feed one IMU sample into the rolling window.
     *
     * Call this on EVERY sensor tick from ImuManager (~50 Hz).
     * Internal downsampling reduces to ~10 Hz matching the training rate.
     *
     * @param linearAx  SensorSample.linearAx  (ax - gravX), m/s²
     * @param linearAy  SensorSample.linearAy  (ay - gravY), m/s²
     * @param linearAz  SensorSample.linearAz  (az - gravZ), m/s²
     * @param gx        SensorSample.gx, rad/s
     * @param gy        SensorSample.gy, rad/s
     * @param gz        SensorSample.gz, rad/s
     */
    fun addSample(
        linearAx: Float, linearAy: Float, linearAz: Float,
        gx: Float, gy: Float, gz: Float
    ) {
        skipCounter++
        if (skipCounter < DOWNSAMPLE_FACTOR) return
        skipCounter = 0

        // Z-score normalize each feature channel
        val raw = floatArrayOf(linearAx, linearAy, linearAz, gx, gy, gz)
        val normalized = FloatArray(NUM_FEATURES) { i -> (raw[i] - mean[i]) / std[i] }

        // Maintain fixed-size sliding window
        if (window.size >= WINDOW_SIZE) window.removeFirst()
        window.addLast(normalized)

        // Run inference once window is full
        if (window.size == WINDOW_SIZE) {
            lastPrediction = runInference()
        }
    }

    /**
     * Returns the latest ML-predicted speed in m/s.
     * Returns null if not enough samples have been fed yet
     * (less than WINDOW_SIZE x DOWNSAMPLE_FACTOR = 100 IMU ticks = ~2 seconds).
     */
    fun predictSpeedMps(): Float? {
        val pred = lastPrediction ?: return null
        val effectiveKmh = if (pred < SPEED_DEADBAND_KMH) 0.0f else pred
        return effectiveKmh / 3.6f   // km/h -> m/s
    }

    /**
     * Whether the model has received enough samples to produce a prediction.
     * Call this in TunnelSimulator to decide ML vs. constant-velocity fallback.
     */
    fun isReady(): Boolean = lastPrediction != null

    /**
     * Reset the sliding window. Call when starting a new drive session or
     * when activating tunnel mode after a long pause.
     */
    fun reset() {
        window.clear()
        lastPrediction = null
        skipCounter = 0
    }

    /**
     * Release TFLite resources. Call from Activity.onDestroy().
     */
    fun close() {
        interpreter.close()
        Log.i(TAG, "Closed")
    }

    // ── Private ────────────────────────────────────────────────────────────

    private fun runInference(): Float {
        // Input shape: [1, WINDOW_SIZE, NUM_FEATURES]
        val input  = Array(1) { Array(WINDOW_SIZE) { idx -> window[idx].copyOf() } }
        val output = Array(1) { FloatArray(1) }
        return try {
            interpreter.run(input, output)
            // Clamp to physically reasonable range: 0–200 km/h
            output[0][0].coerceIn(0f, 200f)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed", e)
            0f
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILE)
        val stream  = FileInputStream(assetFd.fileDescriptor)
        return stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }
}
```

### 4.7 Upgrade TunnelSimulator.kt

**File**: `app/src/main/java/com/sih/deadreckoninglite/deadreckoning/TunnelSimulator.kt`

**What changes**: Accept `MlSpeedEstimator` reference (injected by MainActivity).
Use ML-predicted speed as primary source; fall back to constant-velocity when ML not ready.
Position is now integrated step-by-step (ML speed x delta-t) instead of projecting from time-zero.

Replace the entire file with:

```kotlin
package com.sih.deadreckoninglite.deadreckoning

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.ml.MlSpeedEstimator
import com.sih.deadreckoninglite.util.Constants

/**
 * Simulated-tunnel mode controller — ML-upgraded version.
 *
 * ## What changed vs. prototype
 * Speed source priority:
 *   1. MlSpeedEstimator (primary) — when mlEstimator.isReady() == true
 *   2. ConstantVelocityReckoner (fallback) — during ML warm-up (~2 s after activation)
 *
 * Heading is always from last real GPS fix (bearingDeg). Gyro-based heading is future work.
 *
 * Position integration changed: instead of projecting from time-zero (which assumed
 * constant speed from the last GPS fix), we now integrate step-by-step:
 *   new_pos = prev_pos + speed x delta_t_in_direction_of_bearing
 * This allows ML speed to vary naturally over time.
 *
 * ## Architecture Rule (unchanged)
 * TunnelSimulator has NO knowledge of MapController, MainViewModel, or any UI component.
 * All output goes through the onProjectedPosition callback -> MainActivity routes it.
 *
 * ## Dependency Injection
 * mlEstimator is SET by MainActivity before setActive(true) is called.
 * TunnelSimulator never creates or destroys MlSpeedEstimator.
 */
class TunnelSimulator {

    companion object {
        private const val TAG = "TunnelSimulator"
    }

    private val reckoner = ConstantVelocityReckoner()   // kept as CV fallback
    private val handler  = Handler(Looper.getMainLooper())

    // ── State ──────────────────────────────────────────────────────────────

    var isActive: Boolean = false
        private set

    private var lastRealFix: GpsSample? = null
    private var tickerRunning: Boolean  = false

    // ML mode: running integrated position (updated step-by-step each tick)
    private var reckonedLat: Double  = 0.0
    private var reckonedLon: Double  = 0.0
    private var lastTickTimeMs: Long = 0L

    // ── External dependencies (set by MainActivity before setActive) ────────

    /**
     * ML speed estimator. Set by MainActivity (composition root).
     * If null, falls back to constant-velocity reckoning only.
     */
    var mlEstimator: MlSpeedEstimator? = null

    /**
     * Output callback — invoked each 1-Hz tick with projected (lat, lon).
     * Set by MainActivity; routes to MapController.addToReckonedPath() and MainViewModel.
     */
    var onProjectedPosition: ((lat: Double, lon: Double) -> Unit)? = null

    // ── Public API ──────────────────────────────────────────────────────────

    /** Feed a real GPS sample. Always called by MainActivity, regardless of isActive. */
    fun onRealGpsSample(sample: GpsSample) {
        lastRealFix = sample
    }

    /**
     * Activate or deactivate dead-reckoning mode.
     * setActive(true): starts 1-Hz ticker, resets ML window, anchors reckoned position to last GPS fix.
     * setActive(false): stops ticker immediately.
     */
    fun setActive(active: Boolean) {
        if (active) {
            if (isActive && tickerRunning) return
            isActive = true
            lastTickTimeMs = SystemClock.elapsedRealtime()

            // Anchor reckoned position to last real GPS fix
            lastRealFix?.let {
                reckonedLat = it.latDeg
                reckonedLon = it.lonDeg
            }

            mlEstimator?.reset()
            startTicker()
            Log.i(TAG, "Activated DR mode — ML ready=${mlEstimator?.isReady()}")
        } else {
            isActive = false
            stopTicker()
            Log.i(TAG, "Deactivated DR mode")
        }
    }

    // ── Internal ticker ─────────────────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isActive) { tickerRunning = false; return }

            val fix = lastRealFix
            if (fix != null) {
                val nowMs     = SystemClock.elapsedRealtime()
                val dtSeconds = (nowMs - lastTickTimeMs) / 1000.0
                lastTickTimeMs = nowMs

                // ── Select speed source ──────────────────────────────────
                val ml = mlEstimator
                val speedMps: Double = if (ml != null && ml.isReady()) {
                    val s = ml.predictSpeedMps()?.toDouble() ?: fix.speedMps.toDouble()
                    Log.v(TAG, "ML speed: ${"%.2f".format(s)} m/s")
                    s
                } else {
                    // Constant-velocity fallback using last GPS-reported speed
                    val s = fix.speedMps.toDouble()
                    Log.v(TAG, "Fallback CV speed: ${"%.2f".format(s)} m/s")
                    s
                }

                // ── Integrate one step ───────────────────────────────────
                val (newLat, newLon) = stepReckoning(
                    lat        = reckonedLat,
                    lon        = reckonedLon,
                    speedMps   = speedMps,
                    bearingDeg = fix.bearingDeg.toDouble(),
                    dtSeconds  = dtSeconds
                )
                reckonedLat = newLat
                reckonedLon = newLon

                onProjectedPosition?.invoke(reckonedLat, reckonedLon)
            }

            if (isActive) handler.postDelayed(this, Constants.TUNNEL_SIM_TICK_MS)
            else tickerRunning = false
        }
    }

    /**
     * Single equirectangular dead-reckoning step.
     * Stationary deadband: speedMps < 0.35 m/s is treated as zero (no position change).
     */
    private fun stepReckoning(
        lat: Double, lon: Double,
        speedMps: Double, bearingDeg: Double,
        dtSeconds: Double
    ): Pair<Double, Double> {
        val effectiveSpeed = if (speedMps < 0.35) 0.0 else speedMps
        if (effectiveSpeed == 0.0 || dtSeconds <= 0.0) return Pair(lat, lon)

        val distM      = effectiveSpeed * dtSeconds
        val bearingRad = Math.toRadians(bearingDeg)
        val latRad     = Math.toRadians(lat)
        val R          = Constants.EARTH_RADIUS_M

        val deltaLat = (distM * Math.cos(bearingRad)) / R
        val deltaLon = (distM * Math.sin(bearingRad)) / (R * Math.cos(latRad))

        return Pair(
            lat + Math.toDegrees(deltaLat),
            lon + Math.toDegrees(deltaLon)
        )
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.post(tickRunnable)
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(tickRunnable)
    }
}
```

### 4.8 Update MainViewModel.kt

**File**: `app/src/main/java/com/sih/deadreckoninglite/ui/MainViewModel.kt`

Replace the entire file with:

```kotlin
package com.sih.deadreckoninglite.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.sensors.SensorSample

/**
 * Holds live UI state for the main dashboard.
 *
 * ## Architecture Rule
 * MainActivity is the ONLY writer via the set*/publish* methods.
 *
 * ## Fields (new in ML version)
 * - mlSpeedMps: current ML-predicted speed (null = model not ready yet)
 * - mlReady: whether MlSpeedEstimator has accumulated enough samples
 */
class MainViewModel : ViewModel() {

    enum class Mode { GNSS, DEAD_RECKONING }

    private val _mode        = MutableLiveData(Mode.GNSS)
    private val _sample      = MutableLiveData<SensorSample>()
    private val _gpsSample   = MutableLiveData<GpsSample>()
    private val _drift       = MutableLiveData(0f)
    private val _csvActive   = MutableLiveData(false)
    private val _mlSpeedMps  = MutableLiveData<Float?>(null)   // NEW
    private val _mlReady     = MutableLiveData(false)           // NEW

    val currentMode:      LiveData<Mode>         = _mode
    val latestSample:     LiveData<SensorSample> = _sample
    val latestGps:        LiveData<GpsSample>    = _gpsSample
    val driftEstimateM:   LiveData<Float>        = _drift
    val csvLoggingActive: LiveData<Boolean>      = _csvActive
    val mlSpeedMps:       LiveData<Float?>       = _mlSpeedMps   // NEW
    val mlReady:          LiveData<Boolean>      = _mlReady      // NEW

    fun setMode(value: Mode)               { _mode.value        = value  }
    fun publishSample(value: SensorSample) { _sample.value      = value  }
    fun publishGps(value: GpsSample)       { _gpsSample.value   = value  }
    fun setDriftEstimateM(value: Float)    { _drift.value       = value.coerceAtLeast(0f) }
    fun setCsvLoggingActive(active: Boolean){ _csvActive.value  = active }
    fun setMlSpeedMps(value: Float?)       { _mlSpeedMps.value  = value  }   // NEW
    fun setMlReady(ready: Boolean)         { _mlReady.value     = ready  }   // NEW
}
```

### 4.9 Update Constants.kt

**File**: `app/src/main/java/com/sih/deadreckoninglite/util/Constants.kt`

Add these constants at the end of the `object Constants { }` block:

```kotlin
    // ================================================================== //
    //  ML Speed Estimator                                                 //
    // ================================================================== //

    /**
     * TFLite model input window size in IMU frames.
     * Must match WINDOW_SIZE used in the Python training script.
     */
    const val ML_WINDOW_SIZE: Int = 20

    /**
     * Downsample factor for ML model input.
     * ImuManager emits at ~50 Hz; model trained at 10 Hz.
     * Every 5th sample is passed to MlSpeedEstimator.addSample().
     */
    const val ML_DOWNSAMPLE_FACTOR: Int = 5

    /**
     * ML speed deadband in km/h.
     * Predictions below this are treated as zero (stationary).
     * 1.26 km/h = 0.35 m/s — same threshold as ConstantVelocityReckoner GPS deadband.
     */
    const val ML_SPEED_DEADBAND_KMH: Float = 1.26f

    /**
     * TFLite model asset filename. Must match the file at app/src/main/assets/.
     */
    const val ML_MODEL_ASSET: String = "speed_estimator.tflite"

    /**
     * Normalization stats JSON asset filename. Must match the file at app/src/main/assets/.
     */
    const val ML_NORM_STATS_ASSET: String = "norm_stats.json"
```

### 4.10 Update MainActivity.kt

**File**: `app/src/main/java/com/sih/deadreckoninglite/MainActivity.kt`

Make the following targeted additions (do NOT restructure the class):

**Add this import** at the top:
```kotlin
import com.sih.deadreckoninglite.ml.MlSpeedEstimator
```

**Add this field** alongside other module fields (near `tunnelSimulator`):
```kotlin
private lateinit var mlEstimator: MlSpeedEstimator
```

**In `onCreate()` or `onStart()`**, after instantiating `tunnelSimulator`, add:
```kotlin
// Create and wire ML estimator (composition root responsibility)
mlEstimator = MlSpeedEstimator(this)
tunnelSimulator.mlEstimator = mlEstimator
```

**In the ImuManager callback** (inside `imuManager.start { sample -> ... }`),
add these lines right after `sensorLogger.logImu(sample)`:
```kotlin
imuManager.start { sample ->
    sensorLogger.logImu(sample)
    runOnUiThread {
        viewModel.publishSample(sample)
        // Feed ML estimator
        mlEstimator.addSample(
            linearAx = sample.linearAx,
            linearAy = sample.linearAy,
            linearAz = sample.linearAz,
            gx       = sample.gx,
            gy       = sample.gy,
            gz       = sample.gz
        )
        // Update ViewModel with ML state for UI/telemetry display
        viewModel.setMlSpeedMps(mlEstimator.predictSpeedMps())
        viewModel.setMlReady(mlEstimator.isReady())
    }
}
```

**In `onDestroy()`**, add:
```kotlin
if (::mlEstimator.isInitialized) mlEstimator.close()
```

---

## Phase 5 — Unit Tests for MlSpeedEstimator

**Create file**:
`app/src/test/java/com/sih/deadreckoninglite/ml/MlSpeedEstimatorTest.kt`

```kotlin
package com.sih.deadreckoninglite.ml

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for pure-Kotlin logic in MlSpeedEstimator.
 * TFLite interpreter cannot run in JVM unit tests (requires Android native .so).
 * These tests cover: normalization math, deadband, downsampling, window management.
 */
class MlSpeedEstimatorTest {

    @Test
    fun `z-score normalization formula is correct`() {
        val mean  = 0.5f
        val std   = 2.0f
        val raw   = 2.5f
        val result = (raw - mean) / std
        assertEquals(1.0f, result, 0.0001f)
    }

    @Test
    fun `deadband suppresses speed below 1_26 kmh`() {
        val predicted = 1.0f
        val effective = if (predicted < 1.26f) 0.0f else predicted
        assertEquals(0.0f, effective, 0.0001f)
    }

    @Test
    fun `deadband passes speed at threshold 1_26 kmh`() {
        val predicted = 1.26f
        val effective = if (predicted < 1.26f) 0.0f else predicted
        assertEquals(1.26f, effective, 0.0001f)
    }

    @Test
    fun `deadband passes speed above threshold`() {
        val predicted = 50.0f
        val effective = if (predicted < 1.26f) 0.0f else predicted
        assertEquals(50.0f, effective, 0.0001f)
    }

    @Test
    fun `kmh to mps conversion is correct`() {
        assertEquals(10.0f, 36.0f / 3.6f, 0.001f)
        assertEquals(0.0f,  0.0f  / 3.6f, 0.001f)
    }

    @Test
    fun `downsample factor reduces inference frequency`() {
        val FACTOR = 5
        var skipCounter = 0
        var inferenceCount = 0
        repeat(50) {
            skipCounter++
            if (skipCounter >= FACTOR) {
                skipCounter = 0
                inferenceCount++
            }
        }
        assertEquals(10, inferenceCount)   // 50 calls / factor 5 = 10 inferences
    }

    @Test
    fun `window buffer size does not exceed WINDOW_SIZE`() {
        val WINDOW_SIZE = 20
        val buffer = ArrayDeque<FloatArray>(WINDOW_SIZE)
        repeat(25) { i ->
            if (buffer.size >= WINDOW_SIZE) buffer.removeFirst()
            buffer.addLast(floatArrayOf(i.toFloat()))
        }
        assertEquals(WINDOW_SIZE, buffer.size)
        assertEquals(5.0f, buffer.first()[0], 0.001f)   // oldest = sample index 5
    }

    @Test
    fun `speed clamped to 0-200 kmh`() {
        assertEquals(0.0f,   (-5.0f).coerceIn(0f, 200f), 0.001f)
        assertEquals(200.0f, (250.0f).coerceIn(0f, 200f), 0.001f)
        assertEquals(60.0f,  (60.0f).coerceIn(0f, 200f), 0.001f)
    }

    @Test
    fun `linear acceleration formula is correct`() {
        val ax    = 10.5f
        val gravX = 9.8f
        val linearAx = ax - gravX
        assertEquals(0.7f, linearAx, 0.001f)
    }

    @Test
    fun `stationary deadband at exactly 0_35 mps allows motion`() {
        val speedMps    = 0.35
        val effective   = if (speedMps < 0.35) 0.0 else speedMps
        assertEquals(0.35, effective, 0.0001)
    }

    @Test
    fun `below stationary deadband stops motion`() {
        val speedMps  = 0.34
        val effective = if (speedMps < 0.35) 0.0 else speedMps
        assertEquals(0.0, effective, 0.0001)
    }
}
```

---

## Phase 6 — Build & Verify

### 6.1 Build Commands
```bash
cd C:\Users\manas\OneDrive\Desktop\sihhhh\sih26_prototype1\sih-dead-reckoning-lite

# Stop any running Gradle daemons first (prevents file-lock issues)
.\gradlew.bat --stop

# Run unit tests
.\gradlew.bat test

# Build debug APK
.\gradlew.bat assembleDebug

# Install on connected device
adb install app\build\outputs\apk\debug\app-debug.apk
```

### 6.2 Logcat Filters to Verify ML Is Running
```
Filter by these tags in Android Studio Logcat:
  - Tag: ImuManager       -> verify "Gravity sensor registered"
  - Tag: MlSpeedEstimator -> verify model loads, then "ML speed: X.XX m/s"
  - Tag: TunnelSimulator  -> verify "ML speed: X.XX m/s" (not "Fallback CV speed") after ~2 s
```

---

## Phase 7 — SIH Submission Evidence

### 7.1 What the Screening Committee Expects
PRD requirement: *"Preliminary AI models and the results of the position plot
inferenced from the subset of IO-VNBD dataset"*

### 7.2 Mandatory Deliverables

| Deliverable | Source | Notes |
|---|---|---|
| Speed prediction plot | `outputs/drift_simulation.png` (left panel) | Shows ML vs GPS truth |
| Drift simulation plot | `outputs/drift_simulation.png` (right panel) | Shows position error over time |
| Test MAE value | Printed by training script | Target: < 3.5 km/h |
| Final position error | Printed by training script | In meters |
| `speed_estimator.tflite` | `outputs/` folder | Proof of Android-ready model |
| App demo video | Screen record while driving with tunnel toggle | 30–60 seconds |

### 7.3 Proposal Results Block to Fill In
```
AI Speed Estimator — Test Results
──────────────────────────────────────────────────────────────
Model architecture:  CNN(32 -> 64) + LSTM(128 -> 64) + Dense(32 -> 1)
Training dataset:    IO-VNBD Synchronised S-Dataset (N drives)
Input features:      linear_ax, linear_ay, linear_az, gx, gy, gz
Window size:         20 frames at 10 Hz (= 2 seconds of context)
Normalization:       Z-score (fit on training drives only)
Test MAE:            [FILL: X.XX] km/h
Final drift error:   [FILL: XXX] m over [FILL: YYY] seconds
TFLite model size:   [FILL: XXX] KB
Deployment target:   Android API 26+, TFLite 2.15, dynamic-range quantized
```

---

## Phase 8 — Complete Validation Checklist

### Python Training
- [ ] `train_speed_model.py` runs without errors
- [ ] `outputs/speed_estimator.tflite` file size > 100 KB
- [ ] `outputs/norm_stats.json` has exactly 6 values in `mean` and 6 in `std`
- [ ] `outputs/drift_simulation.png` shows both panels with readable axes
- [ ] Test MAE printed to console is < 3.5 km/h

### Android Build
- [ ] `app/build.gradle.kts` contains TFLite deps and `aaptOptions { noCompress("tflite") }`
- [ ] `app/src/main/assets/speed_estimator.tflite` exists
- [ ] `app/src/main/assets/norm_stats.json` exists
- [ ] `.\gradlew.bat assembleDebug` completes without errors
- [ ] `.\gradlew.bat test` — all unit tests pass

### CSV Schema Verification
- [ ] Start a drive session, stop it, open the CSV from `Android/data/.../logs/`
- [ ] Count columns: should be **14** (1 ts + 9 IMU + 4 GPS)
- [ ] Header row: `timestamp_ns,ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z,gnss_lat,gnss_lon,gnss_speed,gnss_accuracy`
- [ ] IMU rows: grav_x/y/z columns have non-zero values (not stuck at 0)

### Runtime on Device
- [ ] Logcat: "Gravity sensor registered" on app launch
- [ ] Logcat: "TFLite interpreter created from speed_estimator.tflite" on app launch
- [ ] Green path builds up during GNSS mode driving
- [ ] Toggling "Simulate Tunnel" activates amber path
- [ ] After ~2 seconds in DR mode: Logcat shows "ML speed: X.XX m/s" (not Fallback)
- [ ] `viewModel.mlReady` transitions to `true` (observable in UI or Logcat)
- [ ] Speed displayed in HUD is reasonable (non-zero while moving, near-zero when stationary)

---

## Appendix A — Complete File Change Summary

| File | Action | Key Reason |
|---|---|---|
| `sensors/SensorSample.kt` | MODIFY | Add gravX/Y/Z + linearAx/Y/Z computed properties |
| `sensors/ImuManager.kt` | MODIFY | Register TYPE_GRAVITY sensor |
| `logging/SensorLogger.kt` | MODIFY | Update IMU_EMPTY_COLS from 6 to 9 commas |
| `deadreckoning/TunnelSimulator.kt` | REPLACE | ML-integrated step-by-step DR loop |
| `ui/MainViewModel.kt` | MODIFY | Add mlSpeedMps and mlReady LiveData |
| `MainActivity.kt` | MODIFY | Wire MlSpeedEstimator; IMU -> ML; lifecycle |
| `util/Constants.kt` | MODIFY | Add ML_WINDOW_SIZE, ML_DOWNSAMPLE_FACTOR, ML_SPEED_DEADBAND_KMH |
| `app/build.gradle.kts` | MODIFY | Add TFLite deps + aaptOptions |
| `ml/MlSpeedEstimator.kt` | **NEW** | TFLite CNN+LSTM inference wrapper |
| `ml/MlSpeedEstimatorTest.kt` | **NEW** | Pure-Kotlin unit tests |
| `app/src/main/assets/speed_estimator.tflite` | **NEW** | Trained model binary |
| `app/src/main/assets/norm_stats.json` | **NEW** | Normalization parameters |
| `deadreckoning/ConstantVelocityReckoner.kt` | **DO NOT DELETE** | Retained as CV fallback |

---

## Appendix B — Troubleshooting

### Python: "No S-*.csv files found"
Used `git clone` and got LFS pointers. Re-download via GitHub browser Download button.

### Python: TFLite LSTM conversion error
Ensure `unroll=True` on BOTH LSTM layers. This is already included in the script above.

### Android: `UnsatisfiedLinkError` for TFLite at runtime
Add `aaptOptions { noCompress("tflite") }` inside `android {}` in build.gradle.kts.

### Android: `FileNotFoundException: speed_estimator.tflite`
File must be at `app/src/main/assets/speed_estimator.tflite`. Not in `res/raw/`, not anywhere else.

### Android: All ML predictions are 0.0
Check Logcat for "No TYPE_GRAVITY sensor". If your device lacks it, gravZ fallback (9.81) is used.
Verify `sample.linearAx` is different from `sample.ax` in Logcat output.

### Android: "Fallback CV speed" always shown, never "ML speed"
`mlEstimator.isReady()` is stuck at false. Means `addSample()` is not being called.
Verify the IMU callback in MainActivity feeds `mlEstimator.addSample(...)` on every tick.

### Test MAE > 5 km/h after training
Try these in order:
1. Loosen `GPS_ACC_THRESH` to 30m (keep more data from urban-canyon drives)
2. Reduce `STRIDE` from 10 to 5 (more training windows, more overlap)
3. Add `Magnetic Field X/Y/Z` to `FEATURE_COLS` for additional signal
4. Increase model capacity: LSTM(256, ...), LSTM(128, ...)

---

## Appendix C — Future Phases (Post-SIH Submission)

| Phase | Description |
|---|---|
| Gyroscope heading prediction | Add second model output (heading-rate rad/s), replacing GPS bearing during outage |
| Extended Kalman Filter | Fuse ML speed estimates with GPS using EKF for smoother state |
| Map matching | Snap reckoned path to OSM road graph using non-holonomic constraints |
| On-device fine-tuning | Collect Indian road drive CSVs from the app and fine-tune model locally |
| Auto-GNSS-outage detection | Replace manual tunnel toggle with automatic GPS quality monitoring |
