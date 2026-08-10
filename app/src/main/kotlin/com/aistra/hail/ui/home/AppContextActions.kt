package com.aistra.hail.ui.home

import android.content.Intent
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailApi.addTag
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HUI
import com.aistra.hail.work.HWork
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray

/**
 * Tap-to-launch and long-press context-menu behavior shared by every screen that shows a grid
 * of [AppInfo] items ([PagerFragment]'s Home tag pages, the "Shortcuts (custom settings)" list,
 * and the "Dex apps" list) so all three stay pixel-for-pixel identical instead of drifting apart.
 *
 * [onTagListChanged] is only supplied by [PagerFragment], which needs to refresh its TabLayout
 * when a brand-new tag is created from within the tag-assign dialog; other hosts have no tab bar
 * to refresh and leave it null.
 */
class AppContextActions(
    private val fragment: Fragment,
    private val onListChanged: () -> Unit,
    private val onTagListChanged: (() -> Unit)? = null
) {
    private val activity: MainActivity get() = fragment.requireActivity() as MainActivity
    private val layoutInflater: LayoutInflater get() = fragment.layoutInflater

    fun onItemClick(info: AppInfo) {
        if (info.applicationInfo == null) {
            Snackbar.make(activity.fab, R.string.app_not_installed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_remove_home) { removeCheckedApp(info.packageName) }.show()
            return
        }
        launchApp(info.packageName)
    }

    fun onItemLongClick(
        info: AppInfo,
        selectedList: List<AppInfo> = emptyList(),
        onMultiSelectTrigger: (() -> Unit)? = null
    ): Boolean {
        if (info.applicationInfo == null && info !in selectedList) {
            exportToClipboard(listOf(info))
            return true
        }
        if (info in selectedList) {
            onMultiSelectTrigger?.invoke()
            return true
        }
        showItemMenu(info)
        return true
    }

    private fun showItemMenu(info: AppInfo) {
        val pkg = info.packageName
        val frozen = AppManager.isAppFrozen(pkg)
        val action = activity.getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
        MaterialAlertDialogBuilder(activity).setTitle(info.name).setItems(
            activity.resources.getStringArray(R.array.home_action_entries).filter {
                (it != activity.getString(R.string.action_freeze) || !frozen) && (it != activity.getString(R.string.action_unfreeze) || frozen) && (it != activity.getString(
                    R.string.action_pin
                ) || !info.pinned) && (it != activity.getString(R.string.action_unpin) || info.pinned) && (it != activity.getString(
                    R.string.action_whitelist
                ) || !info.whitelisted) && (it != activity.getString(R.string.action_remove_whitelist) || info.whitelisted) && (it != activity.getString(
                    R.string.action_unfreeze_remove_home
                ) || frozen)
            }.toTypedArray()
        ) { _, which ->
            when (which) {
                0 -> launchApp(pkg)
                1 -> setListFrozen(!frozen, listOf(info))
                2 -> {
                    val values = activity.resources.getIntArray(R.array.deferred_task_values)
                    val entries = arrayOfNulls<String>(values.size)
                    values.forEachIndexed { i, it ->
                        entries[i] = activity.resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
                    }
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.action_deferred_task)
                        .setItems(entries) { _, i ->
                            HWork.setDeferredFrozen(pkg, !frozen, values[i].toLong())
                            Snackbar.make(
                                activity.fab, activity.resources.getQuantityString(
                                    R.plurals.msg_deferred_task, values[i], values[i], action, info.name
                                ), Snackbar.LENGTH_INDEFINITE
                            ).setAction(R.string.action_undo) { HWork.cancelWork(pkg) }.show()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }

                3 -> {
                    info.pinned = !info.pinned
                    HailData.saveApps()
                    onListChanged()
                }

                4 -> {
                    info.whitelisted = !info.whitelisted
                    HailData.saveApps()
                    onListChanged()
                }

                5 -> tagDialog(info)
                6 -> tagDialog(info, HailData.lastUsedTagIds)

                7 -> if (HailData.tags.size > 1) MaterialAlertDialogBuilder(activity).setTitle(R.string.action_unfreeze_tag)
                    .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                        addPinShortcut(info, pkg,
                            HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg).addTag(HailData.tags[index].first))
                    }.setPositiveButton(R.string.action_skip) { _, _ ->
                        addPinShortcut(info, pkg,
                            HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg))
                    }.setNegativeButton(android.R.string.cancel, null).show()
                else addPinShortcut(info, pkg,
                    HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg))

                8 -> showPrerequisiteDialog(info)
                9 -> exportToClipboard(listOf(info))
                10 -> removeCheckedApp(pkg)
                11 -> {
                    setListFrozen(false, listOf(info), false)
                    if (!AppManager.isAppFrozen(pkg)) removeCheckedApp(pkg)
                }
            }
        }.setNeutralButton(R.string.action_details) { _, _ ->
            HUI.startActivity(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
            )
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun launchApp(packageName: String) {
        handlePrerequisiteApp(packageName)
        if (AppManager.isAppFrozen(packageName) && AppManager.setAppFrozen(packageName, false)) {
            onListChanged()
        }
        app.packageManager.getLaunchIntentForPackage(packageName)?.let {
            HShortcuts.addDynamicShortcut(packageName)
            fragment.startActivity(it)
        } ?: run {
            HUI.showToast(R.string.activity_not_found)
            HUI.notifyShizukuRequired(packageName)
        }
    }

    private fun handlePrerequisiteApp(packageName: String) {
        val appInfo = HailData.checkedList.find { it.packageName == packageName } ?: return
        val prereqPkg = appInfo.prereqPackage ?: return
        if ((appInfo.prereqLaunch || appInfo.prereqEnable) && AppManager.isAppFrozen(prereqPkg)) {
            if (AppManager.setAppFrozen(prereqPkg, false)) {
                app.setAutoFreezeService()
            }
        }
        if (appInfo.prereqLaunch) {
            app.packageManager.getLaunchIntentForPackage(prereqPkg)?.let { fragment.startActivity(it) }
        }
    }

    fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, updateList: Boolean = true
    ) {
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        } else if (HailData.workingMode == HailData.MODE_SHIZUKU_HIDE) {
            runCatching { HShizuku.isRoot }.onSuccess {
                if (!it) {
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.shizuku_hide_adb)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return
                }
            }
        }
        val filtered = list.filter { AppManager.isAppFrozen(it.packageName) != frozen }
        when (val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())) {
            null -> HUI.showToast(
                R.string.permission_denied_pkg,
                AppManager.lastDeniedPackage ?: activity.getString(R.string.permission_denied)
            )
            else -> {
                if (updateList) onListChanged()
                HUI.showToast(
                    if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result
                )
            }
        }
    }

    fun exportToClipboard(list: List<AppInfo>) {
        if (list.isEmpty()) return
        HUI.copyText(if (list.size > 1) JSONArray().run {
            list.forEach { put(it.packageName) }
            toString()
        } else list[0].packageName)
        HUI.showToast(
            R.string.msg_exported, if (list.size > 1) list.size.toString() else list[0].name
        )
    }

    fun removeCheckedApp(packageName: String, saveApps: Boolean = true) {
        HailData.removeCheckedApp(packageName, saveApps)
        if (saveApps) onListChanged()
    }

    private fun tagDialog(info: AppInfo, initialTagIds: List<Int>? = null) {
        val allTags = HailData.tags
        val selectedIds = initialTagIds ?: info.tagIdList
        val checkedItems = BooleanArray(allTags.size) { index ->
            allTags[index].second in selectedIds
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_tag_select, null)
        val searchEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.tag_list)
        val tagCheckAdapter = TagCheckAdapter(allTags, checkedItems)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = tagCheckAdapter
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { tagCheckAdapter.filter(s?.toString() ?: "") }
        })
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                info.tagIdList.clear()
                checkedItems.forEachIndexed { index, checked ->
                    if (checked) info.tagIdList.add(allTags[index].second)
                }
                val defaultTagId = 0
                if (info.tagIdList.isEmpty()) {
                    // Nothing selected — restore Default tag instead of removing the app
                    info.tagIdList.add(defaultTagId)
                } else if (info.tagIdList.size > 1 || info.tagIdList.first() != defaultTagId) {
                    // Assigned to at least one real tag — remove Default tag if present
                    info.tagIdList.remove(defaultTagId)
                }
                HailData.lastUsedTagIds = info.tagIdList.toList()
                HailData.saveApps()
                onListChanged()
            }.setNeutralButton(R.string.action_tag_add) { _, _ ->
                addTagDialog(listOf(info)) { tagDialog(info) }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** "Add tag" dialog — creates a brand-new tag and re-opens [onCreated] once it's added. */
    fun addTagDialog(list: List<AppInfo>, onCreated: () -> Unit) {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.tag)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_tag_add)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val tagName = binding.editText.text.toString()
                val tagId = tagName.hashCode()
                if (HailData.tags.any { it.first == tagName || it.second == tagId }) return@setPositiveButton
                HailData.tags.add(tagName to tagId)
                onTagListChanged?.invoke()
                onCreated()
                HailData.saveTags()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TagCheckAdapter(
        private val tags: List<Pair<String, Int>>,
        private val checked: BooleanArray
    ) : RecyclerView.Adapter<TagCheckAdapter.VH>() {

        private var displayed: List<IndexedValue<Pair<String, Int>>> = tags.withIndex().toList()

        inner class VH(val checkBox: com.google.android.material.checkbox.MaterialCheckBox) :
            RecyclerView.ViewHolder(checkBox)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_tag_check, parent, false)
                as com.google.android.material.checkbox.MaterialCheckBox)

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (srcIdx, tag) = displayed[position]
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.text = tag.first
            holder.checkBox.isChecked = checked[srcIdx]
            holder.checkBox.setOnCheckedChangeListener { _, isChecked -> checked[srcIdx] = isChecked }
        }

        fun filter(query: String) {
            displayed = if (query.isBlank()) tags.withIndex().toList()
            else tags.withIndex().filter { (_, tag) -> tag.first.contains(query, ignoreCase = true) }.toList()
            notifyDataSetChanged()
        }
    }

    private fun addPinShortcut(info: AppInfo, pkg: String, shortcutIntent: Intent) {
        shortcutIntent.putExtra(HailData.KEY_ENABLE_BLUETOOTH, info.enableBluetooth)
        shortcutIntent.putExtra(HailData.KEY_ENABLE_LOCATION, info.enableLocation)
        HShortcuts.addPinShortcut(info, pkg, info.name, shortcutIntent)
    }

    fun showPrerequisiteDialog(info: AppInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_prerequisite, null)
        val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_text)
        val checkboxLaunch = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_launch)
        val checkboxEnable = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_enable)
        val checkboxBluetooth = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_enable_bluetooth)
        val checkboxLocation = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkbox_enable_location)

        // Pre-fill with existing prereq config if any
        info.prereqPackage?.let { editText.setText(it) }
        checkboxLaunch.isChecked = info.prereqLaunch
        checkboxEnable.isChecked = info.prereqEnable
        checkboxBluetooth.isChecked = info.enableBluetooth
        checkboxLocation.isChecked = info.enableLocation

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.prerequisite_app_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val prereqPkg = editText.text?.toString()?.trim().orEmpty()
                if (prereqPkg.isNotEmpty() && (checkboxLaunch.isChecked || checkboxEnable.isChecked)) {
                    info.prereqPackage = prereqPkg
                    info.prereqLaunch = checkboxLaunch.isChecked
                    info.prereqEnable = checkboxEnable.isChecked
                } else {
                    info.prereqPackage = null
                    info.prereqLaunch = false
                    info.prereqEnable = false
                }
                info.enableBluetooth = checkboxBluetooth.isChecked
                info.enableLocation = checkboxLocation.isChecked
                HailData.saveApps()
                onListChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
