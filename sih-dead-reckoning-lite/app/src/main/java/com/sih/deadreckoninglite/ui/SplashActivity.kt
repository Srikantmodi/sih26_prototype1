package com.sih.deadreckoninglite.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sih.deadreckoninglite.MainActivity
import com.sih.deadreckoninglite.R

/**
 * Splash screen — the app entry point.
 *
 * Displays the DR LITE branding, hardware handshake status animation,
 * and buffer load progress. After a short delay or when the user taps
 * "INITIALIZE CONSOLE", navigates to either:
 * - [PermissionActivity] if location permission hasn't been granted
 * - [MainActivity] if permission is already granted
 *
 * Always uses the dark theme ([R.style.Theme_DRLite_Splash]) for
 * consistent brand presentation regardless of the user's theme preference.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash always dark — theme is set in manifest
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Animate content entrance
        val rootContent = findViewById<android.view.View>(android.R.id.content)
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 600 }
        val slideUp = TranslateAnimation(0f, 0f, 60f, 0f).apply { duration = 600 }
        val animSet = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(slideUp)
        }
        rootContent.startAnimation(animSet)

        // "INITIALIZE CONSOLE" button — immediate navigation
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_initialize)
            .setOnClickListener {
                navigateNext()
            }

        // Auto-navigate after 3 seconds if user doesn't tap
        handler.postDelayed({
            navigateNext()
        }, 3000L)
    }

    private fun navigateNext() {
        if (hasNavigated) return
        hasNavigated = true

        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val target = if (hasPermission) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, PermissionActivity::class.java)
        }

        startActivity(target)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
