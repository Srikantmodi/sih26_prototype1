"""
SIH PS-26168: High-Precision Tactical FOG (Fiber Optic Gyroscope) IMU Simulator
Streams simulated tactical-grade IMU samples at 200 Hz (dt = 0.005s = 5ms).

FOG characteristics modeled:
- Gyro Angle Random Walk (ARW): ~0.005 deg/sqrt(hr) (Tactical FOG grade)
- Gyro In-Run Bias Stability: 0.01 deg/hr (vs MEMS which is 10-50 deg/hr)
- Accelerometer Velocity Random Walk (VRW): 30 ug/sqrt(Hz)
- Accelerometer Bias Stability: 50 ug
"""

import math
import numpy as np

class FogSensorSimulator:
    def __init__(self, init_heading_deg: float = 0.0, init_speed_mps: float = 0.0, sample_rate_hz: float = 200.0, seed: int = 42):
        self.sample_rate_hz = sample_rate_hz
        self.dt = 1.0 / sample_rate_hz  # 5 ms at 200 Hz
        self.rng = np.random.RandomState(seed)

        # Tactical FOG specifications
        # Gyro: 0.005 deg/sqrt(hr) -> rad/sqrt(s)
        self.gyro_noise_std = math.radians(0.005 / 60.0) * math.sqrt(sample_rate_hz)
        # Gyro bias: 0.01 deg/hr in rad/s
        self.gyro_bias = math.radians(0.01 / 3600.0)

        # Navigation-grade Accel: 30 ug/sqrt(Hz) -> m/s^2/sqrt(Hz)
        self.accel_noise_std = (30e-6 * 9.80665) * math.sqrt(sample_rate_hz)
        self.accel_bias = 50e-6 * 9.80665

        # Vehicle state ground truth
        self.t = 0.0
        self.true_speed_mps = init_speed_mps
        self.true_heading_rad = math.radians(init_heading_deg)

    def generate_sample(self, target_speed_mps: float, turn_rate_rads: float = 0.0):
        """
        Generate one synthetic 200 Hz FOG IMU reading.
        Returns:
            (forward_accel_meas, yaw_rate_meas, dt, true_speed_mps, true_heading_rad)
        """
        # Dynamics tracking target speed
        accel_target = (target_speed_mps - self.true_speed_mps) / 0.5
        accel_target = max(-4.0, min(3.0, accel_target))

        # Ground truth kinematic update
        self.true_speed_mps += accel_target * self.dt
        if self.true_speed_mps < 0.0:
            self.true_speed_mps = 0.0
        self.true_heading_rad += turn_rate_rads * self.dt
        self.true_heading_rad = (self.true_heading_rad + math.pi) % (2.0 * math.pi) - math.pi

        # Sensor measurements with FOG physical noise & bias characteristics
        gyro_noise = self.rng.normal(0.0, self.gyro_noise_std)
        accel_noise = self.rng.normal(0.0, self.accel_noise_std)

        yaw_rate_meas = turn_rate_rads + self.gyro_bias + gyro_noise
        forward_accel_meas = accel_target + self.accel_bias + accel_noise

        self.t += self.dt
        return forward_accel_meas, yaw_rate_meas, self.dt, self.true_speed_mps, self.true_heading_rad
