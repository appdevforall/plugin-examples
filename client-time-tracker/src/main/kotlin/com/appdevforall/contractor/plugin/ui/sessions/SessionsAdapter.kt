package com.appdevforall.contractor.plugin.ui.sessions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import com.appdevforall.contractor.plugin.databinding.RowSessionBinding
import com.appdevforall.contractor.plugin.databinding.RowSessionDateHeaderBinding
import com.appdevforall.contractor.plugin.domain.model.DurationFormat
import com.appdevforall.contractor.plugin.domain.usecase.SessionMerger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class SessionListItem {
    data class DateHeader(val date: LocalDate, val totalMillis: Long) : SessionListItem()
    data class Session(val session: WorkSessionEntity, val projectName: String) : SessionListItem()
}

class SessionsAdapter(
    private val onClick: (WorkSessionEntity) -> Unit
) : ListAdapter<SessionListItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SessionListItem.DateHeader -> TYPE_HEADER
        is SessionListItem.Session -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(RowSessionDateHeaderBinding.inflate(inflater, parent, false))
            TYPE_ROW -> RowVH(RowSessionBinding.inflate(inflater, parent, false))
            else -> error("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is HeaderVH -> holder.bind(item as SessionListItem.DateHeader)
            is RowVH -> holder.bind(item as SessionListItem.Session)
        }
    }

    inner class HeaderVH(private val binding: RowSessionDateHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SessionListItem.DateHeader) {
            binding.dateText.text = HEADER_FMT.format(item.date)
            binding.totalText.text = DurationFormat.formatHoursMinutes(item.totalMillis)
        }
    }

    inner class RowVH(private val binding: RowSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SessionListItem.Session) {
            val ctx = binding.root.context
            val s = item.session
            val isActive = s.endTime == null

            binding.timeRange.text = formatRange(s.startTime, s.endTime ?: s.lastHeartbeatAt)
            binding.duration.text = DurationFormat.formatHoursMinutes(SessionMerger.durationOf(s))
            binding.projectLabel.text = item.projectName
            binding.notes.visibility = if (s.notes.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.notes.text = s.notes

            binding.activeBadge.visibility = if (isActive) View.VISIBLE else View.GONE
            binding.root.background = if (isActive) {
                ctx.getDrawable(R.drawable.active_session_left_border)
            } else {
                null
            }

            binding.root.setOnClickListener { onClick(s) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private val HEADER_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

        fun build(
            sessions: List<WorkSessionEntity>,
            projects: List<TrackedProjectEntity>
        ): List<SessionListItem> {
            if (sessions.isEmpty()) return emptyList()
            val zone = ZoneId.systemDefault()
            val projectName: (String) -> String = { id ->
                projects.firstOrNull { it.id == id }?.displayName ?: "—"
            }
            val grouped = sessions
                .sortedByDescending { it.startTime }
                .groupBy { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() }

            val items = mutableListOf<SessionListItem>()
            grouped.forEach { (date, list) ->
                val total = list.sumOf { SessionMerger.durationOf(it) }
                items += SessionListItem.DateHeader(date, total)
                list.forEach {
                    items += SessionListItem.Session(it, projectName(it.projectId))
                }
            }
            return items
        }

        private fun formatRange(start: Long, end: Long): String {
            val zone = ZoneId.systemDefault()
            val s = Instant.ofEpochMilli(start).atZone(zone).toLocalTime()
            val e = Instant.ofEpochMilli(end).atZone(zone).toLocalTime()
            return "${TIME_FMT.format(s)} – ${TIME_FMT.format(e)}"
        }

        private val DIFF = object : DiffUtil.ItemCallback<SessionListItem>() {
            override fun areItemsTheSame(o: SessionListItem, n: SessionListItem): Boolean = when {
                o is SessionListItem.DateHeader && n is SessionListItem.DateHeader -> o.date == n.date
                o is SessionListItem.Session && n is SessionListItem.Session -> o.session.id == n.session.id
                else -> false
            }

            override fun areContentsTheSame(o: SessionListItem, n: SessionListItem) = o == n
        }
    }
}
