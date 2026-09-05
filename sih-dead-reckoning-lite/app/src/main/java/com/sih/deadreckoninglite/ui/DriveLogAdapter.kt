package com.sih.deadreckoninglite.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sih.deadreckoninglite.R
import java.util.Locale

/**
 * RecyclerView adapter for drive log session entries.
 *
 * Displays each CSV log file with its filename, date, row count,
 * file size, and status badge. Supports efficient list updates
 * via [DiffUtil].
 *
 * @param onExportClick Callback when the CSV export button is tapped
 *                      for a specific log entry.
 */
class DriveLogAdapter(
    private val onExportClick: (DriveLogActivity.LogEntry) -> Unit
) : ListAdapter<DriveLogActivity.LogEntry, DriveLogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drive_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val filename: TextView = itemView.findViewById(R.id.log_filename)
        private val date: TextView = itemView.findViewById(R.id.log_date)
        private val statusBadge: TextView = itemView.findViewById(R.id.log_status_badge)
        private val rows: TextView = itemView.findViewById(R.id.log_rows)
        private val size: TextView = itemView.findViewById(R.id.log_size)
        private val exportBtn: View = itemView.findViewById(R.id.btn_export_csv)

        fun bind(entry: DriveLogActivity.LogEntry) {
            filename.text = entry.filename
            date.text = entry.date
            rows.text = String.format(Locale.US, "Rows: %,d", entry.rowCount)
            size.text = "Size: ${entry.sizeDisplay}"

            statusBadge.text = entry.status
            val statusColor = if (entry.status == "VERIFIED") {
                itemView.context.getColor(R.color.status_verified)
            } else {
                itemView.context.getColor(R.color.status_tuning)
            }
            statusBadge.setTextColor(statusColor)

            exportBtn.setOnClickListener {
                onExportClick(entry)
            }
        }
    }

    private class LogDiffCallback : DiffUtil.ItemCallback<DriveLogActivity.LogEntry>() {
        override fun areItemsTheSame(
            oldItem: DriveLogActivity.LogEntry,
            newItem: DriveLogActivity.LogEntry
        ): Boolean = oldItem.filename == newItem.filename

        override fun areContentsTheSame(
            oldItem: DriveLogActivity.LogEntry,
            newItem: DriveLogActivity.LogEntry
        ): Boolean = oldItem == newItem
    }
}
