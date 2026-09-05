package org.appdevforall.getaimodels.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.appdevforall.getaimodels.R
import org.appdevforall.getaimodels.catalog.ByteSize
import org.appdevforall.getaimodels.catalog.CatalogEntry
import org.appdevforall.getaimodels.download.DownloadState
import org.appdevforall.getaimodels.download.Phase

/**
 * One row per catalog file: name, compact spec line, action button, tap-to-expand detail. In flight,
 * Download becomes a Cancel control whose background doubles as the progress indicator - an approved
 * deviation from "no in-app per-row progress bar", since the notification can be dismissed.
 */
class ModelRowAdapter(
    private val entries: List<CatalogEntry>,
    private val onDownload: (CatalogEntry) -> Unit,
    private val onCancel: (CatalogEntry) -> Unit,
    private val onVerify: (CatalogEntry, path: String) -> Unit,
    private val onDelete: (CatalogEntry, path: String, wasVerified: Boolean) -> Unit,
    private val onLongPress: (anchor: View, tooltipTag: String) -> Unit,
    private val rowTooltipTag: String,
    private val downloadTooltipTag: String,
    private val verifyTooltipTag: String,
    private val cancelTooltipTag: String,
    private val deleteTooltipTag: String
) : RecyclerView.Adapter<ModelRowAdapter.RowHolder>() {

    private companion object {
        /** ClipDrawable level range. */
        const val MAX_LEVEL = 10_000
    }

    private val expanded = mutableSetOf<String>()
    private var states: Map<String, DownloadState> = emptyMap()

    /** Applies new download states, refreshing only the rows whose state actually changed. */
    fun submitStates(next: Map<String, DownloadState>) {
        val previous = states
        states = next
        entries.forEachIndexed { index, entry ->
            val before = previous[entry.id] ?: DownloadState.Idle
            val after = next[entry.id] ?: DownloadState.Idle
            if (before != after) notifyItemChanged(index)
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        // parent.context is the host's theme-aware plugin context, so values-night/ resolves.
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return RowHolder(view)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(entries[position])
    }

    inner class RowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val name: TextView = itemView.findViewById(R.id.tvModelName)
        private val spec: TextView = itemView.findViewById(R.id.tvModelSpec)
        private val status: TextView = itemView.findViewById(R.id.tvModelStatus)
        private val detail: View = itemView.findViewById(R.id.groupDetail)
        private val description: TextView = itemView.findViewById(R.id.tvModelDescription)
        private val gatesUnproven: TextView = itemView.findViewById(R.id.tvGatesUnproven)
        private val publisher: TextView = itemView.findViewById(R.id.tvModelPublisher)
        private val minRam: TextView = itemView.findViewById(R.id.tvModelMinRam)
        private val license: TextView = itemView.findViewById(R.id.tvModelLicense)
        private val download: MaterialButton = itemView.findViewById(R.id.btnDownload)
        private val verify: Button = itemView.findViewById(R.id.btnVerify)
        private val delete: Button = itemView.findViewById(R.id.btnDelete)
        private val cancelControl: View = itemView.findViewById(R.id.cancelControl)
        private val progressFill: View = itemView.findViewById(R.id.progressFill)
        private val cancelLabel: TextView = itemView.findViewById(R.id.tvCancel)

        init {
            // Keeps the fill inside the track's rounded corners.
            cancelControl.clipToOutline = true
            // Verify and Delete are real Buttons; Cancel has to be a container so the progress fill
            // can sit behind its label, so only that one declares the role itself.
            announceAsButton(cancelControl)
        }

        /** Makes a non-Button view report itself as a button to accessibility services. */
        private fun announceAsButton(view: View) {
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            })
        }

        fun bind(entry: CatalogEntry) {
            val resources = itemView.resources
            name.text = entry.name
            spec.text = resources.getString(
                R.string.spec_line,
                entry.parameters,
                entry.quantization,
                ByteSize.format(entry.sizeBytes)
            )

            description.text = entry.description
            // Gates 3 and 4 are proven by a harness that does not exist yet; say so in the UI.
            gatesUnproven.visibility =
                if (entry.behaviouralGatesVerified) View.GONE else View.VISIBLE
            publisher.text = resources.getString(R.string.publisher_label, entry.publisher)
            minRam.text = resources.getString(
                R.string.min_ram_label,
                ByteSize.formatWholeGb(entry.minRamBytes)
            )
            license.text = resources.getString(
                R.string.license_label,
                entry.license,
                "${entry.contextTokens / 1024}k",
                entry.baseModel
            )
            detail.visibility = if (entry.id in expanded) View.VISIBLE else View.GONE

            bindState(entry, states[entry.id] ?: DownloadState.Idle)

            itemView.setOnClickListener { toggle(entry) }
            itemView.setOnLongClickListener {
                onLongPress(itemView, rowTooltipTag)
                true
            }
            download.setOnLongClickListener {
                onLongPress(download, downloadTooltipTag)
                true
            }
            download.contentDescription =
                resources.getString(R.string.content_description_download, entry.name)
            verify.setOnLongClickListener {
                onLongPress(verify, verifyTooltipTag)
                true
            }
            verify.contentDescription =
                resources.getString(R.string.content_description_verify, entry.name)
            delete.setOnLongClickListener {
                onLongPress(delete, deleteTooltipTag)
                true
            }
            delete.contentDescription =
                resources.getString(R.string.content_description_delete, entry.name)
            cancelControl.setOnLongClickListener {
                onLongPress(cancelControl, cancelTooltipTag)
                true
            }
            cancelControl.contentDescription =
                resources.getString(R.string.content_description_cancel, entry.name)
        }

        private fun bindState(entry: CatalogEntry, state: DownloadState) {
            val resources = itemView.resources
            download.setOnClickListener(null)
            verify.setOnClickListener(null)
            delete.setOnClickListener(null)
            cancelControl.setOnClickListener(null)

            // Cancel takes the download button's place in flight rather than sitting beside it.
            val inFlight = state is DownloadState.Downloading
            cancelControl.visibility = if (inFlight) View.VISIBLE else View.GONE
            download.visibility = if (inFlight) View.GONE else View.VISIBLE
            // Verified with a path we wrote and can find again. A verified-but-pathless row is
            // deliberately excluded: neither re-verifying nor deleting has anything to act on.
            val verifiedOnDisk = (state as? DownloadState.Verified)?.takeIf { it.onDisk }

            // Only where a record gives a path - never one composed from the catalogued file name.
            val recordedPath = verifiedOnDisk?.path ?: (state as? DownloadState.Changed)?.path

            // A Changed row re-verifies via the main button instead, so this stays hidden there.
            verify.visibility = if (verifiedOnDisk != null) View.VISIBLE else View.GONE
            verifiedOnDisk?.let { verified ->
                verify.setOnClickListener { onVerify(entry, verified.path) }
            }
            delete.visibility = if (recordedPath != null) View.VISIBLE else View.GONE
            if (recordedPath != null) {
                delete.setOnClickListener {
                    onDelete(entry, recordedPath, state is DownloadState.Verified)
                }
            }

            when (state) {
                DownloadState.Idle -> {
                    download.isEnabled = true
                    download.setText(R.string.action_download)
                    status.visibility = View.GONE
                    download.setOnClickListener { onDownload(entry) }
                }

                is DownloadState.Downloading -> bindInFlight(entry, state)

                DownloadState.Verifying -> {
                    download.isEnabled = false
                    download.setText(R.string.state_verifying)
                    showStatus(R.string.status_verifying, R.color.status_neutral_text)
                }

                is DownloadState.Verified -> {
                    download.isEnabled = false
                    download.setText(R.string.state_downloaded)
                    status.visibility = View.VISIBLE
                    status.text = if (state.onDisk) {
                        resources.getString(R.string.status_verified, state.path)
                    } else {
                        // No path came back, so the folder cannot be named and the badge is
                        // session-only; say so rather than imply it will still be here tomorrow.
                        resources.getString(R.string.status_verified_no_path, state.path)
                    }
                    status.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_success_text))
                }

                is DownloadState.Changed -> {
                    download.isEnabled = true
                    download.setText(R.string.action_verify)
                    status.visibility = View.VISIBLE
                    status.text = resources.getString(R.string.status_changed, state.path)
                    status.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.status_error_text)
                    )
                    download.setOnClickListener { onVerify(entry, state.path) }
                }

                is DownloadState.Failed -> {
                    download.isEnabled = true
                    download.setText(R.string.action_retry)
                    status.visibility = View.VISIBLE
                    status.text = if (state.checksumMismatch) {
                        resources.getString(R.string.status_checksum_failed)
                    } else {
                        resources.getString(R.string.status_failed, state.message)
                    }
                    status.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_error_text))
                    download.setOnClickListener { onDownload(entry) }
                }
            }
        }

        /**
         * Draws the in-flight row: the fill behind the Cancel label is the progress, and the status
         * line says whether bytes are actually moving. Both matter because the system notification
         * can be dismissed.
         */
        private fun bindInFlight(entry: CatalogEntry, state: DownloadState.Downloading) {
            val resources = itemView.resources

            // ClipDrawable levels run 0..10000, so no measured width is needed.
            progressFill.background?.level = (state.fraction * MAX_LEVEL).toInt()
            cancelLabel.text = if (state.bytesSoFar > 0L) {
                resources.getString(
                    R.string.cancel_label_percent,
                    (state.fraction * 100).toInt()
                )
            } else {
                resources.getString(R.string.action_cancel_download)
            }
            cancelControl.setOnClickListener { onCancel(entry) }

            val soFar = ByteSize.format(state.bytesSoFar)
            val total = ByteSize.format(state.totalBytes)
            status.visibility = View.VISIBLE
            status.text = when (state.phase) {
                Phase.PENDING -> resources.getString(R.string.status_pending)
                Phase.RUNNING -> resources.getString(R.string.status_downloading, soFar, total)
                Phase.PAUSED_WAITING_FOR_WIFI ->
                    resources.getString(R.string.status_paused_wifi, soFar, total)

                Phase.PAUSED_WAITING_FOR_NETWORK ->
                    resources.getString(R.string.status_paused_network, soFar, total)

                Phase.PAUSED_WAITING_TO_RETRY ->
                    resources.getString(R.string.status_paused_retry, soFar, total)

                Phase.PAUSED_UNKNOWN ->
                    resources.getString(R.string.status_paused_unknown, soFar, total)
            }
            // A pause is not an error, but nothing moves until something changes - so it stands out.
            status.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (state.isPaused) R.color.status_error_text else R.color.status_neutral_text
                )
            )
        }

        private fun showStatus(textRes: Int, colorRes: Int) {
            status.visibility = View.VISIBLE
            status.setText(textRes)
            status.setTextColor(ContextCompat.getColor(itemView.context, colorRes))
        }

        private fun toggle(entry: CatalogEntry) {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            if (!expanded.remove(entry.id)) expanded.add(entry.id)
            notifyItemChanged(position)
        }
    }
}
