package com.sih.deadreckoninglite.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares a CSV log file off-device via Android's share sheet.
 *
 * Uses [FileProvider] to generate a content URI that other apps
 * can read securely, then launches an [Intent.ACTION_SEND] chooser
 * with MIME type "text/csv".
 *
 * ## Member Ownership
 * This file is owned by Member 4 (Data Logging). A minimal implementation
 * is provided here so Member 5's DriveLogActivity can compile and function.
 * Member 4 should enhance this as needed.
 */
object CsvExporter {

    /**
     * Launch the system share sheet to share the given CSV [file].
     *
     * @param context Activity context for starting the chooser
     * @param file    The CSV file to share — must exist and be under
     *                the FileProvider's declared paths
     */
    fun shareLatestLog(context: Context, file: File) {
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DR Lite Drive Log: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "Share drive log")
        )
    }
}
