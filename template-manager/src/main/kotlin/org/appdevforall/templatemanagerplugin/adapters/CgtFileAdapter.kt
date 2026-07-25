package org.appdevforall.templatemanagerplugin.adapters

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.templatemanagerplugin.R
import org.appdevforall.templatemanagerplugin.databinding.ItemCgtFileBinding
import org.appdevforall.templatemanagerplugin.models.CgtFileItem
import org.appdevforall.templatemanagerplugin.models.displayName
import org.appdevforall.templatemanagerplugin.models.hasMultipleTemplates
import org.appdevforall.templatemanagerplugin.models.primaryTemplate
import org.appdevforall.templatemanagerplugin.models.versionLabel

class CgtFileAdapter(
    private val items: List<CgtFileItem>,
    private val onInstall: (CgtFileItem) -> Unit,
    private val onUninstall: (CgtFileItem) -> Unit,
    private val onDetails: (CgtFileItem) -> Unit,
    private val onDelete: (CgtFileItem) -> Unit,
    private val onViewTemplates: (CgtFileItem) -> Unit,
    private val onLongPress: (View) -> Unit
) : RecyclerView.Adapter<CgtFileAdapter.FileViewHolder>() {

    private companion object {
        const val MENU_INSTALL = 1
        const val MENU_UNINSTALL = 2
        const val MENU_DETAILS = 3
        const val MENU_DELETE = 4
        const val MENU_VIEW = 5
    }

    inner class FileViewHolder(private val binding: ItemCgtFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CgtFileItem) {
            val primary = item.primaryTemplate
            // The card represents the .cgt FILE, so its title is the file's own name — not the
            // first bundled template's name. Tapping a multi-template card reveals every template.
            binding.tvTemplateName.text = item.displayName
            val versionText = versionLabel(primary.version)
            binding.tvTemplateVersion.text = versionText
            binding.tvTemplateVersion.visibility = if (versionText.isBlank()) View.GONE else View.VISIBLE
            // The old separate filename line is now redundant with the title.
            binding.tvFileName.visibility = View.GONE

            // Long-press any card to show the plugin's help tooltip (COGO convention).
            binding.root.setOnLongClickListener { anchor -> onLongPress(anchor); true }

            if (item.hasMultipleTemplates) {
                // A single template's description would misrepresent a multi-template file.
                binding.tvTemplateDesc.visibility = View.GONE
                binding.tvMultiTemplate.visibility = View.VISIBLE
                binding.tvMultiTemplate.text = "Contains ${item.templates.size} templates"
                binding.root.setOnClickListener { onViewTemplates(item) }
            } else {
                binding.tvTemplateDesc.visibility = View.VISIBLE
                binding.tvTemplateDesc.text = primary.description
                binding.tvMultiTemplate.visibility = View.GONE
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            }

            if (item.installed) {
                binding.tvStatus.text = "Installed"
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.status_success_text)
                )
            } else {
                binding.tvStatus.text = "Not installed"
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.status_error_text)
                )
            }

            binding.btnMenu.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor, Gravity.END, 0, R.style.PopupMenuStyle)
                if (item.installed) {
                    popup.menu.add(0, MENU_UNINSTALL, 0, "Uninstall")
                } else {
                    popup.menu.add(0, MENU_INSTALL, 0, "Install")
                }
                if (item.hasMultipleTemplates) {
                    popup.menu.add(0, MENU_VIEW, 1, "View templates")
                } else {
                    popup.menu.add(0, MENU_DETAILS, 1, "Details")
                }
                if (!item.installed) {
                    popup.menu.add(0, MENU_DELETE, 2, "Delete")
                }
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        MENU_INSTALL -> onInstall(item)
                        MENU_UNINSTALL -> onUninstall(item)
                        MENU_DETAILS -> onDetails(item)
                        MENU_DELETE -> onDelete(item)
                        MENU_VIEW -> onViewTemplates(item)
                    }
                    true
                }
                popup.show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemCgtFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
