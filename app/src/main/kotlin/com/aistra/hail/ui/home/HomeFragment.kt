package com.aistra.hail.ui.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.app.HailData.tags
import com.aistra.hail.databinding.FragmentHomeBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.NameComparator
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textview.MaterialTextView

class HomeFragment : MainFragment() {

    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()

    // Batch pin state — one shortcut is requested at a time; onResume advances the queue
    // when Hail regains focus after the user has handled the launcher's pin dialog.
    private val shortcutQueue = ArrayDeque<AppInfo>()
    private var batchPinActive = false
    // Set to true immediately after requestPinShortcut fires so we know onResume
    // is returning from the launcher pin dialog (not from some unrelated resume).
    private var waitingForLauncherReturn = false

    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        if (tags.size == 1) binding.tabs.isVisible = false
        binding.pager.adapter = HomeAdapter(this)
        // Keep all tag fragments alive so revisiting a tab requires no RecyclerView
        // rebind — DiffUtil sees no changes and the icons render from existing views instantly.
        binding.pager.offscreenPageLimit = tags.size.coerceAtLeast(1)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = tags[position].first
        }.attach()
        binding.tabs.applyDefaultInsetter { paddingRelative(isRtl, start = !activity.isLandscape, end = true) }

        // For tabs more than 1 position away, jump instantly so the scroll animation
        // doesn't sweep visually through every intermediate page's icons.
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (kotlin.math.abs(tab.position - binding.pager.currentItem) > 1) {
                    binding.pager.setCurrentItem(tab.position, false)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // When a page fully settles, refresh that page's fragment with the now-correct
        // tab position. This fixes the race where onResume() fires during animation
        // before TabLayoutMediator has updated selectedTabPosition.
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Load icons immediately when a tab is tapped, without waiting for the
                // scroll animation to finish. This makes distant tabs feel responsive.
                (childFragmentManager.findFragmentByTag("f$position") as? PagerFragment)
                    ?.updateCurrentList()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val pos = binding.pager.currentItem
                    (childFragmentManager.findFragmentByTag("f$pos") as? PagerFragment)
                        ?.updateCurrentList()
                }
            }
        })

        activity.fabHome.setOnClickListener { binding.pager.setCurrentItem(0, false) }
        activity.fabPinShortcuts.setOnClickListener { showPinShortcutsDialog() }

        // Pre-warm the icon cache for all checked apps so switching tag categories
        // shows icons instantly instead of waiting for them to load on demand.
        val appsToPreload = HailData.checkedList.mapNotNull { it.applicationInfo }
        AppIconCache.preloadIconsAsync(requireContext().applicationContext, appsToPreload, myUserId)

        return binding.root
    }

    private fun showPinShortcutsDialog() {
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
        val checkboxPrereqs = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_include_prereqs)

        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_all_apps))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_selected))

        // Tab 1 ("All Apps"): persistent addToHomeScreen flag
        val allAppsSelected = BooleanArray(allApps.size) { allApps[it].addToHomeScreen }
        val allAppsAdapter = AllAppsShortcutsAdapter(allApps, allAppsSelected)

        // Tab 2 ("Selected"): transient selection for which to actually pin now
        var selectedApps = allApps.filterIndexed { i, _ -> allAppsSelected[i] }
        var pinNow = BooleanArray(selectedApps.size) { true }
        var selectedAdapter = SelectedAppsShortcutsAdapter(selectedApps, pinNow)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = allAppsAdapter
        btnContainer.isVisible = false

        // Rebuild the "Selected" tab data from the current addToHomeScreen state
        fun rebuildSelectedTab() {
            selectedApps = allApps.filter { it.addToHomeScreen }
            pinNow = BooleanArray(selectedApps.size) { true }
            selectedAdapter = SelectedAppsShortcutsAdapter(selectedApps, pinNow)
        }

        allAppsAdapter.onSelectionChanged = {
            // Keep allAppsSelected in sync for the positive button
            rebuildSelectedTab()
        }

        var currentTab = 0
        var currentQuery = ""

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                if (currentTab == 0) {
                    btnContainer.isVisible = false
                    checkboxPrereqs.isVisible = false
                    recyclerView.adapter = allAppsAdapter
                    allAppsAdapter.filter(currentQuery)
                } else {
                    rebuildSelectedTab()
                    btnContainer.isVisible = true
                    val hasPrereqs = selectedApps.any { !it.prereqPackage.isNullOrEmpty() }
                    checkboxPrereqs.isVisible = hasPrereqs
                    recyclerView.adapter = selectedAdapter
                    selectedAdapter.filter(currentQuery)
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
                if (currentTab == 0) allAppsAdapter.filter(currentQuery)
                else selectedAdapter.filter(currentQuery)
            }
        })
        btnSelectAll.setOnClickListener { selectedAdapter.selectAll() }
        btnDeselectAll.setOnClickListener { selectedAdapter.deselectAll() }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_shortcuts)
            .setView(dialogView)
            .setPositiveButton(R.string.action_add_pin_shortcut) { _, _ ->
                val toAdd = selectedApps.filterIndexed { i, _ -> pinNow[i] }
                if (!checkboxPrereqs.isChecked) {
                    toAdd.filter { !it.prereqPackage.isNullOrEmpty() }.forEach {
                        it.prereqPackage = null
                        it.prereqLaunch = false
                        it.prereqEnable = false
                    }
                    HailData.saveApps()
                }
                startBatchPinShortcuts(toAdd)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        // Hide the positive button until the user switches to the Selected tab
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isVisible = false
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.95).toInt()
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
     * Starts a sequential batch-pin flow. One [requestPinShortcut] is made at a time;
     * [onResume] advances the queue after Hail regains focus from the launcher's dialog.
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
        HShortcuts.addPinShortcut(
            info, info.packageName, info.name,
            HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, info.packageName)
        )
        // Mark that we're now waiting for Hail to regain focus after the launcher dialog.
        waitingForLauncherReturn = true
    }

    override fun onResume() {
        super.onResume()
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

    /** Tab 1 adapter: all apps with persistent addToHomeScreen checkboxes. */
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
                    requireContext(), it, myUserId, holder.icon,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: holder.icon.setImageDrawable(requireContext().packageManager.defaultActivityIcon)
            val toggle = {
                selected[srcIdx] = !selected[srcIdx]
                holder.check.isChecked = selected[srcIdx]
                info.addToHomeScreen = selected[srcIdx]
                HailData.saveApps()
                onSelectionChanged?.invoke()
            }
            holder.view.setOnClickListener { toggle() }
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked != selected[srcIdx]) {
                    selected[srcIdx] = checked
                    info.addToHomeScreen = checked
                    HailData.saveApps()
                    onSelectionChanged?.invoke()
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
                    requireContext(), it, myUserId, holder.icon,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: holder.icon.setImageDrawable(requireContext().packageManager.defaultActivityIcon)
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

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        shortcutQueue.clear()
        batchPinActive = false
        waitingForLauncherReturn = false
        super.onDestroyView()
        _binding = null
    }
}
