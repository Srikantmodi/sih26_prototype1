"""
SIH PS-26168: High-Frequency Edge Deployable Software Engine (200 Hz)
Target Architecture: Industrial Embedded Edge Box / NVIDIA Jetson / Raspberry Pi 5
Sensor Input: Tactical Fiber Optic Gyroscope (FOG) IMU (RS-422 / Serial / CAN)

Algorithm:
- 200 Hz Strapdown Inertial Navigation System (INS)
- High-rate Quaternion/Heading Integration
- WGS84 Geodetic Position Integration
- Error-State Extended Kalman Filter (ES-EKF) for GNSS+INS Fusion
- Low-latency compute loop (< 0.1ms per 5ms cycle)
"""

import math
import numpy as np

class FogInsEkfEngine:
    def __init__(self, init_lat: float = 12.9716, init_lon: float = 77.5946, init_heading_deg: float = 0.0):
        # 200 Hz update timing
        self.sample_rate_hz = 200.0
        self.dt = 1.0 / self.sample_rate_hz  # 0.005 s = 5 ms

        # Geodetic state (WGS-84)
        self.lat = init_lat
        self.lon = init_lon
        self.heading_rad = math.radians(init_heading_deg)
        self.R = 6371000.0  # Earth radius in meters

        # Velocity in Navigation frame (North, East)
        self.vn = 0.0
        self.ve = 0.0

        # High-grade FOG calibration parameters
        # Estimated residual biases (corrected by EKF during GNSS-available phases)
        self.est_gyro_bias = 0.0
        self.est_accel_bias = 0.0

        # EKF State Covariance (North Pos, East Pos, North Vel, East Vel, Heading, Acc Bias, Gyro Bias)
        self.P = np.diag([1.0, 1.0, 0.1, 0.1, 0.001, 1e-4, 1e-6])

        # Process Noise Covariance (Q) - tuned for tactical FOG
        self.Q = np.diag([
            1e-4, 1e-4,     # Position noise
            1e-3, 1e-3,     # Velocity noise
            1e-6,           # Gyro noise (FOG ARW)
            1e-8, 1e-9      # Bias random walk
        ])

        # Measurement Noise Covariance (R_gnss) for 10Hz GNSS fixes
        self.R_gnss = np.diag([2.0, 2.0, 0.2, 0.2])  # 2m pos accuracy, 0.2 m/s vel accuracy

        # Operational metrics
        self.total_steps = 0
        self.gnss_blackout_mode = False

    def predict_200hz(self, forward_accel_mps2: float, yaw_rate_rads: float):
        """
        Runs at 200 Hz (every 5 ms = 0.005 s).
        Performs high-speed Strapdown INS integration.
        """
        # 1. Bias compensation
        corr_yaw_rate = yaw_rate_rads - self.est_gyro_bias
        corr_accel = forward_accel_mps2 - self.est_accel_bias

        # 2. Attitude Integration (Heading)
        self.heading_rad += corr_yaw_rate * self.dt
        self.heading_rad = (self.heading_rad + math.pi) % (2.0 * math.pi) - math.pi

        # 3. Specific Force Transformation to Navigation Frame (North-East)
        cos_h = math.cos(self.heading_rad)
        sin_h = math.sin(self.heading_rad)
        accel_north = corr_accel * cos_h
        accel_east = corr_accel * sin_h

        # 4. Velocity Integration
        self.vn += accel_north * self.dt
        self.ve += accel_east * self.dt

        # Ensure stationary zero-velocity if no movement detected (ZUPT logic)
        current_speed_mps = math.sqrt(self.vn**2 + self.ve**2)
        if abs(corr_accel) < 0.05 and current_speed_mps < 0.2:
            self.vn = 0.0
            self.ve = 0.0

        # 5. Position Integration (Geodetic WGS84)
        disp_north = self.vn * self.dt
        disp_east = self.ve * self.dt

        delta_lat = math.degrees(disp_north / self.R)
        lat_rad = math.radians(self.lat)
        delta_lon = math.degrees(disp_east / (self.R * math.cos(lat_rad)))

        self.lat += delta_lat
        self.lon += delta_lon

        # 6. Propagate Covariance (P = F * P * F^T + Q)
        # Jacobian F for discrete-time propagation
        F = np.eye(7)
        F[0, 2] = self.dt  # pos_N from vel_N
        F[1, 3] = self.dt  # pos_E from vel_E
        F[2, 4] = -corr_accel * sin_h * self.dt  # vel_N from heading
        F[3, 4] =  corr_accel * cos_h * self.dt  # vel_E from heading
        F[2, 5] = -cos_h * self.dt               # vel_N from accel_bias
        F[3, 5] = -sin_h * self.dt               # vel_E from accel_bias
        F[4, 6] = -self.dt                       # heading from gyro_bias

        self.P = F @ self.P @ F.T + self.Q

        self.total_steps += 1
        speed_kmh = math.sqrt(self.vn**2 + self.ve**2) * 3.6
        return self.lat, self.lon, speed_kmh, math.degrees(self.heading_rad)

    def gnss_update(self, gnss_lat: float, gnss_lon: float, gnss_speed_mps: float, gnss_heading_deg: float):
        """
        Runs at GNSS availability rate (e.g. 10 Hz) when outdoors.
        Updates state and estimates gyro/accelerometer sensor biases.
        """
        self.gnss_blackout_mode = False

        # Convert GNSS lat/lon difference to local metric displacement
        d_north = math.radians(gnss_lat - self.lat) * self.R
        d_east = math.radians(gnss_lon - self.lon) * (self.R * math.cos(math.radians(self.lat)))

        gnss_h_rad = math.radians(gnss_heading_deg)
        gnss_vn = gnss_speed_mps * math.cos(gnss_h_rad)
        gnss_ve = gnss_speed_mps * math.sin(gnss_h_rad)

        # Innovation (Residual)
        z = np.array([d_north, d_east, gnss_vn - self.vn, gnss_ve - self.ve])

        # Measurement matrix H
        H = np.zeros((4, 7))
        H[0, 0] = 1.0  # pos_N
        H[1, 1] = 1.0  # pos_E
        H[2, 2] = 1.0  # vel_N
        H[3, 3] = 1.0  # vel_E

        # Kalman Gain
        S = H @ self.P @ H.T + self.R_gnss
        K = self.P @ H.T @ np.linalg.inv(S)

        # State correction
        dx = K @ z

        # Apply corrections
        d_lat_corr = math.degrees(dx[0] / self.R)
        d_lon_corr = math.degrees(dx[1] / (self.R * math.cos(math.radians(self.lat))))
        self.lat += d_lat_corr
        self.lon += d_lon_corr
        self.vn += dx[2]
        self.ve += dx[3]
        self.heading_rad += dx[4]
        self.est_accel_bias += dx[5]
        self.est_gyro_bias += dx[6]

        # Covariance update (Joseph form for numerical stability)
        I_KH = np.eye(7) - K @ H
        self.P = I_KH @ self.P @ I_KH.T + K @ self.R_gnss @ K.T

    def set_blackout(self, in_blackout: bool):
        """Toggle GNSS denied / blackout environment (e.g. tunnel entry)."""
        self.gnss_blackout_mode = in_blackout
