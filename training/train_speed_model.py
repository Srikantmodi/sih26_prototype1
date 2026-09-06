"""
SIH PS-26168 — IMU-to-Velocity CNN+LSTM Model
Dataset:  IO-VNBD Smartphone S-* drives (Synchronised dataset)
Outputs:  speed_estimator.tflite  — Android TFLite model
          norm_stats.json         — normalization stats for Android app
          drift_simulation.png    — SIH submission evidence plot
"""

import os
import json
import shutil
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models
from sklearn.model_selection import train_test_split
import matplotlib
matplotlib.use("Agg")   # non-interactive backend for headless servers
import matplotlib.pyplot as plt

# ─────────────────────────────────────────────────────────────
#  CONFIGURATION
# ─────────────────────────────────────────────────────────────
DATA_DIR = r"C:\Users\manas\Desktop\sih_ml_training\data\Synchronised V abd S datasets\Uncategorised IOVNB Dataset\S-Dataset"
OUT_DIR  = r"C:\Users\manas\Desktop\sih_ml_training\outputs"
APP_ASSETS_DIR = r"C:\Users\manas\OneDrive\Desktop\sihhhh\sih26_prototype1\sih-dead-reckoning-lite\app\src\main\assets"

WINDOW_SIZE  = 20      # 2 seconds at 10Hz
STRIDE       = 10      # 50% overlap -> more training windows
GPS_ACC_THRESH = 20.0  # drop rows where GPS accuracy > 20m (unreliable labels)

os.makedirs(OUT_DIR, exist_ok=True)
if os.path.exists(APP_ASSETS_DIR):
    os.makedirs(APP_ASSETS_DIR, exist_ok=True)

# ─────────────────────────────────────────────────────────────
#  COLUMN NORMALIZER (handles variations in dataset CSV headers)
# ─────────────────────────────────────────────────────────────
def normalize_col_name(c: str) -> str:
    cl = c.strip().lower()
    if "accelerometer x" in cl or "accel x" in cl: return "ax"
    if "accelerometer y" in cl or "accel y" in cl: return "ay"
    if "accelerometer z" in cl or "accel z" in cl: return "az"
    if "gravity x" in cl: return "grav_x"
    if "gravity y" in cl: return "grav_y"
    if "gravity z" in cl: return "grav_z"
    if "gyroscope x" in cl or "gyro x" in cl or "gyroscope yaw" in cl: return "gx"
    if "gyroscope y" in cl or "gyro y" in cl or "gyroscope pitch" in cl: return "gy"
    if "gyroscope z" in cl or "gyro z" in cl or "gyroscope roll" in cl: return "gz"
    if "gps speed" in cl: return "gps_speed"
    if "gps accuracy" in cl: return "gps_accuracy"
    return cl

FEATURE_COLS = ["linear_ax", "linear_ay", "linear_az", "gx", "gy", "gz"]
LABEL_COL    = "gps_speed"   # unit: km/h in dataset

# ─────────────────────────────────────────────────────────────
#  STEP 1: LOAD & PREPROCESS
# ─────────────────────────────────────────────────────────────
def load_drive(csv_path: str) -> pd.DataFrame:
    """Load one S-*.csv, compute derived columns, forward-fill GPS labels."""
    df = pd.read_csv(csv_path, encoding="latin1")
    df = df.rename(columns={c: normalize_col_name(c) for c in df.columns})

    # Ensure required columns exist
    req_cols = ["ax", "ay", "az", "grav_x", "grav_y", "grav_z", "gx", "gy", "gz", "gps_speed"]
    for col in req_cols:
        if col not in df.columns:
            raise KeyError(f"Missing required column: {col}")

    # Convert to numeric
    for col in req_cols:
        df[col] = pd.to_numeric(df[col], errors="coerce")
    if "gps_accuracy" in df.columns:
        df["gps_accuracy"] = pd.to_numeric(df["gps_accuracy"], errors="coerce")

    # Gravity-compensated linear acceleration
    df["linear_ax"] = df["ax"] - df["grav_x"]
    df["linear_ay"] = df["ay"] - df["grav_y"]
    df["linear_az"] = df["az"] - df["grav_z"]

    # Forward-fill GPS speed across the slower 1Hz updates
    df["gps_speed"] = df["gps_speed"].ffill().bfill()

    # Drop rows with poor GPS accuracy (unreliable training label)
    if "gps_accuracy" in df.columns:
        df = df[df["gps_accuracy"] <= GPS_ACC_THRESH]

    # Drop any remaining NaN rows
    df = df.dropna(subset=FEATURE_COLS + [LABEL_COL])
    return df

def make_windows(df: pd.DataFrame):
    """Sliding window extraction -> returns X [N, W, F] and y [N]."""
    X, y = [], []
    arr = df[FEATURE_COLS].values.astype(np.float32)
    lbl = df[LABEL_COL].values.astype(np.float32)
    for start in range(0, len(df) - WINDOW_SIZE, STRIDE):
        X.append(arr[start : start + WINDOW_SIZE])
        y.append(np.mean(lbl[start : start + WINDOW_SIZE]))   # avg GPS speed over window
    if len(X) == 0:
        return np.empty((0, WINDOW_SIZE, len(FEATURE_COLS)), dtype=np.float32), np.empty((0,), dtype=np.float32)
    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32)

# Select key representative drives from S-Dataset (large, diverse drives)
target_drive_names = ["S-S1.csv", "S-S2.csv", "S-S3a.csv", "S-S4.csv", "S-Vfa01.csv", "S-Vfa02.csv"]
all_files = [os.path.join(DATA_DIR, f) for f in target_drive_names if os.path.exists(os.path.join(DATA_DIR, f))]
if not all_files:
    # Fallback to any S-*.csv
    all_files = sorted([
        os.path.join(DATA_DIR, f)
        for f in os.listdir(DATA_DIR)
        if f.startswith("S-") and f.endswith(".csv")
    ])[:8]

print(f"Loading {len(all_files)} drives for training...")
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
        print(f"  Loaded {os.path.basename(fpath)}: {len(Xi)} windows, "
              f"speed range [{yi.min():.1f}, {yi.max():.1f}] km/h, mean={yi.mean():.1f} km/h")
    except Exception as e:
        print(f"  ERROR {os.path.basename(fpath)}: {e}")

X = np.concatenate(all_X)
y = np.concatenate(all_y)
print(f"\nTotal dataset: {X.shape[0]} windows, shape {X.shape}, "
      f"speed range [{y.min():.1f}, {y.max():.1f}] km/h")

# ─────────────────────────────────────────────────────────────
#  STEP 2: TRAIN / VAL / TEST SPLIT
# ─────────────────────────────────────────────────────────────
X_train, X_temp, y_train, y_temp = train_test_split(
    X, y, test_size=0.3, random_state=42, shuffle=True
)
X_val, X_test, y_val, y_test = train_test_split(
    X_temp, y_temp, test_size=0.5, random_state=42
)
print(f"Split -> Train: {len(X_train)}, Val: {len(X_val)}, Test: {len(X_test)}")

# ─────────────────────────────────────────────────────────────
#  STEP 3: NORMALIZE (fit on train only — no data leakage)
# ─────────────────────────────────────────────────────────────
mean = X_train.mean(axis=(0, 1))   # shape [NUM_FEATURES]
std  = X_train.std(axis=(0, 1)) + 1e-8   # epsilon prevents div-by-zero

X_train = (X_train - mean) / std
X_val   = (X_val   - mean) / std
X_test  = (X_test  - mean) / std

norm_stats = {
    "mean":        mean.tolist(),
    "std":         std.tolist(),
    "features":    FEATURE_COLS,
    "window_size": WINDOW_SIZE
}
norm_path = os.path.join(OUT_DIR, "norm_stats.json")
with open(norm_path, "w") as f:
    json.dump(norm_stats, f, indent=2)
print(f"Normalization stats saved -> {norm_path}")

# ─────────────────────────────────────────────────────────────
#  STEP 4: DEFINE CNN + LSTM MODEL
# ─────────────────────────────────────────────────────────────
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
    loss="huber",
    metrics=["mae"]
)
model.summary()

# ─────────────────────────────────────────────────────────────
#  STEP 5: TRAIN
# ─────────────────────────────────────────────────────────────
callbacks = [
    tf.keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=8, restore_best_weights=True, verbose=1
    ),
    tf.keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss", factor=0.5, patience=4, min_lr=1e-5, verbose=1
    ),
]

print("\nStarting model training...")
history = model.fit(
    X_train, y_train,
    validation_data=(X_val, y_val),
    epochs=40,
    batch_size=128,
    callbacks=callbacks,
    verbose=1
)

# ─────────────────────────────────────────────────────────────
#  STEP 6: EVALUATE & GENERATE SUBMISSION EVIDENCE
# ─────────────────────────────────────────────────────────────
test_loss, test_mae = model.evaluate(X_test, y_test, verbose=0)
print(f"\n{'='*55}")
print(f"FINAL TEST MAE:  {test_mae:.2f} km/h  (Target: < 3.5 km/h)")
print(f"FINAL TEST LOSS: {test_loss:.4f}")
print(f"{'='*55}\n")

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
axes[0].set_xlabel("Window Index")
axes[0].set_ylabel("Speed (km/h)")
axes[0].set_title(f"Speed Prediction  |  MAE = {test_mae:.2f} km/h")
axes[0].legend()
axes[0].grid(True, alpha=0.3)

axes[1].plot(time_axis, dist_true, label="True Distance (GPS integrated)",   linewidth=1.5)
axes[1].plot(time_axis, dist_pred, label="ML Dead-Reckoned Distance",         linewidth=1.5, linestyle="--")
axes[1].fill_between(time_axis, dist_true, dist_pred, alpha=0.15,
                     label=f"Error (final={drift_m[-1]:.1f} m)")
axes[1].set_xlabel("Time (s)")
axes[1].set_ylabel("Distance (m)")
axes[1].set_title(f"Drift Simulation  |  Final Error = {drift_m[-1]:.1f} m")
axes[1].legend()
axes[1].grid(True, alpha=0.3)

plt.tight_layout()
plot_path = os.path.join(OUT_DIR, "drift_simulation.png")
plt.savefig(plot_path, dpi=150, bbox_inches="tight")
print(f"\nEvidence plot saved -> {plot_path}")

# ─────────────────────────────────────────────────────────────
#  STEP 7: EXPORT TO TFLITE
# ─────────────────────────────────────────────────────────────
print("\nExporting model to TFLite format...")
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

tflite_path = os.path.join(OUT_DIR, "speed_estimator.tflite")
with open(tflite_path, "wb") as f:
    f.write(tflite_model)

print(f"TFLite model saved -> {tflite_path}")
print(f"TFLite model size:   {len(tflite_model) / 1024:.1f} KB")

# ─────────────────────────────────────────────────────────────
#  STEP 8: AUTO-COPY TO ANDROID APP ASSETS
# ─────────────────────────────────────────────────────────────
if os.path.exists(APP_ASSETS_DIR):
    shutil.copy2(tflite_path, os.path.join(APP_ASSETS_DIR, "speed_estimator.tflite"))
    shutil.copy2(norm_path, os.path.join(APP_ASSETS_DIR, "norm_stats.json"))
    print(f"\nSuccessfully copied trained model and norm stats directly to Android assets:")
    print(f"  -> {APP_ASSETS_DIR}\\speed_estimator.tflite")
    print(f"  -> {APP_ASSETS_DIR}\\norm_stats.json")

print("\n" + "="*60)
print("TRAINING & EXPORT COMPLETE!")
print(f"  Test MAE:             {test_mae:.2f} km/h")
print(f"  Model Size:           {len(tflite_model) / 1024:.1f} KB")
print(f"  Drift Plot:           {plot_path}")
print("="*60)
