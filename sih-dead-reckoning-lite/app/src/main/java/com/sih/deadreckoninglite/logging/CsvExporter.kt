package com.sih.deadreckoninglite.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sih.deadreckoninglite.location.GpsSample
import java.io.File

/**
 * Handles exporting (sharing) CSV log files off-device via Android's
 * share sheet (ACTION_SEND).
 *
 * ## Design Contract (PRD §4.2 point 5, §9)
 * - This is a stateless utility object — no lifecycle, no state.
 * - It does NOT know how the CSV was created; it only operates on a [File].
 * - The actual file is produced by [SensorLogger]; this class just shares it.
 *
 * ## FileProvider Requirement
 * Android 7.0+ (API 24) prohibits passing `file://` URIs via Intents.
 * We use [FileProvider] to generate a `content://` URI that grants
 * temporary read access to the receiving app.
 *
 * **Manifest entry required** (coordinate with Member 3):
 * ```xml
 * <provider
 *     android:name="androidx.core.content.FileProvider"
 *     android:authorities="${applicationId}.fileprovider"
 *     android:exported="false"
 *     android:grantUriPermissions="true">
 *     <meta-data
 *         android:name="android.support.FILE_PROVIDER_PATHS"
 *         android:resource="@xml/file_paths" />
 * </provider>
 * ```
 *
 * **res/xml/file_paths.xml** (created by this module):
 * ```xml
 * <paths>
 *     <external-files-path name="logs" path="logs/" />
 * </paths>
 * ```
 *
 * ## Usage
 * ```kotlin
 * // In MainActivity's export button handler:
 * val logFile = sensorLogger.getCurrentFile()
 * if (logFile != null && logFile.exists()) {
 *     CsvExporter.shareLatestLog(this, logFile)
 * }
 * ```
 */
object CsvExporter {

    private const val TAG = "CsvExporter"

    /**
     * The FileProvider authority — MUST match the `android:authorities`
     * attribute in AndroidManifest.xml.
     *
     * Convention: `<applicationId>.fileprovider`
     */
    private const val FILE_PROVIDER_AUTHORITY = "com.sih.deadreckoninglite.fileprovider"

    /**
     * MIME type for CSV files used in the share Intent.
     */
    private const val CSV_MIME_TYPE = "text/csv"

    /**
     * Share a CSV log file via Android's share sheet.
     *
     * Builds an [Intent.ACTION_SEND] with:
     * - A `content://` URI from [FileProvider] (required for API 24+)
     * - MIME type `text/csv`
     * - [Intent.FLAG_GRANT_READ_URI_PERMISSION] so the receiving app can read the file
     * - A subject line with the filename for email clients
     *
     * If the file doesn't exist or the FileProvider URI can't be generated,
     * a user-facing [Toast] is shown and the error is logged.
     *
     * @param context Activity or Application context. Must be an Activity
     *                context for the chooser to display correctly.
     * @param file    The CSV file to share (typically from [SensorLogger.getCurrentFile]).
     */
    fun shareLatestLog(context: Context, file: File) {
        // ---- Pre-flight checks ---- //

        if (!file.exists()) {
            Log.e(TAG, "Cannot share — file does not exist: ${file.absolutePath}")
            Toast.makeText(context, "No log file found to share", Toast.LENGTH_SHORT).show()
            return
        }

        if (file.length() == 0L) {
            Log.w(TAG, "File exists but is empty (0 bytes): ${file.absolutePath}")
            Toast.makeText(context, "Log file is empty — nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        // ---- Generate content URI via FileProvider ---- //

        val contentUri: Uri = try {
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        } catch (e: IllegalArgumentException) {
            // This happens if:
            // 1. The FileProvider is not declared in AndroidManifest.xml
            // 2. The file_paths.xml doesn't cover the file's directory
            // 3. The authority string doesn't match
            Log.e(TAG, "FileProvider failed to generate URI for: ${file.absolutePath}. " +
                    "Check that the FileProvider is declared in AndroidManifest.xml " +
                    "with authority '$FILE_PROVIDER_AUTHORITY' and that " +
                    "res/xml/file_paths.xml includes an <external-files-path> entry.", e)
            Toast.makeText(context, "Unable to share file — configuration error", Toast.LENGTH_LONG).show()
            return
        }

        // ---- Build the share Intent ---- //

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = CSV_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, contentUri)

            // Subject line for email clients
            putExtra(Intent.EXTRA_SUBJECT, "Dead Reckoning Data: ${file.name}")

            // Body text for email clients
            putExtra(Intent.EXTRA_TEXT,
                "Attached: sensor data log from Dead Reckoning Lite.\n" +
                "File: ${file.name}\n" +
                "Size: ${formatFileSize(file.length())}\n" +
                "Format: CSV (${GpsSample.UNIFIED_CSV_HEADER})")

            // Grant temporary read permission to the receiving app
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // ---- Launch the share sheet ---- //

        val chooserIntent = Intent.createChooser(shareIntent, "Share sensor log via…")

        // Verify there's at least one app that can handle this Intent
        if (chooserIntent.resolveActivity(context.packageManager) != null ||
            shareIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(chooserIntent)
            Log.i(TAG, "Share sheet launched for: ${file.name} " +
                    "(${formatFileSize(file.length())})")
        } else {
            // Fallback: on newer Android versions, resolveActivity may return null
            // even though the chooser will work. Just try launching it.
            try {
                context.startActivity(chooserIntent)
                Log.i(TAG, "Share sheet launched (fallback) for: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "No app available to handle CSV share", e)
                Toast.makeText(context, "No app available to share files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Format a file size in bytes to a human-readable string.
     *
     * Examples: `"1.2 KB"`, `"3.4 MB"`, `"512 B"`
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1_024L -> "$bytes B"
            bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
            else -> "%.1f MB".format(bytes / 1_048_576.0)
        }
    }
}
