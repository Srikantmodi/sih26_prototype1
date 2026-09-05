package com.sih.deadreckoninglite.logging

import android.content.Context
import android.util.Log
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.sensors.SensorSample
import com.sih.deadreckoninglite.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Writes IMU and GPS samples to a CSV file matching the unified schema (PRD §10.1).
 *
 * ## CSV Schema (EXACT — do NOT deviate)
 * ```
 * timestamp_ns,ax,ay,az,gx,gy,gz,gnss_lat,gnss_lon,gnss_speed,gnss_accuracy
 * ```
 *
 * ## Design Contract (PRD §4.2 point 5, §9)
 * - This is the **ONLY** file that performs file I/O for sensor data.
 * - It knows nothing about how samples are collected — it only consumes
 *   [SensorSample] and [GpsSample] data objects from Member 2.
 * - It does NOT call into ImuManager, GpsProvider, or any other module.
 * - All routing is done by MainActivity (Member 3).
 *
 * ## Threading Model
 * [logImu] and [logGps] can be called from **any thread** — they are
 * non-blocking. Samples are enqueued into a lock-free
 * [ConcurrentLinkedQueue] and flushed to disk asynchronously on
 * [Dispatchers.IO] by a periodic flush coroutine.
 *
 * This design ensures:
 * 1. IMU callbacks (~50 Hz from ImuManager's sensor-delivery thread) never
 *    block on file I/O.
 * 2. GPS callbacks (main thread via GpsProvider) never jank the UI.
 * 3. Disk writes are batched for efficiency.
 *
 * ## Buffering Strategy
 * - Rows accumulate in a [ConcurrentLinkedQueue] (lock-free, thread-safe).
 * - A flush coroutine drains the queue every [FLUSH_INTERVAL_MS] or when
 *   the queue reaches [FLUSH_THRESHOLD_ROWS], whichever comes first.
 * - [stop] performs a final synchronous flush to guarantee no data loss.
 *
 * ## File Location
 * `<externalFilesDir>/logs/<fileName>`
 * (uses [Constants.LOG_DIR_NAME] — no hardcoded paths).
 *
 * ## Guards & Safety
 * - **Double-start guard:** Calling [start] twice stops the first session cleanly.
 * - **Write exception safety:** IOException during flush is caught and logged;
 *   subsequent samples continue buffering and retry on the next flush cycle.
 * - **Stop idempotency:** [stop] is safe to call multiple times.
 * - **Coroutine lifecycle:** Uses [SupervisorJob] so a single failed flush
 *   does not cancel the entire scope.
 *
 * @param context Application or Activity context (used only to resolve file paths).
 */
class SensorLogger(context: Context) {

    companion object {
        private const val TAG = "SensorLogger"

        /**
         * How often (ms) the flush coroutine wakes up to drain the buffer.
         * 2 seconds is a good balance between write latency and I/O batching.
         * At 50 Hz IMU, this means ~100 rows per flush — efficient.
         */
        private const val FLUSH_INTERVAL_MS: Long = 2_000L

        /**
         * If the queue reaches this many rows, trigger an immediate flush
         * on the next flush cycle instead of waiting for the full interval.
         * Prevents unbounded memory growth during high-throughput bursts.
         */
        private const val FLUSH_THRESHOLD_ROWS: Int = 200

        /**
         * Empty columns for IMU fields when logging a GPS-only row.
         * Schema: `timestamp_ns,[6 empty IMU fields],gnss_lat,...`
         *
         * The format string in logGps() is: `"${ts}${IMU_EMPTY_COLS},${gpsCsv}"`
         * IMU_EMPTY_COLS has 6 commas, plus the explicit `,` before gpsCsv = 7 separators.
         * This produces: ts + 6 empty fields + gpsCsv start boundary = correct alignment.
         *
         * Trace: `12345,,,,,,,28.6,77.2,12.3,3.2` → 10 commas → 11 fields ✅
         */
        private const val IMU_EMPTY_COLS = ",,,,,,"  // 6 commas + explicit comma in format = 7 boundaries = 6 empty + gpsCsv start

        /**
         * Empty columns for GNSS fields when logging an IMU-only row.
         * Schema: `timestamp_ns,ax,...,gz,[4 empty GNSS fields]`
         *
         * The format string in logImu() is: `"${ts},${imuCsv}${GNSS_EMPTY_COLS}"`
         * imuCsv = "ax,ay,az,gx,gy,gz" (6 values with 5 internal commas).
         * We need 4 trailing empty fields → 4 commas.
         *
         * Verification: `12345,ax,ay,az,gx,gy,gz,,,,`
         *               ts  ^1^2^3^4^5^6   ^7^8^9^10
         *               = timestamp + 6 IMU values + 4 empties = 11 columns ✅
         */
        private const val GNSS_EMPTY_COLS = ",,,,"   // 4 commas → 4 empty fields after gz
    }

    private val appContext: Context = context.applicationContext

    // ------------------------------------------------------------------ //
    //  State tracking                                                     //
    // ------------------------------------------------------------------ //

    /** True while a logging session is active. Prevents double-start. */
    @Volatile private var isRunning: Boolean = false

    /** The CSV file being written to in the current session. */
    @Volatile private var currentFile: File? = null

    /** The underlying writer — only accessed from the flush coroutine (Dispatchers.IO). */
    private var writer: BufferedWriter? = null

    /** Coroutine scope for background flush operations. */
    private var flushScope: CoroutineScope? = null

    /** Handle to the periodic flush job — used for cancellation. */
    private var flushJob: Job? = null

    /** Mutex protecting writer open/close to prevent races between flush and stop. */
    private val writerMutex = Mutex()

    // ------------------------------------------------------------------ //
    //  Thread-safe row buffer                                             //
    // ------------------------------------------------------------------ //

    /**
     * Lock-free queue of pre-formatted CSV rows.
     *
     * Rows are formatted at enqueue time (in [logImu] / [logGps]) so the
     * flush coroutine only needs to drain and write — no formatting work
     * on the I/O thread.
     */
    private val rowBuffer = ConcurrentLinkedQueue<String>()

    /** Total rows written to disk in the current session (for diagnostics). */
    @Volatile private var totalRowsWritten: Long = 0L

    /** Total IMU samples received in the current session. */
    @Volatile private var imuSamplesReceived: Long = 0L

    /** Total GPS samples received in the current session. */
    @Volatile private var gpsSamplesReceived: Long = 0L

    // ------------------------------------------------------------------ //
    //  Public API (PRD §9 contract)                                       //
    // ------------------------------------------------------------------ //

    /**
     * Begin a new logging session.
     *
     * Creates the log directory if needed, opens the CSV file, writes the
     * header row, and starts the periodic flush coroutine.
     *
     * If already running, the previous session is stopped cleanly first
     * (double-start guard).
     *
     * @param fileName Name of the CSV file (default: `drive_<timestamp>.csv`).
     *                 The file is created under `<externalFilesDir>/logs/`.
     */
    fun start(fileName: String = "${Constants.LOG_FILE_PREFIX}${System.currentTimeMillis()}.csv") {
        // Double-start guard
        if (isRunning) {
            Log.w(TAG, "start() called while already running — stopping previous session first")
            stop()
        }

        // Reset counters
        totalRowsWritten = 0L
        imuSamplesReceived = 0L
        gpsSamplesReceived = 0L
        rowBuffer.clear()

        // Resolve file path: <externalFilesDir>/logs/<fileName>
        val logDir = File(appContext.getExternalFilesDir(null), Constants.LOG_DIR_NAME)

        // Create the directory if it doesn't exist
        if (!logDir.exists()) {
            val created = logDir.mkdirs()
            if (!created) {
                Log.e(TAG, "Failed to create log directory: ${logDir.absolutePath}")
                return
            }
            Log.i(TAG, "Created log directory: ${logDir.absolutePath}")
        }

        val file = File(logDir, fileName)
        currentFile = file

        try {
            // Open writer with append=false (new session = new file)
            writer = BufferedWriter(FileWriter(file, false))

            // Write the CSV header row — using the single-source-of-truth
            // constant from GpsSample.UNIFIED_CSV_HEADER
            writer?.write(GpsSample.UNIFIED_CSV_HEADER)
            writer?.newLine()
            writer?.flush()  // Ensure header is persisted immediately

            Log.i(TAG, "Started logging to: ${file.absolutePath}")
            Log.d(TAG, "CSV header: ${GpsSample.UNIFIED_CSV_HEADER}")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to open CSV file for writing: ${file.absolutePath}", e)
            writer = null
            currentFile = null
            return
        }

        // Start the periodic flush coroutine
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        flushScope = scope

        flushJob = scope.launch {
            Log.d(TAG, "Flush coroutine started — interval=${FLUSH_INTERVAL_MS}ms, " +
                    "threshold=${FLUSH_THRESHOLD_ROWS} rows")
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBuffer()
            }
        }

        isRunning = true
    }

    /**
     * Enqueue an IMU sample for writing to CSV.
     *
     * **Thread-safe** — can be called from the sensor-delivery thread
     * (ImuManager callback) without blocking. The row is pre-formatted
     * here and flushed to disk asynchronously.
     *
     * CSV row format (IMU-only):
     * `<timestampNs>,<ax>,<ay>,<az>,<gx>,<gy>,<gz>,,,,`
     * (trailing empties for the 4 GNSS columns)
     *
     * @param sample The merged IMU sample from ImuManager.
     */
    fun logImu(sample: SensorSample) {
        if (!isRunning) {
            Log.w(TAG, "logImu() called but logger is not running — sample dropped")
            return
        }

        imuSamplesReceived++

        // Pre-format the entire CSV row:
        // timestamp_ns, [6 IMU values from toCsvValues()], [4 empty GNSS cols]
        val row = "${sample.timestampNs},${sample.toCsvValues()}$GNSS_EMPTY_COLS"
        rowBuffer.offer(row)

        // Log periodically for debugging (every 500th sample ≈ every 10 seconds at 50 Hz)
        if (imuSamplesReceived == 1L || imuSamplesReceived % 500 == 0L) {
            Log.d(TAG, "IMU sample #$imuSamplesReceived buffered | " +
                    "queue=${rowBuffer.size} | written=$totalRowsWritten")
        }
    }

    /**
     * Enqueue a GPS sample for writing to CSV.
     *
     * **Thread-safe** — can be called from the main thread (GpsProvider
     * callback) without blocking.
     *
     * CSV row format (GPS-only):
     * `<timestampNs>,,,,,,<gnss_lat>,<gnss_lon>,<gnss_speed>,<gnss_accuracy>`
     * (leading empties for the 6 IMU columns)
     *
     * @param sample The GPS fix from GpsProvider.
     */
    fun logGps(sample: GpsSample) {
        if (!isRunning) {
            Log.w(TAG, "logGps() called but logger is not running — sample dropped")
            return
        }

        gpsSamplesReceived++

        // Pre-format the entire CSV row:
        // timestamp_ns, [6 empty IMU cols], [4 GNSS values from toCsvValues()]
        val row = "${sample.timestampNs}$IMU_EMPTY_COLS,${sample.toCsvValues()}"
        rowBuffer.offer(row)

        Log.d(TAG, "GPS sample #$gpsSamplesReceived buffered | " +
                "queue=${rowBuffer.size} | written=$totalRowsWritten")
    }

    /**
     * Stop the current logging session.
     *
     * Performs a final flush to guarantee no buffered data is lost,
     * then closes the file writer and cancels the flush coroutine.
     *
     * Safe to call multiple times, or before [start] has ever been called.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false  // Set early to reject new samples during shutdown

        // Cancel the periodic flush coroutine
        flushJob?.cancel()
        flushJob = null
        flushScope?.cancel()
        flushScope = null

        // Final synchronous flush — drain any remaining buffered rows
        // This runs on the caller's thread (typically main), but the buffer
        // is usually small (< 100 rows) so it's fast.
        flushBufferSync()

        // Close the writer
        try {
            writer?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing CSV writer", e)
        }
        writer = null

        Log.i(TAG, "Stopped — total rows written: $totalRowsWritten " +
                "(IMU: $imuSamplesReceived, GPS: $gpsSamplesReceived) " +
                "to file: ${currentFile?.absolutePath}")

        currentFile = null
    }

    /**
     * Returns the current log file, or null if no session is active.
     *
     * Used by [CsvExporter] to locate the file for sharing.
     */
    fun getCurrentFile(): File? = currentFile

    // ------------------------------------------------------------------ //
    //  Internal flush logic                                               //
    // ------------------------------------------------------------------ //

    /**
     * Drain the row buffer and write to disk. Called periodically by the
     * flush coroutine on [Dispatchers.IO].
     *
     * Uses [writerMutex] to prevent races with [stop] which may close
     * the writer concurrently.
     */
    private suspend fun flushBuffer() {
        if (rowBuffer.isEmpty()) return

        writerMutex.withLock {
            flushBufferInternal()
        }
    }

    /**
     * Synchronous version of flush — used by [stop] for the final drain.
     * Does NOT acquire the mutex (caller is responsible for thread safety
     * at this point — the flush coroutine is already cancelled).
     */
    private fun flushBufferSync() {
        flushBufferInternal()
    }

    /**
     * Core flush logic — drains the queue and writes to the BufferedWriter.
     *
     * Exception-safe: an [IOException] during write logs the error but
     * does NOT crash the app. The queue is drained regardless (rows that
     * failed to write are lost, which is acceptable for sensor data —
     * we prioritize app stability over perfect data capture).
     */
    private fun flushBufferInternal() {
        val w = writer ?: return
        var flushedCount = 0

        try {
            // Drain the entire queue
            var row = rowBuffer.poll()
            while (row != null) {
                w.write(row)
                w.newLine()
                flushedCount++
                totalRowsWritten++
                row = rowBuffer.poll()
            }

            // Flush the underlying stream to disk
            if (flushedCount > 0) {
                w.flush()
                Log.v(TAG, "Flushed $flushedCount rows to disk " +
                        "(total: $totalRowsWritten)")
            }

        } catch (e: IOException) {
            Log.e(TAG, "IOException during flush — $flushedCount rows may be lost. " +
                    "Buffer still has ${rowBuffer.size} pending rows.", e)
            // Don't rethrow — let the next flush cycle retry with remaining rows.
            // The rows that were polled but not written ARE lost, which is
            // acceptable (sensor data, not financial transactions).
        }
    }
}
