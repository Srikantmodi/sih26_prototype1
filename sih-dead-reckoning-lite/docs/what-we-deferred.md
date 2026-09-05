# What We Deferred (Next Phase)

This prototype is Phase 0–1 of the roadmap (data collection and UX proof). We deliberately deferred the following components to the next phase, once we have real collected data to build against (as described in §13 of the PRD):

*   **Trained AI/ML Model:** No model exists yet — this prototype is what produces the data to train one.
*   **Kalman Filter (EKF/UKF) / Sensor Fusion Math:** Replaced by a constant-velocity projection placeholder.
*   **Map-Matching / Road-Snapping / Non-holonomic constraints:** The reckoned path is not corrected against the road graph at all.
*   **Phone-to-Vehicle Calibration/Alignment Module:** We assume phone orientation is roughly forward-facing; no correction is applied.
*   **C++ Core Engine / NDK / JNI Bridges:** We used a plain Kotlin constant-velocity projector.
*   **Edge-Deployable Engine:** This is currently an Android-only artifact.
*   **Cloud Backend / Accounts / Login:** Not needed for local data collection.
*   **Destination Search / Routing / Turn-by-Turn Instructions:** Was never in scope, even for the full project.

We have clearly separated "what's real" (the logging pipeline and UI) from "what's a placeholder" (the constant velocity reckoner) to lay a solid foundation for these deferred items.
