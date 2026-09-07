package com.appdevforall.contractor.plugin.ui.projects

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.RowProjectBinding
import com.appdevforall.contractor.plugin.domain.model.DurationFormat
import com.appdevforall.contractor.plugin.domain.model.MoneyFormat

class ProjectsAdapter(
    private val onClick: (ProjectRow) -> Unit,
    private val onMore: (ProjectRow, android.view.View) -> Unit
) : ListAdapter<ProjectRow, ProjectsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = RowProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: RowProjectBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ProjectRow) {
            val ctx = binding.root.context
            binding.title.text = row.project.displayName
            binding.client.text = row.project.clientName
            binding.rate.text = ctx.getString(
                R.string.project_card_rate,
                MoneyFormat.format(row.project.hourlyRate, row.project.currency)
            )
            binding.monthValue.text = DurationFormat.formatHoursMinutes(row.monthMillis)

            binding.trackingEyebrow.visibility =
                if (row.isActiveTracked) android.view.View.VISIBLE else android.view.View.GONE

            binding.archivedChip.visibility =
                if (row.project.isArchived) android.view.View.VISIBLE else android.view.View.GONE

            binding.root.alpha = if (row.project.isArchived) 0.55f else 1.0f

            binding.root.setOnClickListener { onClick(row) }
            binding.btnMore.setOnClickListener { onMore(row, it) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProjectRow>() {
            override fun areItemsTheSame(o: ProjectRow, n: ProjectRow) = o.project.id == n.project.id
            override fun areContentsTheSame(o: ProjectRow, n: ProjectRow) = o == n
        }
    }
}
