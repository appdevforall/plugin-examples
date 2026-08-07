package org.appdevforall.templatemanagerplugin.adapters

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.templatemanagerplugin.R
import org.appdevforall.templatemanagerplugin.databinding.ItemTemplateBinding
import org.appdevforall.templatemanagerplugin.models.TemplateMetadata
import org.appdevforall.templatemanagerplugin.models.versionLabel

/** Lists the individual templates bundled inside a single multi-template .cgt file. */
class TemplateCardAdapter(
    private val templates: List<TemplateMetadata>,
    private val onDetails: (TemplateMetadata) -> Unit
) : RecyclerView.Adapter<TemplateCardAdapter.TemplateViewHolder>() {

    private companion object {
        const val MENU_DETAILS = 1
    }

    inner class TemplateViewHolder(private val binding: ItemTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(template: TemplateMetadata) {
            binding.tvTemplateName.text = template.name.ifBlank { "(unnamed)" }
            val versionText = versionLabel(template.version)
            binding.tvTemplateVersion.text = versionText
            binding.tvTemplateVersion.visibility = if (versionText.isBlank()) View.GONE else View.VISIBLE
            binding.tvTemplateDesc.text = template.description

            binding.btnMenu.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor, Gravity.END, 0, R.style.PopupMenuStyle)
                popup.menu.add(0, MENU_DETAILS, 0, "Details")
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.itemId == MENU_DETAILS) onDetails(template)
                    true
                }
                popup.show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateViewHolder {
        val binding = ItemTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TemplateViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TemplateViewHolder, position: Int) {
        holder.bind(templates[position])
    }

    override fun getItemCount(): Int = templates.size
}
