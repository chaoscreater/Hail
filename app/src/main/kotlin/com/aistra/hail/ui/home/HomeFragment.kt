package com.aistra.hail.ui.home

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
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

    companion object {
        private const val ACTION_SHORTCUT_PINNED = "com.aistra.hail.action.SHORTCUT_PINNED"
    }

    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()

    // Queue for sequential pin-shortcut requests — populated when the user taps
    // "Add to Home screen" in the XYZ dialog. Each accepted shortcut fires the
    // ACTION_SHORTCUT_PINNED broadcast which advances the queue.
    private val shortcutQueue = ArrayDeque<AppInfo>()
    private var shortcutReceiver: BroadcastReceiver? = null
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
        val apps = HailData.checkedList
            .filter { it.applicationInfo != null }
            .sortedWith(NameComparator)

        val selected = BooleanArray(apps.size) { apps[it].addToHomeScreen }

        val dialogView = layoutInflater.inflate(R.layout.dialog_home_shortcuts, null)
        val searchEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.home_shortcuts_list)
        val btnSelectAll = dialogView.findViewById<MaterialButton>(R.id.btn_select_all)
        val btnDeselectAll = dialogView.findViewById<MaterialButton>(R.id.btn_deselect_all)

        val shortcutsAdapter = HomeShortcutsAdapter(apps, selected)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = shortcutsAdapter

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                shortcutsAdapter.filter(s?.toString() ?: "")
            }
        })
        btnSelectAll.setOnClickListener { shortcutsAdapter.selectAll() }
        btnDeselectAll.setOnClickListener { shortcutsAdapter.deselectAll() }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_shortcuts)
            .setView(dialogView)
            .setPositiveButton(R.string.action_add_pin_shortcut) { _, _ ->
                val toAdd = apps.filterIndexed { i, _ -> selected[i] }
                startBatchPinShortcuts(toAdd)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().also { dialog ->
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.95).toInt()
                )
            }
    }

    /**
     * Fires [requestPinShortcut] for each app in [apps] one at a time.
     * Each accepted shortcut broadcasts [ACTION_SHORTCUT_PINNED] which advances the queue
     * so the next dialog appears only after the user has handled the current one.
     * Dismissing (not accepting) a dialog stops the chain at that point.
     */
    private fun startBatchPinShortcuts(apps: List<AppInfo>) {
        if (apps.isEmpty()) return
        shortcutQueue.clear()
        shortcutQueue.addAll(apps)
        if (shortcutReceiver == null) {
            shortcutReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    processNextShortcut()
                }
            }
            ContextCompat.registerReceiver(
                requireContext(), shortcutReceiver!!,
                IntentFilter(ACTION_SHORTCUT_PINNED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        processNextShortcut()
    }

    private fun processNextShortcut() {
        if (shortcutQueue.isEmpty()) {
            shortcutReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
            shortcutReceiver = null
            return
        }
        val info = shortcutQueue.removeFirst()
        val callbackIntent = Intent(ACTION_SHORTCUT_PINNED).setPackage(requireContext().packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            requireContext(), 0, callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        HShortcuts.addPinShortcut(
            info, info.packageName, info.name,
            HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, info.packageName),
            pendingIntent.intentSender
        )
    }

    private inner class HomeShortcutsAdapter(
        private val apps: List<AppInfo>,
        private val selected: BooleanArray
    ) : RecyclerView.Adapter<HomeShortcutsAdapter.VH>() {

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
            }
            holder.view.setOnClickListener { toggle() }
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked != selected[srcIdx]) {
                    selected[srcIdx] = checked
                    info.addToHomeScreen = checked
                    HailData.saveApps()
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
            apps.forEachIndexed { i, info ->
                selected[i] = true
                info.addToHomeScreen = true
            }
            HailData.saveApps()
            notifyDataSetChanged()
        }

        fun deselectAll() {
            apps.forEachIndexed { i, info ->
                selected[i] = false
                info.addToHomeScreen = false
            }
            HailData.saveApps()
            notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        shortcutReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        shortcutReceiver = null
        shortcutQueue.clear()
        super.onDestroyView()
        _binding = null
    }
}
