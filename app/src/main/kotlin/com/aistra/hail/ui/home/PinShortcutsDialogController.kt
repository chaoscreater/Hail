package com.aistra.hail.ui.home

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.NameComparator
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textview.MaterialTextView

/**
 * Drives the "pick apps to shortcut" dialog (layout `dialog_home_shortcuts`) — the same three-tab
 * (All apps / Selected / Unselected) picker used both for Home screen shortcuts and for Dex app
 * shortcuts. Which [AppInfo] boolean flag it toggles is supplied via [getFlag]/[setFlag] so both
 * call sites share one implementation instead of drifting apart.
 */
class PinShortcutsDialogController(
    private val fragment: Fragment,
    private val titleRes: Int,
    private val getFlag: (AppInfo) -> Boolean,
    private val setFlag: (AppInfo, Boolean) -> Unit,
    private val onSelectionChanged: () -> Unit = {}
) {
    private val activity: MainActivity get() = fragment.requireActivity() as MainActivity
    private val layoutInflater get() = fragment.layoutInflater

    // Batch pin state — one shortcut is requested at a time; onResume advances the queue
    // when Hail regains focus after the user has handled the launcher's pin dialog.
    private val shortcutQueue = ArrayDeque<AppInfo>()
    private var batchPinActive = false
    // Set to true immediately after requestPinShortcut fires so we know onResume
    // is returning from the launcher pin dialog (not from some unrelated resume).
    private var waitingForLauncherReturn = false

    fun show() {
        val allApps = HailData.checkedList
            .filter { it.applicationInfo != null }
            .sortedWith(NameComparator)

        val dialogView = layoutInflater.inflate(R.layout.dialog_home_shortcuts, null)
        val tabLayout = dialogView.findViewById<TabLayout>(R.id.shortcut_tabs)
        val searchEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.home_shortcuts_list)
        val btnContainer = dialogView.findViewById<View>(R.id.btn_container)
        val btnSelectAll = dialogView.findViewById<MaterialButton>(R.id.btn_select_all)
        val btnDeselectAll = dialogView.findViewById<MaterialButton>(R.id.btn_deselect_all)

        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_all_apps))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_selected))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_unselected))

        // Tab 1 ("All Apps"): persistent flag
        val allAppsSelected = BooleanArray(allApps.size) { getFlag(allApps[it]) }
        val allAppsAdapter = AllAppsShortcutsAdapter(allApps, allAppsSelected)

        // Tab 2 ("Selected"): transient selection for which to actually pin now
        var selectedApps = allApps.filterIndexed { i, _ -> allAppsSelected[i] }
        var pinNow = BooleanArray(selectedApps.size) { true }
        var selectedAdapter = SelectedAppsShortcutsAdapter(selectedApps, pinNow)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = allAppsAdapter
        btnContainer.isVisible = false

        // Keep the "All apps" checkboxes correct after a change made from the Unselected tab
        fun syncAllAppsSelection() {
            allApps.forEachIndexed { i, info -> allAppsSelected[i] = getFlag(info) }
            allAppsAdapter.notifyDataSetChanged()
        }

        // Rebuild the "Selected" tab data from the current flag state
        fun rebuildSelectedTab() {
            selectedApps = allApps.filter { getFlag(it) }
            pinNow = BooleanArray(selectedApps.size) { true }
            selectedAdapter = SelectedAppsShortcutsAdapter(selectedApps, pinNow)
        }

        // Tab 3 ("Unselected"): only apps that have not been added to the selection.
        // Selecting an app removes it from the adapter in place (no adapter swap), so the
        // RecyclerView scroll position is preserved instead of jumping back to the top.
        fun newUnselectedAdapter() = UnselectedAppsShortcutsAdapter(
            allApps.filterNot { getFlag(it) }
        ).apply {
            onAppSelected = {
                syncAllAppsSelection()
                rebuildSelectedTab()
            }
        }
        var unselectedAdapter = newUnselectedAdapter()
        fun rebuildUnselectedTab() {
            unselectedAdapter = newUnselectedAdapter()
        }

        allAppsAdapter.onSelectionChanged = {
            rebuildSelectedTab()
            rebuildUnselectedTab()
        }

        var currentTab = 0
        var currentQuery = ""

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                when (currentTab) {
                    0 -> {
                        btnContainer.isVisible = false
                        recyclerView.adapter = allAppsAdapter
                        allAppsAdapter.filter(currentQuery)
                    }
                    1 -> {
                        rebuildSelectedTab()
                        btnContainer.isVisible = true
                        recyclerView.adapter = selectedAdapter
                        selectedAdapter.filter(currentQuery)
                    }
                    else -> {
                        rebuildUnselectedTab()
                        btnContainer.isVisible = false
                        recyclerView.adapter = unselectedAdapter
                        unselectedAdapter.filter(currentQuery)
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""
                when (currentTab) {
                    0 -> allAppsAdapter.filter(currentQuery)
                    1 -> selectedAdapter.filter(currentQuery)
                    else -> unselectedAdapter.filter(currentQuery)
                }
            }
        })
        btnSelectAll.setOnClickListener { selectedAdapter.selectAll() }
        btnDeselectAll.setOnClickListener { selectedAdapter.deselectAll() }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(dialogView)
            .setPositiveButton(R.string.action_add_pin_shortcut) { _, _ ->
                val toAdd = selectedApps.filterIndexed { i, _ -> pinNow[i] }
                startBatchPinShortcuts(toAdd)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        // Hide the positive button until the user switches to the Selected tab
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isVisible = false
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (fragment.resources.displayMetrics.heightPixels * 0.95).toInt()
        )

        // Wire tab changes to show/hide the positive button
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isVisible =
                    tab.position == 1
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * Starts a sequential batch-pin flow. One [HShortcuts.addPinShortcut] request is made at a
     * time; [onResume] advances the queue after Hail regains focus from the launcher's dialog.
     */
    private fun startBatchPinShortcuts(apps: List<AppInfo>) {
        if (apps.isEmpty()) return
        shortcutQueue.clear()
        shortcutQueue.addAll(apps)
        batchPinActive = true
        waitingForLauncherReturn = false
        pinNextInQueue()
    }

    private fun pinNextInQueue() {
        val info = shortcutQueue.removeFirstOrNull() ?: run {
            batchPinActive = false
            waitingForLauncherReturn = false
            return
        }
        val intent = HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, info.packageName)
            .putExtra(HailData.KEY_ENABLE_BLUETOOTH, info.enableBluetooth)
            .putExtra(HailData.KEY_ENABLE_LOCATION, info.enableLocation)
        HShortcuts.addPinShortcut(info, info.packageName, info.name, intent)
        // Mark that we're now waiting for Hail to regain focus after the launcher dialog.
        waitingForLauncherReturn = true
    }

    /** Call from the host fragment's `onResume`. */
    fun onResume() {
        // If we fired a requestPinShortcut and the user has now returned to Hail
        // (confirmed or dismissed the launcher's pin dialog), advance to the next shortcut.
        if (batchPinActive && waitingForLauncherReturn) {
            waitingForLauncherReturn = false
            if (shortcutQueue.isNotEmpty()) {
                pinNextInQueue()
            } else {
                batchPinActive = false
            }
        }
    }

    /** Call from the host fragment's `onDestroyView`. */
    fun onDestroy() {
        shortcutQueue.clear()
        batchPinActive = false
        waitingForLauncherReturn = false
    }

    /** Tab 1 adapter: all apps with persistent checkboxes bound to [getFlag]/[setFlag]. */
    private inner class AllAppsShortcutsAdapter(
        private val apps: List<AppInfo>,
        private val selected: BooleanArray
    ) : RecyclerView.Adapter<AllAppsShortcutsAdapter.VH>() {

        var onSelectionChanged: (() -> Unit)? = null
        private var displayed: List<IndexedValue<AppInfo>> = apps.withIndex().toList()

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: MaterialTextView = view.findViewById(R.id.app_name)
            val pkg: MaterialTextView = view.findViewById(R.id.app_desc)
            val check: MaterialCheckBox = view.findViewById(R.id.app_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_apps, parent, false))

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (srcIdx, info) = displayed[position]
            holder.name.text = info.name
            holder.pkg.text = info.packageName
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = selected[srcIdx]
            info.applicationInfo?.let {
                AppIconCache.loadIconBitmapAsync(
                    fragment.requireContext(), it, myUserId, holder.icon,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: holder.icon.setImageDrawable(fragment.requireContext().packageManager.defaultActivityIcon)
            val toggle = {
                selected[srcIdx] = !selected[srcIdx]
                holder.check.isChecked = selected[srcIdx]
                setFlag(info, selected[srcIdx])
                HailData.saveApps()
                onSelectionChanged?.invoke()
                this@PinShortcutsDialogController.onSelectionChanged()
            }
            holder.view.setOnClickListener { toggle() }
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked != selected[srcIdx]) {
                    selected[srcIdx] = checked
                    setFlag(info, checked)
                    HailData.saveApps()
                    onSelectionChanged?.invoke()
                    this@PinShortcutsDialogController.onSelectionChanged()
                }
            }
        }

        fun filter(query: String) {
            displayed = if (query.isBlank()) apps.withIndex().toList()
            else apps.withIndex().filter { (_, app) ->
                app.name.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }.toList()
            notifyDataSetChanged()
        }
    }

    /** Tab 2 adapter: only selected apps, with transient checkboxes for pinning now. */
    private inner class SelectedAppsShortcutsAdapter(
        private val apps: List<AppInfo>,
        private val pinNow: BooleanArray
    ) : RecyclerView.Adapter<SelectedAppsShortcutsAdapter.VH>() {

        private var displayed: List<IndexedValue<AppInfo>> = apps.withIndex().toList()

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: MaterialTextView = view.findViewById(R.id.app_name)
            val pkg: MaterialTextView = view.findViewById(R.id.app_desc)
            val check: MaterialCheckBox = view.findViewById(R.id.app_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_apps, parent, false))

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (srcIdx, info) = displayed[position]
            holder.name.text = info.name
            holder.pkg.text = info.packageName
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = pinNow[srcIdx]
            info.applicationInfo?.let {
                AppIconCache.loadIconBitmapAsync(
                    fragment.requireContext(), it, myUserId, holder.icon,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: holder.icon.setImageDrawable(fragment.requireContext().packageManager.defaultActivityIcon)
            val toggle = {
                pinNow[srcIdx] = !pinNow[srcIdx]
                holder.check.isChecked = pinNow[srcIdx]
            }
            holder.view.setOnClickListener { toggle() }
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked != pinNow[srcIdx]) {
                    pinNow[srcIdx] = checked
                }
            }
        }

        fun filter(query: String) {
            displayed = if (query.isBlank()) apps.withIndex().toList()
            else apps.withIndex().filter { (_, app) ->
                app.name.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }.toList()
            notifyDataSetChanged()
        }

        fun selectAll() {
            pinNow.indices.forEach { pinNow[it] = true }
            notifyDataSetChanged()
        }

        fun deselectAll() {
            pinNow.indices.forEach { pinNow[it] = false }
            notifyDataSetChanged()
        }
    }

    /**
     * Tab 3 adapter: apps not yet selected. Selecting one flags it and removes it from this
     * list in place via [notifyItemRemoved], instead of swapping adapters, so the scroll
     * position doesn't reset.
     */
    private inner class UnselectedAppsShortcutsAdapter(
        initialApps: List<AppInfo>
    ) : RecyclerView.Adapter<UnselectedAppsShortcutsAdapter.VH>() {

        var onAppSelected: (() -> Unit)? = null
        private val source: MutableList<AppInfo> = initialApps.toMutableList()
        private var displayed: MutableList<AppInfo> = source.toMutableList()

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: MaterialTextView = view.findViewById(R.id.app_name)
            val pkg: MaterialTextView = view.findViewById(R.id.app_desc)
            val check: MaterialCheckBox = view.findViewById(R.id.app_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_apps, parent, false))

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = displayed[position]
            holder.name.text = info.name
            holder.pkg.text = info.packageName
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = false
            info.applicationInfo?.let {
                AppIconCache.loadIconBitmapAsync(
                    fragment.requireContext(), it, myUserId, holder.icon,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: holder.icon.setImageDrawable(fragment.requireContext().packageManager.defaultActivityIcon)
            val select = {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    setFlag(info, true)
                    HailData.saveApps()
                    source.remove(info)
                    displayed.removeAt(pos)
                    notifyItemRemoved(pos)
                    onAppSelected?.invoke()
                    this@PinShortcutsDialogController.onSelectionChanged()
                }
            }
            holder.view.setOnClickListener { select() }
            holder.check.setOnCheckedChangeListener { _, checked -> if (checked) select() }
        }

        fun filter(query: String) {
            displayed = (if (query.isBlank()) source
            else source.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }).toMutableList()
            notifyDataSetChanged()
        }
    }
}
