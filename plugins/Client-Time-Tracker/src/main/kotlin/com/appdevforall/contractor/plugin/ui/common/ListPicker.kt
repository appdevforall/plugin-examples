package com.appdevforall.contractor.plugin.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.appdevforall.contractor.plugin.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Fragment.openListPicker(
    title: CharSequence,
    items: List<String>,
    selectedIndex: Int = -1,
    onPick: (index: Int, value: String) -> Unit
) {
    requireActivity().openListPicker(title, items, selectedIndex, onPick)
}

fun FragmentActivity.openListPicker(
    title: CharSequence,
    items: List<String>,
    selectedIndex: Int = -1,
    onPick: (index: Int, value: String) -> Unit
) {
    if (items.isEmpty()) return
    val initial = if (selectedIndex in items.indices) selectedIndex else -1
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setSingleChoiceItems(items.toTypedArray(), initial) { dialog, which ->
            onPick(which, items[which])
            dialog.dismiss()
        }
        .setNegativeButton(R.string.action_cancel, null)
        .show()
}
