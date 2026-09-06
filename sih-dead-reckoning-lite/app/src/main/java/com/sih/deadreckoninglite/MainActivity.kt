package com.sih.deadreckoninglite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.sih.deadreckoninglite.databinding.ActivityMainBinding
import com.sih.deadreckoninglite.deadreckoning.TunnelSimulator
import com.sih.deadreckoninglite.location.GpsProvider
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.logging.SensorLogger
import com.sih.deadreckoninglite.map.MapController
import com.sih.deadreckoninglite.map.MapMatchingEngine
import com.sih.deadreckoninglite.ml.MlSpeedEstimator
import com.sih.deadreckoninglite.sensors.ImuManager
import com.sih.deadreckoninglite.sensors.OrientationCalibrator
import com.sih.deadreckoninglite.sensors.SensorSample
import com.sih.deadreckoninglite.ui.DriveLogActivity
import com.sih.deadreckoninglite.ui.MainViewModel
import com.sih.deadreckoninglite.ui.ThemeManager
import com.sih.deadreckoninglite.util.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Composition Root — the central wiring hub for the Dead Reckoning Lite prototype.
 *
 * ## Architecture
 * All domain modules ([ImuManager], [GpsProvider], [SensorLogger], [TunnelSimulator],
 * [MapController], [MainViewModel]) are isolated from each other. They do not hold
 * references to one another. **This Activity is the ONLY place** where data flows
 * between them — it receives callbacks from producers, routes data to consumers,
 * and pushes state changes into [MainViewModel] for the UI layer to observe.
 *
 * ## Data Flow Summary
 * ```
 * ImuManager ──callback──► MainActivity ──► SensorLogger.logImu()
 *                                      ──► MainViewModel.publishSample()
 *
 * GpsProvider ──callback──► MainActivity ──► SensorLogger.logGps()
 *                                       ──► MapController.moveVehicleTo() + addToRealPath()
 *                                       ──► MainViewModel.publishGps()
 *                                       ──► TunnelSimulator.onRealGpsSample()
 *
 * TunnelSimulator ──onProjectedPosition──► MainActivity ──► MapController.moveVehicleTo() + addToReckonedPath()
 *                                                      ──► MainViewModel.setDriftEstimateM()
 *
 * MainViewModel (LiveData) ──observe──► UI TextViews (mode, speed, lat, lon, accel, gyro, drift)
 * ```
 *
 * ## Threading
 * - IMU callback: sensor-delivery thread → post to main thread for UI updates
 * - GPS callback: main looper (configured in GpsProvider)
 * - TunnelSimulator ticker: main looper (configured in TunnelSimulator)
 * - SensorLogger: thread-safe, accepts calls from any thread
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ---- View Binding ----
    private lateinit var binding: ActivityMainBinding

    // ---- Domain Modules (created in onCreate) ----
    private lateinit var imuManager: ImuManager
    private lateinit var gpsProvider: GpsProvider
    private lateinit var sensorLogger: SensorLogger
    private lateinit var tunnelSimulator: TunnelSimulator
    private lateinit var mlEstimator: MlSpeedEstimator
    private lateinit var mapController: MapController
    private lateinit var viewModel: MainViewModel
    private lateinit var orientationCalibrator: OrientationCalibrator
    private lateinit var mapMatchingEngine: MapMatchingEngine

    // ---- Helpers ----
    /** Main-thread handler for posting UI updates from sensor thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** UTC time formatter for the position card. */
    private val utcFormat = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Tracks the last real GPS fix for drift calculation when exiting tunnel mode. */
    @Volatile
    private var lastRealGpsFix: GpsSample? = null

    // ---- Permission Launcher ----
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.i(TAG, "Location permission granted — starting sensors")
            startAllSensors()
        } else {
            Log.w(TAG, "Location permission denied — GPS will not be available")
            Toast.makeText(
                this,
                "Location permission is required for GPS tracking",
                Toast.LENGTH_LONG
            ).show()
            // Start IMU-only (GPS won't start)
            startImu()
            startCsvLogging()
        }
    }

    // ================================================================== //
    //  Lifecycle                                                          //
    // ================================================================== //

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme BEFORE super.onCreate() (ThemeManager requirement)
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.i(TAG, "onCreate — initializing composition root")

        // ---- Instantiate domain modules ----
        imuManager = ImuManager(this)
        gpsProvider = GpsProvider(this)
        sensorLogger = SensorLogger(this)
        tunnelSimulator = TunnelSimulator()
        mlEstimator = MlSpeedEstimator(this)
        tunnelSimulator.mlEstimator = mlEstimator
        mapController = MapController(binding.mapView)
        orientationCalibrator = OrientationCalibrator()
        mapMatchingEngine = MapMatchingEngine()
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // ---- Initialize map ----
        mapController.init()

        // ---- Wire TunnelSimulator output callback ----
        tunnelSimulator.onProjectedPosition = { lat, lon ->
            onTunnelProjection(lat, lon)
        }

        // ---- Set up UI interactions ----
        setupTunnelSwitch()
        setupMapButtons()
        setupThemeToggle()
        setupBottomNav()

        // ---- Observe LiveData → update UI ----
        observeViewModel()

        // ---- Check permissions and start sensors ----
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — stopping all modules")
        stopAllSensors()
        if (::mlEstimator.isInitialized) mlEstimator.close()
        super.onDestroy()
    }

    // ================================================================== //
    //  Permission Handling                                                //
    // ================================================================== //

    private fun checkAndRequestPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (fineLocation == PackageManager.PERMISSION_GRANTED ||
            coarseLocation == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Location permission already granted")
            startAllSensors()
        } else {
            Log.i(TAG, "Requesting location permissions")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // ================================================================== //
    //  Sensor Start / Stop                                                //
    // ================================================================== //

    /**
     * Start all sensor streams: IMU, GPS, and CSV logging.
     * Called after location permission is confirmed granted.
     */
    private fun startAllSensors() {
        startImu()
        startGps()
        startCsvLogging()
    }

    /**
     * Start IMU sensor with the routing callback.
     *
     * The callback fires on the sensor-delivery thread, so we:
     * 1. Feed SensorLogger immediately (it's thread-safe)
     * 2. Post ViewModel update to main thread
     */
    private fun startImu() {
        imuManager.start { sample: SensorSample ->
            // Route 1: Log to CSV (thread-safe, called from sensor thread)
            sensorLogger.logImu(sample)

            // Auto-calibration: process sample for mounting orientation & gyro bias
            orientationCalibrator.addSample(sample)
            if (orientationCalibrator.isCalibrated) {
                tunnelSimulator.gyroBiasZ = orientationCalibrator.gyroBiasZ
            }

            // Route 2: Push to ViewModel & ML estimator (must be on main thread)
            mainHandler.post {
                viewModel.publishSample(sample)

                // Coordinate alignment (Gap 2): Transform raw phone linear accel to vehicle forward/lateral/vertical
                val (fwdAccel, latAccel, vertAccel) = orientationCalibrator.transformToVehicleFrame(
                    sample.linearAx, sample.linearAy, sample.linearAz
                )

                // Feed gyro yaw rate for dynamic curve tracking (Gap 1)
                tunnelSimulator.onGyroSample(sample.gz)

                // Feed ML estimator with vehicle-aligned motion acceleration
                mlEstimator.addSample(
                    linearAx = latAccel,
                    linearAy = fwdAccel,
                    linearAz = vertAccel,
                    gx       = sample.gx,
                    gy       = sample.gy,
                    gz       = sample.gz
                )
                // Update ViewModel with ML state for UI/telemetry display
                viewModel.setMlSpeedMps(mlEstimator.predictSpeedMps())
                viewModel.setMlReady(mlEstimator.isReady())
            }
        }
        Log.i(TAG, "IMU started")
    }

    /**
     * Start GPS with the routing callback.
     *
     * GPS callback fires on the main looper (configured in GpsProvider),
     * so all downstream calls here are already on the main thread.
     */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        val success = gpsProvider.start { sample: GpsSample ->
            onGpsFix(sample)
        }

        if (!success) {
            Log.e(TAG, "GpsProvider.start() returned false — GPS is unavailable")
            Toast.makeText(this, "GPS unavailable on this device", Toast.LENGTH_LONG).show()
        } else {
            Log.i(TAG, "GPS started")
        }
    }

    /**
     * Start CSV logging session.
     */
    private fun startCsvLogging() {
        sensorLogger.start()
        viewModel.setCsvLoggingActive(true)
        Log.i(TAG, "CSV logging started")
    }

    /**
     * Stop all sensor streams cleanly.
     */
    private fun stopAllSensors() {
        imuManager.stop()
        gpsProvider.stop()
        tunnelSimulator.setActive(false)
        sensorLogger.stop()
        viewModel.setCsvLoggingActive(false)
        Log.i(TAG, "All sensors stopped")
    }

    // ================================================================== //
    //  GPS Fix Routing (Main Thread)                                      //
    // ================================================================== //

    /**
     * Central GPS fix handler — called by GpsProvider callback on main thread.
     *
     * Routes the fix to all consumers:
     * 1. SensorLogger — log to CSV
     * 2. TunnelSimulator — always feed real GPS (even while tunnel is active)
     * 3. MapController — update position on map (only when NOT in tunnel mode)
     * 4. MainViewModel — update UI state
     * 5. Update UTC time display
     */
    private fun onGpsFix(sample: GpsSample) {
        // Store for drift calculation
        lastRealGpsFix = sample

        // Route 1: CSV logging (thread-safe)
        sensorLogger.logGps(sample)

        // Route 2: Always feed tunnel simulator (stores latest fix)
        tunnelSimulator.onRealGpsSample(sample)

        // Dynamically build road corridor segments from preceding GPS fixes for map matching
        lastRealGpsFix?.let { prevFix ->
            if (prevFix.distanceTo(sample.latDeg, sample.lonDeg) > 2.0) {
                mapMatchingEngine.addCorridorSegment(prevFix.latDeg, prevFix.lonDeg, sample.latDeg, sample.lonDeg)
            }
        }

        // Route 3: Update map (only if NOT in tunnel/DR mode)
        if (!tunnelSimulator.isActive) {
            mapController.moveVehicleTo(sample.latDeg, sample.lonDeg)
            mapController.addToRealPath(sample.latDeg, sample.lonDeg)
            if (sample.accuracyM > 0) {
                binding.gnssLockText.text = "GNSS LOCK : 3D (±%.0fm)".format(sample.accuracyM)
            } else {
                binding.gnssLockText.text = "GNSS LOCK : 3D"
            }
            setDotColor(binding.gnssLockDot, Constants.GNSS_BADGE_COLOR)
        }

        // Route 4: Update ViewModel
        viewModel.publishGps(sample)

        // Route 5: Update UTC time
        binding.utcTime.text = utcFormat.format(Date())

        // Route 6: Update speed display (m/s → km/h) with stationary noise deadband (< 0.35 m/s)
        val speedKmh = if (sample.speedMps < 0.35f) 0.0f else sample.speedMps * 3.6f
        binding.speedValue.text = "%.1f".format(speedKmh)
    }

    // ================================================================== //
    //  Tunnel Simulator Projection Callback (Main Thread)                 //
    // ================================================================== //

    /**
     * Called by TunnelSimulator's ~1 Hz ticker with projected (lat, lon).
     * Runs on main thread (Handler in TunnelSimulator uses main looper).
     *
     * Routes projected position to:
     * 1. MapController — move vehicle marker + add to reckoned path
     * 2. MainViewModel — update drift estimate
     * 3. Update lat/lon display
     */
    private fun onTunnelProjection(lat: Double, lon: Double) {
        // Map Matching (Gap 3): Snap raw dead-reckoned point to nearest corridor centerline
        val (matchedLat, matchedLon) = mapMatchingEngine.snapToRoad(lat, lon)

        // Route 1: Update map with matched position
        mapController.moveVehicleTo(matchedLat, matchedLon)
        mapController.addToReckonedPath(matchedLat, matchedLon)

        // Route 2: Calculate and publish drift estimate
        val fix = lastRealGpsFix
        if (fix != null) {
            val driftMeters = fix.distanceTo(matchedLat, matchedLon).toFloat()
            viewModel.setDriftEstimateM(driftMeters)
        }

        // Route 3: Update lat/lon display with matched coordinates
        binding.latitudeValue.text = "%.6f".format(matchedLat)
        binding.longitudeValue.text = "%.6f".format(matchedLon)
    }

    // ================================================================== //
    //  UI Interactions                                                     //
    // ================================================================== //

    /**
     * Wire the "Simulate Tunnel" MaterialSwitch to the TunnelSimulator.
     */
    private fun setupTunnelSwitch() {
        binding.tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "Tunnel switch toggled: $isChecked")

            tunnelSimulator.setActive(isChecked)

            if (isChecked) {
                // Entering Dead Reckoning mode
                viewModel.setMode(MainViewModel.Mode.DEAD_RECKONING)
                binding.gnssLockText.text = "GNSS LOST : DR ACTIVE"
                setDotColor(binding.gnssLockDot, Constants.DR_BADGE_COLOR)
                Log.i(TAG, "Mode → DEAD_RECKONING")
            } else {
                // Returning to GNSS mode
                viewModel.setMode(MainViewModel.Mode.GNSS)
                viewModel.setDriftEstimateM(0f) // Reset drift
                binding.gnssLockText.text = "GNSS LOCK : ACQUIRING..."
                setDotColor(binding.gnssLockDot, Color.GRAY)

                // Snap map back to last real GPS position
                val fix = lastRealGpsFix
                if (fix != null) {
                    mapController.moveVehicleTo(fix.latDeg, fix.lonDeg)
                }
                Log.i(TAG, "Mode → GNSS")
            }
        }
    }

    /**
     * Wire map zoom/recenter buttons.
     */
    private fun setupMapButtons() {
        binding.btnZoomIn.setOnClickListener { mapController.zoomIn() }
        binding.btnZoomOut.setOnClickListener { mapController.zoomOut() }
        binding.btnRecenter.setOnClickListener { mapController.recenter() }
    }

    /**
     * Wire theme toggle button.
     */
    private fun setupThemeToggle() {
        binding.btnThemeToggle.setOnClickListener {
            ThemeManager.toggleTheme(this)
            // Activity will be recreated by AppCompatDelegate
        }
    }

    /**
     * Wire bottom navigation bar tabs.
     */
    private fun setupBottomNav() {
        // Dashboard tab — active on MainActivity
        binding.navDashboard.isSelected = true
        binding.navDashboard.setOnClickListener {
            // Already on dashboard — no-op
            Log.d(TAG, "Dashboard tab clicked (already here)")
        }

        // Logs tab → DriveLogActivity
        binding.navLogs.setOnClickListener {
            Log.d(TAG, "Logs tab clicked → navigating to DriveLogActivity")
            startActivity(Intent(this, DriveLogActivity::class.java))
        }

        // About tab → ISRO SIH project dialog
        binding.navAbout.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name_full)
                .setMessage("ISRO SIH PS 26168 Prototype\nVersion 1.0-prototype\n\nIntelligent Dead Reckoning (DR) with high-frequency IMU logging and constant-velocity fallback for GNSS-denied environments.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // ================================================================== //
    //  LiveData Observers → UI Updates                                    //
    // ================================================================== //

    /**
     * Observe all [MainViewModel] LiveData fields and update the corresponding
     * UI elements when values change.
     */
    private fun observeViewModel() {
        // ---- Mode (GNSS / DEAD_RECKONING) ----
        viewModel.currentMode.observe(this) { mode ->
            when (mode) {
                MainViewModel.Mode.GNSS -> {
                    binding.modeText.text = getString(R.string.mode_gnss) + " (10Hz)"
                    binding.modeText.setTextColor(
                        ContextCompat.getColor(this, R.color.gnss_green)
                    )
                    setDotColor(binding.modeDot, Constants.GNSS_BADGE_COLOR)
                    setDotColor(binding.headerDrDot, Constants.GNSS_BADGE_COLOR)
                    binding.headerDrText.text = getString(R.string.mode_gnss) + " (10Hz)"
                }
                MainViewModel.Mode.DEAD_RECKONING -> {
                    binding.modeText.text = getString(R.string.mode_dead_reckoning) + " (10Hz ML)"
                    binding.modeText.setTextColor(
                        ContextCompat.getColor(this, R.color.dead_reckoning_amber)
                    )
                    setDotColor(binding.modeDot, Constants.DR_BADGE_COLOR)
                    setDotColor(binding.headerDrDot, Constants.DR_BADGE_COLOR)
                    binding.headerDrText.text = getString(R.string.dr_active) + " (10Hz)"
                }
                null -> { /* no-op */ }
            }
        }

        // ---- IMU Sample (accel + gyro values) ----
        viewModel.latestSample.observe(this) { sample ->
            if (sample != null) {
                binding.accelX.text = "%.2f".format(sample.ax)
                binding.accelY.text = "%.2f".format(sample.ay)
                binding.accelZ.text = "%.2f".format(sample.az)
                binding.gyroX.text = "%.2f".format(sample.gx)
                binding.gyroY.text = "%.2f".format(sample.gy)
                binding.gyroZ.text = "%.2f".format(sample.gz)
            }
        }

        // ---- GPS Sample (lat, lon, speed) ----
        viewModel.latestGps.observe(this) { gps ->
            if (gps != null && !tunnelSimulator.isActive) {
                binding.latitudeValue.text = "%.6f".format(gps.latDeg)
                binding.longitudeValue.text = "%.6f".format(gps.lonDeg)

                val speedKmh = if (gps.speedMps < 0.35f) 0.0f else gps.speedMps * 3.6f
                binding.speedValue.text = "%.1f".format(speedKmh)
            }
        }

        // ---- ML Speed (used in DR mode) ----
        viewModel.mlSpeedMps.observe(this) { mlSpeed ->
            if (mlSpeed != null && tunnelSimulator.isActive) {
                val speedKmh = mlSpeed * 3.6f
                binding.speedValue.text = "%.1f".format(speedKmh)
            }
        }

        // ---- Drift Estimate ----
        viewModel.driftEstimateM.observe(this) { driftM ->
            binding.driftValue.text = "%.1f m".format(driftM)

            // Color-code the drift indicator dot
            val driftColor = when {
                driftM < 10f -> Constants.GNSS_BADGE_COLOR   // Green: low drift
                driftM < 50f -> Constants.DR_BADGE_COLOR     // Amber: moderate drift
                else -> Color.RED                             // Red: high drift
            }
            setDotColor(binding.driftDot, driftColor)
        }

        // ---- CSV Logging Status ----
        viewModel.csvLoggingActive.observe(this) { active ->
            if (active) {
                binding.csvLogText.text = getString(R.string.csv_log_active)
                setDotColor(binding.csvLogDot, Constants.GNSS_BADGE_COLOR) // Green = active
            } else {
                binding.csvLogText.text = getString(R.string.csv_log_inactive)
                setDotColor(binding.csvLogDot, Color.GRAY)
            }
        }
    }

    // ================================================================== //
    //  Utility                                                            //
    // ================================================================== //

    /**
     * Set the background color of a small dot [View] that uses a shape drawable.
     * Works with both [GradientDrawable] (from XML shapes like dot_gnss.xml)
     * and falls back to setting the background tint directly.
     */
    private fun setDotColor(dotView: View, color: Int) {
        val bg = dotView.background
        if (bg is GradientDrawable) {
            bg.mutate()
            bg.setColor(color)
        } else {
            dotView.setBackgroundColor(color)
        }
    }
}
