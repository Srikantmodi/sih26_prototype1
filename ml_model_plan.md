# ML model for vehicle positioning using inertial and odometry data

**Created:** 9/6/2026 14:42:19  
**Updated:** 9/6/2026 14:50:10  
**Exported:** 9/6/2026 14:59:18  
**Link:** [https://claude.ai/chat/f3fecd1c-b9af-4157-8c41-b4e30095f7d1](https://claude.ai/chat/f3fecd1c-b9af-4157-8c41-b4e30095f7d1)  

## User:

> 9/6/2026 14:42:24

> File: prototype1_prd (1).pdf

> File: 

IO-VNBD: Inertial and Odometry benchmark dataset for ground vehicle positioning (https://github.com/onyekpeu/IO-VNBD)

this is the basic project PRD and there is the problem statement for SIH it says that i need to build an ML model and i have to train it using the dataset link i have given now from github link. i want you to explain me what ML model i should train for this project idea based on the PRd i have given and how i should i build it detailly. explain me in a step by step manner

## Assistant:

> 9/6/2026 14:43:46

> Researching model recommendations for vehicle navigation data.
>
> - [GitHub - onyekpeu/IO-VNBD: Inertial and Odometry Benchmark Dataset for Ground Vehicle Positioning (github.com)](https://github.com/onyekpeu/IO-VNBD)
> - [https://raw.githubusercontent.com/onyekpeu/IO-VNBD/master/README.md](https://raw.githubusercontent.com/onyekpeu/IO-VNBD/master/README.md)
> - IO-VNBD dataset README columns speed heading accelerometer gyroscope
> - Researching model recommendations for vehicle navigation data.
> - **Done**

Based on both your PRD and the actual IO‑VNBD dataset structure, here's what I'd build and why.

## The core insight: this is a speed/displacement regression problem, not classification

Your PRD's "AI Speed & Vibration Filter" requirement is asking for exactly what the IO‑VNBD dataset's own creators built it for: **learn to predict vehicle velocity/displacement directly from noisy IMU windows, bypassing the need for double-integration** (which is what causes the exponential drift in classical dead reckoning). Double-integrating raw accelerometer data to get position is mathematically correct but practically useless on MEMS-grade sensors because tiny bias errors compound quadratically over time. So instead of "integrate acceleration → velocity → position," the ML model learns a direct mapping: **IMU window → displacement over that window**.

This is a **sequence regression problem**, and the standard architecture for it is an **LSTM (or GRU) recurrent network**, optionally hybridized with a 1D-CNN front end. This is not a guess — the IO‑VNBD dataset's original paper and a follow-up paper by the same authors ("Learning to Localise Automated Vehicles in Challenging Environments using INS") use exactly this LSTM-based approach on this exact dataset.

## Recommended architecture: two-stage model

**Stage 1 — IMU-to-Velocity Network (the model you'll actually train first)**
- Input: a sliding window of raw IMU samples (accelerometer x/y/z, gyroscope x/y/z, and ideally magnetometer/heading if available), e.g. 1–2 seconds at 10Hz = 10–20 timesteps.
- Output: estimated forward velocity (and optionally heading change) for that window.
- Architecture: CNN1D (1–2 conv layers to extract local vibration/shock features) → stacked LSTM (2 layers, 64–128 units) → dense output layer.
- Ground truth for training: the GPS-derived speed (`gnss_speed`) from the same dataset, which acts as your label during GNSS-available segments.

**Stage 2 — Fusion/Correction layer**
- During GNSS availability: continuously use GNSS position/speed as ground truth, and optionally have a lightweight model learn the *residual bias* between what Stage 1 predicts and true GNSS speed (this is your "AI based Sensor Fusion" piece).
- During GNSS outage: integrate Stage 1's predicted velocity + heading over time to get position (dead reckoning), instead of integrating raw accelerometer output. Optionally add a Kalman filter on top that uses Stage 1's output as its process model input — this satisfies your "GNSS+INS Fusion Engine" requirement without you needing a physics-first strapdown INS.

Map-matching (OSM + non-holonomic constraints) sits as a separate, non-ML, deterministic post-processing step after Stage 2 — you don't need ML for that part, just geometry.

## Step-by-step build plan

> Used tool
>
> - Step-by-step plan for the IMU-to-velocity ML model using IO-VNBD
> - **Done**

Here's a minimal training script skeleton to get you started, using Keras since TFLite export is smoother from there:

```python
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models

WINDOW_SIZE = 20       # 2 seconds at 10Hz
STRIDE = 10            # 50% overlap
FEATURES = ["ax", "ay", "az", "gx", "gy", "gz"]  # extend with mag if used

def make_windows(df, window_size=WINDOW_SIZE, stride=STRIDE):
    X, y = [], []
    for start in range(0, len(df) - window_size, stride):
        chunk = df.iloc[start:start + window_size]
        X.append(chunk[FEATURES].values)
        y.append(chunk["gps_speed"].mean())   # label = avg GPS speed over window
    return np.array(X), np.array(y)

# Load per-drive, keep drives separate for the train/val/test split
train_drives = [...]  # list of file paths, e.g. Driver A, B
val_drives   = [...]
test_drives  = [...]  # held-out driver/vehicle for generalization test

def load_and_window(paths):
    X_list, y_list = [], []
    for p in paths:
        df = pd.read_csv(p)
        Xi, yi = make_windows(df)
        X_list.append(Xi); y_list.append(yi)
    return np.concatenate(X_list), np.concatenate(y_list)

X_train, y_train = load_and_window(train_drives)
X_val, y_val     = load_and_window(val_drives)
X_test, y_test   = load_and_window(test_drives)

# Normalize using train stats only
mean, std = X_train.mean(axis=(0, 1)), X_train.std(axis=(0, 1))
X_train = (X_train - mean) / std
X_val   = (X_val - mean) / std
X_test  = (X_test - mean) / std

model = models.Sequential([
    layers.Input(shape=(WINDOW_SIZE, len(FEATURES))),
    layers.Conv1D(32, 3, activation="relu", padding="same"),
    layers.Conv1D(64, 3, activation="relu", padding="same"),
    layers.LSTM(128, return_sequences=True),
    layers.LSTM(64),
    layers.Dense(32, activation="relu"),
    layers.Dense(1)   # predicted speed; add a second unit for heading-rate if desired
])

model.compile(optimizer="adam", loss="huber", metrics=["mae"])

callbacks = [tf.keras.callbacks.EarlyStopping(patience=8, restore_best_weights=True)]

model.fit(X_train, y_train, validation_data=(X_val, y_val),
          epochs=100, batch_size=64, callbacks=callbacks)

# Export to TFLite for the Android app
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()
with open("speed_estimator.tflite", "wb") as f:
    f.write(tflite_model)
```

A few things worth flagging honestly, since your proposal will be judged on this:

- **Don't skip the drift-simulation validation** (step 6). A model with great MSE on speed prediction can still drift badly once you integrate its output over time, because small consistent biases compound. This is the number the PRD screening actually wants to see, not your training loss.
- **Heading estimation is often the harder half.** Speed-from-IMU is well-studied (that's basically what this dataset was built for), but heading drift from a noisy gyroscope is usually the bigger source of final-position error. If you have time, consider predicting heading-rate as a second output rather than relying on the magnetometer alone (magnetometers on a car dashboard get heavily distorted by the vehicle's own metal/electronics).
- **The 3-day prototype PRD you attached is a separate, smaller deliverable** — it explicitly excludes any ML model. So this training pipeline is for the *parent* 6-week project / the actual SIH proposal submission, not something you'd wire into the prototype app before your internal hackathon deadline.

---

**Sources:**

- [IO-VNBD: Inertial and Odometry benchmark dataset for ground vehicle positioning - PMC](https://ncbi.nlm.nih.gov/pmc/articles/PMC7907232)
- [[PDF] IO-VNBD: Inertial and Odometry benchmark dataset for ground vehicle positioning | Semantic Scholar](https://www.semanticscholar.org/paper/IO-VNBD:-Inertial-and-Odometry-benchmark-dataset-Onyekpe-Palade/b7f26d5afa5a0e85d696e5a2c8560c8aaa2b6de4)
- [(PDF) IO-VNBD: Inertial and Odometry Benchmark Dataset for Ground Vehicle Positioning](https://www.researchgate.net/publication/349341035_IO-VNBD_Inertial_and_Odometry_Benchmark_Dataset_for_Ground_Vehicle_Positioning)
- [IO-VNBD/README_1.pdf at master · onyekpeu/IO-VNBD](https://github.com/onyekpeu/IO-VNBD/blob/master/README_1.pdf)
- [(Open Access) IO-VNBD: Inertial and Odometry Benchmark Dataset for Ground Vehicle Positioning (2020) | Uche Onyekpe | 11 Citations](https://scispace.com/papers/io-vnbd-inertial-and-odometry-benchmark-dataset-for-ground-4ernvhmb4u)
- [uf-ahrs 0.2.0](https://docs.rs/crate/uf-ahrs/latest)
- [GitHub - mmalekzadeh/motion-sense: MotionSense Dataset for Human Activity and Attribute Recognition ( time-series data generated by smartphone's sensors: accelerometer and gyroscope) (PMC Journal) (IoTDI'19) · GitHub](https://github.com/mmalekzadeh/motion-sense)
- [Learning to Localise Automated Vehicles in Challenging Environments using Inertial Navigation Systems (INS)](https://arxiv.org/pdf/2010.02363)

## User:

> 9/6/2026 14:45:04

i have already built everything in the prd and I have to just train the ML model based on the dataset i have given  tell me exactly what i should be doing now

## Assistant:

> 9/6/2026 14:50:10

> Diagnosing a blocked download caused by egress proxy restrictions.
>
> - Planning concrete steps to train a model on the dataset.
> - Clone the IO-VNBD dataset repo to inspect actual file structure
> - Look at the repo structure
> - List files inside the dataset folders
> - See full directory tree
> - Inspect one categorised drive folder's files and headers
> - Try pulling actual LFS-tracked CSV data
> - Install git-lfs to fetch actual dataset content
> - Attempt to pull LFS data (may fail due to network domain restrictions)
> - Diagnosing blocked file downloads from a restricted domain.
> - Check gitattributes LFS tracking and zip file type
> - Retrieving large tracked files despite access restrictions.
> - Try pulling just one small drive's files to check header content
> - Diagnosing a blocked download caused by egress proxy restrictions.
> - Read the actual README for column/schema details
> - Confirm README_1.pdf is a real file not an LFS pointer
> - **Done**

It's a real PDF. Let me read it for the exact column schema.

> Mapping sensor columns to build a concrete training pipeline.
>
> - Extract text from the dataset's README PDF to find column schema
> - Mapping sensor columns to build a concrete training pipeline.
> - **Done**

I dug into the actual dataset repo (not just the paper) to get you the real column names and folder layout, so this plan is exact rather than generic. Here's precisely what to do now.

## 1. Get the data properly (skip `git clone`)

The repo stores every CSV/ZIP as **Git LFS pointers** — cloning it normally (or my sandbox trying to) gets you tiny placeholder text files, not real data. Don't fight LFS:

- Go to the GitHub repo page → download `Synchronised V and S datasets.zip` and `Unsynchronised V and S Dataset.zip` directly via the "Download" button in your browser (GitHub serves LFS content correctly through the web UI/raw download, unlike a plain git clone).
- Use the **Synchronised** folder — it has matched V- (vehicle CAN bus) and S- (smartphone) drives, which is what you need since your app only has phone sensors.

## 2. Use the S- (smartphone) files — here's their exact real schema

I pulled this straight from the dataset's own README PDF:

| Column | Unit |
|---|---|
| GPS Latitude, GPS Longitude | degrees |
| GPS Altitude | m |
| **GPS Speed** | km/h |
| GPS Accuracy | m |
| GPS Orientation | ° |
| GPS Satellites In Range | — |
| Time Since Start | ms |
| Date | timestamp |
| Accelerometer X/Y/Z | m/s² |
| **Gravity X/Y/Z** | m/s² |
| Gyroscope Yaw/Pitch/Roll | rad/s |
| Magnetic Field X/Y/Z | µT |
| Orientation Yaw/Roll/Pitch | ° |

Two things that change your plan for the better:

- **`GPS Speed` is already a column** — you don't need to derive velocity from lat/lon differences. That's your training label, ready-made.
- **`Gravity X/Y/Z` is provided separately from `Accelerometer X/Y/Z`.** That means `Accelerometer − Gravity` gives you the actual linear (motion) acceleration with gravity removed — no need to build your own gravity-estimation filter. This was the "remove IMU noise & bias" step in your PRD; half of it is already done for you by the dataset.

## 3. Exact preprocessing steps

1. Load a drive's `S-*.csv`.
2. Compute `linear_ax = Accel_X - Gravity_X` (same for y, z).
3. IMU rows come at 10Hz but GPS Speed only updates at 1Hz — forward-fill `GPS Speed` between updates so every 10Hz row has a label.
4. Drop or flag rows where `GPS Accuracy` is poor (e.g. > 15–20m) or `GPS Satellites In Range` is very low — these are unreliable labels, not good training signal.
5. Z-score normalize `linear_ax/ay/az`, gyroscope, magnetometer channels (fit stats on train drives only).

## 4. Tested, working training script

I validated this end-to-end against a synthetic file with the exact same column names, so the mechanics (windowing, shapes, training loop, TFLite export) are confirmed to run without errors — just point it at your real folders.

> Ran command, created file
>
> - Install TF to validate the training script actually runs
> - Create the validated end-to-end training script for the IO-VNBD S- dataset
> - **Done**

Now let me actually verify this runs correctly against a synthetic file with the exact real column names, so you know it works before you point it at your real data:

> Diagnosing a TFLite conversion error with stacked LSTM layers.
>
> - Create synthetic CSVs matching the real IO-VNBD S- schema to validate the script
> - Run the training script end-to-end to confirm it works
> - Diagnosing a TFLite conversion error with stacked LSTM layers.
> - **Done**

Good — that surfaced a real bug you'd have hit. Let me fix it:

> Edited file, ran command
>
> - Fix TFLite conversion failure by unrolling the LSTM layers (required for static shapes on-device)
> - Re-run to confirm the fix works end-to-end
> - **Done**

It runs cleanly end-to-end now — training, evaluation, and TFLite export all succeed.

> Ran command, presented file
>
> - Confirm output artifacts exist
> - Presenting file(s)...
> - **Done**

## Exactly what to do now

1. **Download the real data via browser** (not `git clone` — LFS pointers, empty files). Get `Synchronised V and S datasets.zip`, unzip it, find all the `S-*.csv` files.
2. **Set `DATA_DIR`** in the script above to wherever you unzipped the `S-Dataset` folder.
3. **Run it**: `pip install tensorflow pandas numpy` then `python train_speed_model.py`. It will:
   - remove gravity from raw accelerometer using the dataset's own `Gravity X/Y/Z` columns
   - forward-fill `GPS Speed` as your label across the slower GPS update rate
   - split by whole drive (not shuffled windows) so you're testing generalization
   - train the CNN+LSTM speed estimator
   - print test MAE in km/h
   - export `speed_estimator.tflite` + normalization stats, ready to drop into your Android app's `assets/` folder
4. **Report the test MAE and a drift-simulation plot** in your proposal — that's literally what the PRD screening asks for ("preliminary AI models and the results of the position plot inferenced from the subset of IO-VNBD dataset").
5. Once this trains cleanly on real data, that's your MVP. The Kalman/fusion layer and map-matching are next-phase work you can mention as roadmap, not something screening requires yet.

One thing to watch when you run it for real: if your `GPS Accuracy` filter (`≤20m`) throws away too much of a drive, loosen it — some IO-VNBD tunnel/urban-canyon segments have naturally worse GPS accuracy, and you don't want to accidentally exclude exactly the scenarios your model needs to learn from.

> File: [/home/claude/train_speed_model.py](https://claude.ai/api/organizations/05e94bfa-484f-43d2-aad3-02b13b779d48/conversations/f3fecd1c-b9af-4157-8c41-b4e30095f7d1/wiggle/download-file?path=%2Fhome%2Fclaude%2Ftrain_speed_model.py)

Powered by Claude Exporter (https://www.ai-chat-exporter.net)
