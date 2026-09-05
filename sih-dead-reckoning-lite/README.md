# Dead Reckoning Lite (SIH PS 26168)

## What This Is
This is a scoped-down, buildable prototype: Phase 0–1 of the parent roadmap (Intelligent Dead Reckoning System), pulled forward and shipped standalone for the internal college hackathon.

It is a minimal but real Android application that:
1. Reads live phone GPS and IMU (accelerometer + gyroscope) sensors.
2. Displays the vehicle's position on a live OpenStreetMap view.
3. Logs every sensor sample to CSV in a schema that is directly reusable by the parent project's future ML training pipeline.
4. Includes a "Simulate Tunnel" toggle that demonstrates GNSS-outage mode-switching using simple rule-based physics (explicitly not AI, not a Kalman filter).

**Honest Purpose:** Prove the data-acquisition pipeline and the mode-switch UX exist, in working code, in 3 days.

## How to Run & Install
1. Build the APK via Android Studio or `./gradlew assembleDebug`.
2. Install the APK on a physical Android device (Android 10+, API 26+).
3. Open the app and grant **Location Permissions** (`ACCESS_FINE_LOCATION`). This is required for the GPS reading.
4. Go for a walk or drive outdoors holding the phone to see the live telemetry and map updates. The app will log data to device storage continuously.

## App Features
*   **Live Sensor Telemetry**: Displays accelerometer and gyroscope data continuously.
*   **Live Map**: Uses OpenStreetMap to display real-time GPS locations.
*   **"Simulate Tunnel" Toggle**: Demonstrates rule-based constant-velocity dead reckoning to simulate a GNSS outage. When turned off, the map gracefully snaps back to live GPS.
*   **Data Logging**: Logs raw data to `drive_<timestamp>.csv` located in the app's external files directory.

## Judge Context
This is **Phase 0–1** of our actual roadmap. It is the data-acquisition and sensor-dashboard layer that *must* exist before any AI model can be trained on Indian road conditions.
