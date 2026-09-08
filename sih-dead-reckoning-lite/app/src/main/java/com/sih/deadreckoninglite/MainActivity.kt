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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.sih.deadreckoninglite.databinding.ActivityMainBinding
import com.sih.deadreckoninglite.deadreckoning.DeadReckoningEngine
import com.sih.deadreckoninglite.location.GnssQualityMonitor
import com.sih.deadreckoninglite.location.GpsProvider
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.location.LocationCache
import com.sih.deadreckoninglite.location.NavigationMode
import com.sih.deadreckoninglite.logging.SensorLogger
import com.sih.deadreckoninglite.map.MapController
import com.sih.deadreckoninglite.sensors.ImuManager
import com.sih.deadreckoninglite.sensors.SensorFilter
import com.sih.deadreckoninglite.sensors.SensorSample
import com.sih.deadreckoninglite.sensors.SpeedFilter
import com.sih.deadreckoninglite.ui.DriveLogActivity
import com.sih.deadreckoninglite.ui.MainViewModel
import com.sih.deadreckoninglite.ui.ThemeManager
import com.sih.deadreckoninglite.util.Constants
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Composition Root — central integration hub for the Dead Reckoning Lite system.
 *
 * ## Architecture
 * All domain modules ([ImuManager], [GpsProvider], [SensorLogger], [GnssQualityMonitor],
 * [DeadReckoningEngine], [SensorFilter], [SpeedFilter], [LocationCache], [MapController],
 * [MainViewModel]) are completely decoupled.
 *
 * This Activity coordinates data flow:
 * - High-frequency 50 Hz IMU data -> filtered via [SensorFilter] & logged to CSV
 * - UI sensor updates throttled to 10 Hz (no flickering/glitches)
 * - Zero-velocity update (ZUPT) clamping speed & rotation when stationary
 * - Automatic GNSS outage detection via [GnssQualityMonitor]
 * - Instant offline startup via [LocationCache]
 * - Bias-corrected, non-circular path dead reckoning via [DeadReckoningEngine]
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ---- View Binding ----
    private lateinit var binding: ActivityMainBinding

    // ---- Domain Modules ----
    private lateinit var imuManager: ImuManager
    private lateinit var gpsProvider: GpsProvider
    private lateinit var sensorLogger: SensorLogger
    private lateinit var gnssQualityMonitor: GnssQualityMonitor
    private lateinit var deadReckoningEngine: DeadReckoningEngine
    private lateinit var sensorFilter: SensorFilter
    private lateinit var speedFilter: SpeedFilter
    private lateinit var locationCache: LocationCache
    private lateinit var mapController: MapController
    private lateinit var viewModel: MainViewModel

    // ---- Helpers & Throttling ----
    private val mainHandler = Handler(Looper.getMainLooper())

    private val utcFormat = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var lastRealGpsFix: GpsSample? = null

    @Volatile
    private var lastUiUpdateMs: Long = 0L

    @Volatile
    private var lastImuTimestampNs: Long = 0L

    // ---- Permission Launcher ----
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.i(TAG, "Location permission granted — starting all sensors")
            startAllSensors()
        } else {
            Log.w(TAG, "Location permission denied — operating in standalone offline DR mode")
            Toast.makeText(
                this,
                "Location unavailable — operating in offline Dead Reckoning mode",
                Toast.LENGTH_LONG
            ).show()
            startImu()
            startCsvLogging()
            onNavigationModeChanged(NavigationMode.OFFLINE_STANDALONE)
        }
    }

    // ================================================================== //
    //  Lifecycle                                                          //
    // ================================================================== //

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // ---- Configure osmdroid and ensure offline tiles are available before MapView inflates ----
        val basePath = File(filesDir, "osmdroid")
        basePath.mkdirs()
        val osmConfig = Configuration.getInstance()
        osmConfig.load(this, PreferenceManager.getDefaultSharedPreferences(this))
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = File(basePath, "tiles")
        osmConfig.userAgentValue = "DeadReckoningLite/1.0 (com.sih.deadreckoninglite; SIH-PS-26168)"
        osmConfig.cacheMapTileCount = 1200
        osmConfig.cacheMapTileOvershoot = 500
        osmConfig.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 60L
        MapController.copyOfflineAssetsIfNeeded(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.i(TAG, "onCreate — initializing composition root")

        // ---- Instantiate domain modules ----
        imuManager = ImuManager(this)
        gpsProvider = GpsProvider(this)
        sensorLogger = SensorLogger(this)
        deadReckoningEngine = DeadReckoningEngine()
        sensorFilter = SensorFilter()
        speedFilter = SpeedFilter()
        locationCache = LocationCache(this)
        mapController = MapController(binding.mapView)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        gnssQualityMonitor = GnssQualityMonitor(this) { mode ->
            onNavigationModeChanged(mode)
        }

        // ---- Initialize map and seed position immediately from offline cache ----
        mapController.init()
        val (seedLat, seedLon, seedBearing) = locationCache.getLastKnownPosition()
        mapController.seedInitialPosition(seedLat, seedLon)
        binding.latitudeValue.text = "%.7f".format(seedLat)
        binding.longitudeValue.text = "%.7f".format(seedLon)
        deadReckoningEngine.setOrigin(seedLat, seedLon, seedBearing, 0f)

        // ---- Set up UI interactions ----
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
        mapController.updateDataConnectionMode()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — stopping all modules")
        stopAllSensors()
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

    private fun startAllSensors() {
        startImu()
        startGps()
        startCsvLogging()
        gnssQualityMonitor.start()
    }

    /**
     * Start IMU sampling:
     * 1. 50 Hz raw sample logged to CSV.
     * 2. Sample filtered via [SensorFilter] (EMA low-pass, stationary ZUPT, gyro bias).
     * 3. If in Dead Reckoning mode, project position forward and update map.
     * 4. UI text updates throttled to 10 Hz (100ms) to prevent screen flickering.
     */
    private fun startImu() {
        imuManager.start { sample: SensorSample ->
            // Route 1: Log raw unadulterated 50 Hz sample for CSV export
            sensorLogger.logImu(sample)

            // Route 2: Process through SensorFilter (EMA + ZUPT stationary detection)
            val filtered = sensorFilter.filter(sample)

            // Route 3: If in Dead Reckoning or Offline mode, project position step
            val mode = gnssQualityMonitor.currentMode
            if (mode == NavigationMode.DEAD_RECKONING_AUTO || mode == NavigationMode.OFFLINE_STANDALONE) {
                val dtSec = if (lastImuTimestampNs > 0L) {
                    (sample.timestampNs - lastImuTimestampNs) / 1_000_000_000.0
                } else {
                    0.02
                }
                lastImuTimestampNs = sample.timestampNs

                val speedMps = speedFilter.stepDrSpeed(dtSec, filtered.isStationary)
                deadReckoningEngine.updateSpeed(speedMps)
                val (drLat, drLon) = deadReckoningEngine.step(filtered, dtSec)

                mainHandler.post {
                    mapController.moveVehicleTo(drLat, drLon)
                    // Only draw reckoned path if the vehicle is confirmed in motion (speed > 1.5 km/h)
                    if (!filtered.isStationary && speedFilter.speedKmh > 1.5f) {
                        mapController.addToReckonedPath(drLat, drLon)
                    }
                    binding.latitudeValue.text = "%.7f".format(drLat)
                    binding.longitudeValue.text = "%.7f".format(drLon)
                    viewModel.setDriftEstimateM(deadReckoningEngine.estimatedDriftM)
                    binding.speedValue.text = "%.1f".format(speedFilter.speedKmh)
                }
            }

            // Route 4: Throttle UI text display to 10 Hz (~100ms)
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastUiUpdateMs >= Constants.UI_UPDATE_INTERVAL_MS) {
                lastUiUpdateMs = nowMs
                mainHandler.post {
                    viewModel.publishSample(
                        SensorSample(
                            timestampNs = filtered.timestampNs,
                            ax = filtered.ax,
                            ay = filtered.ay,
                            az = filtered.az,
                            gx = filtered.gx,
                            gy = filtered.gy,
                            gz = filtered.gz
                        )
                    )

                    if (mode == NavigationMode.GNSS_LOCK || mode == NavigationMode.GNSS_DEGRADED) {
                        binding.speedValue.text = "%.1f".format(speedFilter.speedKmh)
                    }
                }
            }
        }
        Log.i(TAG, "IMU started with 10 Hz UI throttling and ZUPT filter")
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val success = gpsProvider.start { sample: GpsSample ->
            onGpsFix(sample)
        }

        if (!success) {
            Log.w(TAG, "GpsProvider unavailable — operating in offline mode")
            onNavigationModeChanged(NavigationMode.OFFLINE_STANDALONE)
        } else {
            Log.i(TAG, "GPS started")
        }
    }

    private fun startCsvLogging() {
        sensorLogger.start()
        viewModel.setCsvLoggingActive(true)
        Log.i(TAG, "CSV logging started")
    }

    private fun stopAllSensors() {
        imuManager.stop()
        gpsProvider.stop()
        gnssQualityMonitor.stop()
        sensorLogger.stop()
        viewModel.setCsvLoggingActive(false)
        Log.i(TAG, "All sensors stopped")
    }

    // ================================================================== //
    //  GPS Fix Routing (Main Thread)                                      //
    // ================================================================== //

    private fun onGpsFix(sample: GpsSample) {
        lastRealGpsFix = sample

        // Route 1: CSV logging
        sensorLogger.logGps(sample)

        // Route 2: Feed autonomous GNSS monitor (resets watchdog, checks health)
        gnssQualityMonitor.onGpsSampleReceived(sample)

        // Route 3: Update persistent cache for offline restarts
        locationCache.saveLastKnownPosition(
            sample.latDeg, sample.lonDeg, sample.bearingDeg, sample.speedMps
        )

        // Route 4: Update speed filter
        speedFilter.onGpsSpeed(sample.speedMps, sensorFilter.isStationary)

        // Route 5: Re-seed Dead Reckoning engine origin to latest GPS fix
        deadReckoningEngine.setOrigin(
            sample.latDeg, sample.lonDeg, sample.bearingDeg, speedFilter.speedMps
        )

        // Route 6: Update map & UI if in GNSS mode
        val mode = gnssQualityMonitor.currentMode
        if (mode == NavigationMode.GNSS_LOCK || mode == NavigationMode.GNSS_DEGRADED) {
            // Wipe out any old DR path so no yellow lines linger
            mapController.clearReckonedPath()
            mapController.moveVehicleTo(sample.latDeg, sample.lonDeg)
            // Only add to real path if in actual motion
            if (speedFilter.speedKmh > 1.5f && !sensorFilter.isStationary) {
                mapController.addToRealPath(sample.latDeg, sample.lonDeg)
            }
            binding.latitudeValue.text = "%.7f".format(sample.latDeg)
            binding.longitudeValue.text = "%.7f".format(sample.lonDeg)
            viewModel.setDriftEstimateM(0f)
            binding.speedValue.text = "%.1f".format(speedFilter.speedKmh)

            if (sample.accuracyM > 0f && sample.accuracyM < 100f) {
                binding.gnssLockText.text = "GNSS LOCK : 3D (±%.0fm)".format(sample.accuracyM)
            } else {
                binding.gnssLockText.text = "GNSS LOCK : 3D"
            }
            setDotColor(binding.gnssLockDot, Constants.GNSS_BADGE_COLOR)
        }

        // Route 7: ViewModel & UTC time
        viewModel.publishGps(sample)
        binding.utcTime.text = utcFormat.format(Date())
    }

    // ================================================================== //
    //  Autonomous Mode Transitions                                        //
    // ================================================================== //

    /**
     * Triggered automatically by [GnssQualityMonitor] without any manual toggle.
     */
    private fun onNavigationModeChanged(mode: NavigationMode) {
        runOnUiThread {
            mapController.updateDataConnectionMode()
            when (mode) {
                NavigationMode.GNSS_LOCK -> {
                    mapController.clearReckonedPath()
                    viewModel.setMode(MainViewModel.Mode.GNSS)
                    binding.gnssLockText.text = "GNSS LOCK : 3D"
                    setDotColor(binding.gnssLockDot, Constants.GNSS_BADGE_COLOR)
                    binding.autoNavStatusText.text = "GNSS 3D"
                    binding.autoNavStatusText.setTextColor(
                        ContextCompat.getColor(this, R.color.gnss_green)
                    )
                    setDotColor(binding.autoNavPulseDot, Constants.GNSS_BADGE_COLOR)
                    binding.headerDrText.text = getString(R.string.mode_gnss)
                    setDotColor(binding.headerDrDot, Constants.GNSS_BADGE_COLOR)
                    Log.i(TAG, "Mode -> GNSS_LOCK (Active Satellite Tracking)")
                }
                NavigationMode.GNSS_DEGRADED -> {
                    binding.gnssLockText.text = "GNSS DEGRADED : HIGH DOP"
                    setDotColor(binding.gnssLockDot, Constants.DR_BADGE_COLOR)
                    binding.autoNavStatusText.text = "DEGRADED"
                    binding.autoNavStatusText.setTextColor(
                        ContextCompat.getColor(this, R.color.dead_reckoning_amber)
                    )
                    setDotColor(binding.autoNavPulseDot, Constants.DR_BADGE_COLOR)
                    Log.i(TAG, "Mode -> GNSS_DEGRADED")
                }
                NavigationMode.DEAD_RECKONING_AUTO -> {
                    // Reset DR path so it starts fresh from current vehicle location
                    mapController.clearReckonedPath()
                    val curFix = lastRealGpsFix
                    if (curFix != null) {
                        deadReckoningEngine.setOrigin(
                            curFix.latDeg, curFix.lonDeg, curFix.bearingDeg, speedFilter.speedMps
                        )
                    }
                    viewModel.setMode(MainViewModel.Mode.DEAD_RECKONING)
                    binding.gnssLockText.text = "GNSS LOST : DR ACTIVE"
                    setDotColor(binding.gnssLockDot, Constants.DR_BADGE_COLOR)
                    binding.autoNavStatusText.text = "DR ACTIVE"
                    binding.autoNavStatusText.setTextColor(
                        ContextCompat.getColor(this, R.color.dead_reckoning_amber)
                    )
                    setDotColor(binding.autoNavPulseDot, Constants.DR_BADGE_COLOR)
                    binding.headerDrText.text = getString(R.string.dr_active)
                    setDotColor(binding.headerDrDot, Constants.DR_BADGE_COLOR)
                    Log.i(TAG, "Mode -> DEAD_RECKONING_AUTO (Autonomous Outage Fallback)")
                }
                NavigationMode.OFFLINE_STANDALONE -> {
                    mapController.clearReckonedPath()
                    viewModel.setMode(MainViewModel.Mode.DEAD_RECKONING)
                    binding.gnssLockText.text = "OFFLINE : DR ACTIVE"
                    val cyanColor = Color.parseColor("#00E5FF")
                    setDotColor(binding.gnssLockDot, cyanColor)
                    binding.autoNavStatusText.text = "OFFLINE DR"
                    binding.autoNavStatusText.setTextColor(cyanColor)
                    setDotColor(binding.autoNavPulseDot, cyanColor)
                    binding.headerDrText.text = "OFFLINE DR"
                    setDotColor(binding.headerDrDot, cyanColor)
                    Log.i(TAG, "Mode -> OFFLINE_STANDALONE (No GPS Provider)")
                }
            }
        }
    }

    // ================================================================== //
    //  UI Interactions                                                     //
    // ================================================================== //

    private fun setupMapButtons() {
        binding.btnZoomIn.setOnClickListener { mapController.zoomIn() }
        binding.btnZoomOut.setOnClickListener { mapController.zoomOut() }
        binding.btnRecenter.setOnClickListener { mapController.recenter() }
        binding.btnReloadMap.setOnClickListener {
            mapController.reloadMap()
            Toast.makeText(this, "Map reloaded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupThemeToggle() {
        binding.btnThemeToggle.setOnClickListener {
            ThemeManager.toggleTheme(this)
        }
    }

    private fun setupBottomNav() {
        binding.navDashboard.isSelected = true
        binding.navDashboard.setOnClickListener {
            Log.d(TAG, "Dashboard tab clicked (already here)")
        }

        binding.navLogs.setOnClickListener {
            startActivity(Intent(this, DriveLogActivity::class.java))
        }

        binding.navAbout.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name_full)
                .setMessage("ISRO SIH PS 26168 Prototype\nVersion 1.0-prototype\n\nIntelligent Autonomous Dead Reckoning (DR) with high-frequency IMU logging, online bias calibration, and smooth no-circle fallback for GNSS-denied environments.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // ================================================================== //
    //  LiveData Observers → UI Updates                                    //
    // ================================================================== //

    private fun observeViewModel() {
        viewModel.currentMode.observe(this) { mode ->
            when (mode) {
                MainViewModel.Mode.GNSS -> {
                    binding.modeText.text = getString(R.string.mode_gnss)
                    binding.modeText.setTextColor(
                        ContextCompat.getColor(this, R.color.gnss_green)
                    )
                    setDotColor(binding.modeDot, Constants.GNSS_BADGE_COLOR)
                }
                MainViewModel.Mode.DEAD_RECKONING -> {
                    binding.modeText.text = getString(R.string.mode_dead_reckoning)
                    binding.modeText.setTextColor(
                        ContextCompat.getColor(this, R.color.dead_reckoning_amber)
                    )
                    setDotColor(binding.modeDot, Constants.DR_BADGE_COLOR)
                }
                null -> { /* no-op */ }
            }
        }

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

        viewModel.driftEstimateM.observe(this) { driftM ->
            binding.driftValue.text = "%.1f m".format(driftM)

            val driftColor = when {
                driftM < 10f -> Constants.GNSS_BADGE_COLOR   // Green: minimal uncertainty
                driftM < 25f -> Constants.DR_BADGE_COLOR     // Amber: moderate uncertainty
                else -> Color.RED                             // Red: elevated uncertainty
            }
            setDotColor(binding.driftDot, driftColor)
        }

        viewModel.csvLoggingActive.observe(this) { active ->
            if (active) {
                binding.csvLogText.text = getString(R.string.csv_log_active)
                setDotColor(binding.csvLogDot, Constants.GNSS_BADGE_COLOR)
            } else {
                binding.csvLogText.text = getString(R.string.csv_log_inactive)
                setDotColor(binding.csvLogDot, Color.GRAY)
            }
        }
    }

    // ================================================================== //
    //  Utility                                                            //
    // ================================================================== //

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
