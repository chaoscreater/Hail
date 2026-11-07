package com.aistra.hail.ui.home

import android.text.InputFilter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogDescriptionBinding
import com.aistra.hail.databinding.DialogDescriptionImportBinding
import com.aistra.hail.databinding.ItemDescriptionImportBinding
import com.aistra.hail.utils.AppDescriptions
import com.aistra.hail.utils.HUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialogs for per-app descriptions: viewing/editing one app's description from the long-press menu,
 * and the copy-AI-prompt / import-reply flow from the Home menu.
 */
class AppDescriptionDialogs(private val fragment: Fragment, private val onChanged: () -> Unit) {
    private val activity get() = fragment.requireActivity()

    fun show(info: AppInfo) {
        MaterialAlertDialogBuilder(activity).setTitle(info.name)
            .setMessage(info.description.ifBlank { activity.getString(R.string.description_empty) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_edit) { _, _ -> edit(info) }.show()
    }

    private fun edit(info: AppInfo) {
        val binding = DialogDescriptionBinding.inflate(fragment.layoutInflater)
        binding.inputLayout.setHint(R.string.description_hint)
        binding.editText.filters = arrayOf(InputFilter.LengthFilter(AppDescriptions.MAX_LENGTH))
        binding.editText.setText(info.description)
        MaterialAlertDialogBuilder(activity).setTitle(info.name).setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                info.description = binding.editText.text.toString().trim()
                HailData.saveApps()
                onChanged()
                show(info)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Offers to copy the AI prompt for every app without a description (hidden apps only on request). */
    fun showPromptDialog() {
        val apps = AppDescriptions.appsNeedingDescription(false)
        val hiddenCount = AppDescriptions.hiddenAppsNeedingDescription()
        if (apps.isEmpty() && hiddenCount == 0) {
            HUI.showToast(R.string.description_prompt_none)
            return
        }
        val message = buildString {
            append(activity.getString(R.string.description_prompt_message, apps.size))
            if (hiddenCount > 0) append("\n").append(activity.getString(R.string.description_prompt_hidden_note, hiddenCount))
        }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(R.string.action_copy_ai_prompt)
            .setMessage(message).setNegativeButton(android.R.string.cancel, null)
        if (apps.isNotEmpty()) builder.setPositiveButton(R.string.description_prompt_copy) { _, _ -> copyPrompt(apps) }
        if (hiddenCount > 0) builder.setNeutralButton(R.string.description_prompt_copy_hidden) { _, _ ->
            copyPrompt(AppDescriptions.appsNeedingDescription(true))
        }
        builder.show()
    }

    private fun copyPrompt(apps: List<AppInfo>) {
        HUI.copyText(AppDescriptions.buildPrompt(apps))
        HUI.showToast(R.string.description_prompt_copied, apps.size.toString())
    }

    /** Asks before removing every app's description. */
    fun showClearDialog() {
        val count = HailData.checkedList.count { it.description.isNotBlank() }
        if (count == 0) {
            HUI.showToast(R.string.description_clear_none)
            return
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_clear_descriptions)
            .setMessage(activity.getString(R.string.description_clear_message, count))
            .setPositiveButton(R.string.description_clear_confirm) { _, _ ->
                HailData.checkedList.forEach { it.description = "" }
                HailData.saveApps()
                onChanged()
                HUI.showToast(R.string.description_cleared, count.toString())
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Step 1 of importing: paste the AI's reply. */
    fun showImportDialog() {
        val binding = DialogDescriptionImportBinding.inflate(fragment.layoutInflater)
        binding.inputLayout.setHint(R.string.description_paste_hint)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.action_import_descriptions)
            .setView(binding.root)
            .setPositiveButton(R.string.action_next) { _, _ ->
                showReviewDialog(binding.editText.text.toString(), binding.replaceExisting.isChecked)
            }
            .setNeutralButton(R.string.action_paste, null)
            .setNegativeButton(android.R.string.cancel, null).show()
        // Set after show() so Paste fills the box instead of closing the dialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            HUI.pasteText()?.let { binding.editText.setText(it) }
        }
    }

    /** Step 2 of importing: a scrolling list of new descriptions, all ticked, to confirm. */
    private fun showReviewDialog(text: String, replaceExisting: Boolean) {
        val entries = AppDescriptions.importable(AppDescriptions.parse(text), replaceExisting)
        if (entries.isEmpty()) {
            HUI.showToast(R.string.description_import_none, true)
            return
        }
        val ticked = BooleanArray(entries.size) { true }
        val adapter = ImportAdapter(entries, ticked)
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.description_import_title, entries.size))
            .setView(list)
            .setPositiveButton(R.string.action_import) { _, _ ->
                var count = 0
                entries.forEachIndexed { i, (info, description) ->
                    if (ticked[i]) {
                        info.description = description
                        count++
                    }
                }
                if (count > 0) {
                    HailData.saveApps()
                    onChanged()
                }
                HUI.showToast(R.string.msg_imported, count.toString())
            }
            .setNeutralButton(R.string.action_toggle_all, null)
            .setNegativeButton(android.R.string.cancel, null).show()
        // Set after show() so the toggle doesn't close the dialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            ticked.fill(!ticked.all { it })
            adapter.notifyDataSetChanged()
        }
    }

    private class ImportAdapter(
        private val entries: List<Pair<AppInfo, String>>,
        private val ticked: BooleanArray
    ) : RecyclerView.Adapter<ImportAdapter.VH>() {

        class VH(val binding: ItemDescriptionImportBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDescriptionImportBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (info, description) = entries[position]
            holder.binding.name.text = info.name
            holder.binding.description.text = description
            holder.binding.check.isChecked = ticked[position]
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                ticked[pos] = !ticked[pos]
                holder.binding.check.isChecked = ticked[pos]
            }
        }
    }
}
