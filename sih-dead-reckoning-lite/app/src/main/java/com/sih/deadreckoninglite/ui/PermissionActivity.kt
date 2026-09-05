package com.sih.deadreckoninglite.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sih.deadreckoninglite.MainActivity
import com.sih.deadreckoninglite.R

/**
 * Permission request screen — shown when ACCESS_FINE_LOCATION
 * has not yet been granted.
 *
 * Displays a clear rationale for why location + sensor access is needed,
 * with technical details (IMU poll rate, GNSS correction mode) styled
 * to match the DR Lite design language.
 *
 * On permission grant → navigates to [MainActivity].
 * On denial → shows a toast and stays on this screen.
 */
class PermissionActivity : AppCompatActivity() {

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            navigateToMain()
        } else {
            Toast.makeText(
                this,
                getString(R.string.permission_denied_message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        // If permission already granted (e.g. navigated back), go straight to main
        if (hasLocationPermission()) {
            navigateToMain()
            return
        }

        // Grant Permission button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_grant_permission)
            .setOnClickListener {
                requestLocationPermission()
            }

        // "Why do you need this?" — simple toast explanation for now
        findViewById<android.widget.TextView>(R.id.btn_why_permission)
            .setOnClickListener {
                Toast.makeText(
                    this,
                    getString(R.string.permission_rationale),
                    Toast.LENGTH_LONG
                ).show()
            }

        // Theme toggle
        findViewById<android.widget.ImageButton>(R.id.btn_theme_toggle)
            .setOnClickListener {
                ThemeManager.toggleTheme(this)
            }

        // Bottom navigation
        setupBottomNav()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun setupBottomNav() {
        // Dashboard tab (current conceptual location)
        findViewById<android.view.View>(R.id.nav_dashboard)?.apply {
            isSelected = true
            setOnClickListener {
                // Already here (permission gate for dashboard)
            }
        }

        // Logs tab
        findViewById<android.view.View>(R.id.nav_logs)?.setOnClickListener {
            if (hasLocationPermission()) {
                startActivity(Intent(this, DriveLogActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                Toast.makeText(this, "Grant permission first", Toast.LENGTH_SHORT).show()
            }
        }

        // About tab — no-op for prototype
        findViewById<android.view.View>(R.id.nav_about)?.setOnClickListener {
            Toast.makeText(this, "About — coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
