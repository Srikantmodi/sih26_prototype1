package com.sih.deadreckoninglite.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sih.deadreckoninglite.MainActivity
import com.sih.deadreckoninglite.R
import com.sih.deadreckoninglite.logging.CsvExporter
import com.sih.deadreckoninglite.util.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drive Log History screen — lists all CSV log files recorded by the app.
 *
 * Shows summary statistics (total logs, storage used, total recording time),
 * a search/filter bar, and a scrollable list of drive sessions with metadata
 * (filename, date, row count, file size, status badge).
 *
 * Each entry has a CSV export button that triggers [CsvExporter.shareLatestLog].
 *
 * Navigates between screens via the bottom navigation bar.
 */
class DriveLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var adapter: DriveLogAdapter
    private var allLogEntries = mutableListOf<LogEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drive_log)

        recyclerView = findViewById(R.id.recycler_logs)
        emptyState = findViewById(R.id.empty_state)

        // Theme toggle
        findViewById<ImageButton>(R.id.btn_theme_toggle).setOnClickListener {
            ThemeManager.toggleTheme(this)
        }

        // Setup RecyclerView
        adapter = DriveLogAdapter { logEntry ->
            // Export CSV on button click
            val file = logEntry.file
            if (file.exists()) {
                CsvExporter.shareLatestLog(this, file)
            } else {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Search filtering
        findViewById<EditText>(R.id.search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterLogs(s?.toString() ?: "")
            }
        })

        // Action buttons
        findViewById<View>(R.id.btn_export_all).setOnClickListener {
            Toast.makeText(this, "Sync not available in prototype", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btn_clear_cache).setOnClickListener {
            Toast.makeText(this, "Cache clear not available in prototype", Toast.LENGTH_SHORT).show()
        }

        // Load log files
        loadLogFiles()

        // Bottom navigation
        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        loadLogFiles()
    }

    private fun loadLogFiles() {
        val logsDir = File(getExternalFilesDir(null), Constants.LOG_DIR_NAME)
        allLogEntries.clear()

        if (logsDir.exists() && logsDir.isDirectory) {
            val csvFiles = logsDir.listFiles { file ->
                file.extension.equals("csv", ignoreCase = true)
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            for (file in csvFiles) {
                val rowCount = countCsvRows(file)
                val sizeStr = formatFileSize(file.length())
                val dateStr = formatTimestamp(file.lastModified())
                val status = if (rowCount > 500) "VERIFIED" else "TUNING"

                allLogEntries.add(
                    LogEntry(
                        file = file,
                        filename = file.name,
                        date = dateStr,
                        rowCount = rowCount,
                        sizeDisplay = sizeStr,
                        status = status
                    )
                )
            }
        }

        updateUI(allLogEntries)
        updateStats()
    }

    private fun updateUI(entries: List<LogEntry>) {
        adapter.submitList(entries.toList())
        emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateStats() {
        val totalLogs = allLogEntries.size
        val totalBytes = allLogEntries.sumOf { it.file.length() }
        val totalRows = allLogEntries.sumOf { it.rowCount }

        // Estimate time: ~50 IMU samples/sec + 1 GPS/sec ≈ 51 rows/sec
        val estimatedSeconds = if (totalRows > 0) totalRows / 51 else 0
        val hours = estimatedSeconds / 3600
        val minutes = (estimatedSeconds % 3600) / 60

        findViewById<TextView>(R.id.stat_total_logs).text = totalLogs.toString()
        findViewById<TextView>(R.id.stat_storage).text = formatFileSize(totalBytes)
        findViewById<TextView>(R.id.stat_time).text = String.format(
            Locale.US, "%02d:%02d", hours, minutes
        )
    }

    private fun filterLogs(query: String) {
        if (query.isBlank()) {
            updateUI(allLogEntries)
        } else {
            val filtered = allLogEntries.filter {
                it.filename.contains(query, ignoreCase = true) ||
                        it.date.contains(query, ignoreCase = true)
            }
            updateUI(filtered)
        }
    }

    private fun countCsvRows(file: File): Int {
        return try {
            file.bufferedReader().useLines { lines ->
                // Subtract 1 for the header row
                (lines.count() - 1).coerceAtLeast(0)
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(millis))
    }

    private fun setupBottomNav() {
        // Dashboard tab
        findViewById<View>(R.id.nav_dashboard)?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }

        // Logs tab (current)
        findViewById<View>(R.id.nav_logs)?.apply {
            isSelected = true
        }

        // About tab
        findViewById<View>(R.id.nav_about)?.setOnClickListener {
            Toast.makeText(this@DriveLogActivity, "About — coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Data class representing a single drive log entry in the list.
     */
    data class LogEntry(
        val file: File,
        val filename: String,
        val date: String,
        val rowCount: Int,
        val sizeDisplay: String,
        val status: String
    )
}
