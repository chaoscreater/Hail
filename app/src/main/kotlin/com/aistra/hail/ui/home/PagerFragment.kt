package com.aistra.hail.ui.home

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.*
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener,
    MenuProvider {

    companion object {
        private const val APP_TYPE_ALL = 0
        private const val APP_TYPE_USER = 1
        private const val APP_TYPE_SYSTEM = 2
    }

    private var query: String = String()
    /** 0 = all apps, 1 = user apps only, 2 = system apps only */
    private var appTypeFilter: Int = APP_TYPE_ALL
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private lateinit var tagResultAdapter: TagResultAdapter
    private var searchItem: MenuItem? = null
    private var scrollToTopOnNextUpdate = false
    private var multiselect: Boolean
        set(value) {
            (parentFragment as HomeFragment).multiselect = value
        }
        get() = (parentFragment as HomeFragment).multiselect
    private val selectedList get() = (parentFragment as HomeFragment).selectedList
    private val tabs: TabLayout get() = (parentFragment as HomeFragment).binding.tabs
    private val adapter get() = (parentFragment as HomeFragment).binding.pager.adapter as HomeAdapter
    private val tag: Pair<String, Int> get() = HailData.tags[tabs.selectedTabPosition]
    private val actions by lazy {
        AppContextActions(this, onListChanged = { updateCurrentList() }, onTagListChanged = {
            adapter.notifyItemInserted(adapter.itemCount - 1)
            if (query.isEmpty() && tabs.tabCount == 2) tabs.isVisible = true
        }, jumpToTagCategoryVisible = { query.isNotEmpty() }, jumpToTagCategoryAction = { info ->
            jumpToTab(HailData.tags.indexOfFirst { it.second in info.tagIdList })
        })
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(selectedList).apply {
            onItemClickListener = this@PagerFragment
            onItemLongClickListener = this@PagerFragment
        }
        tagResultAdapter = TagResultAdapter { tagResult -> jumpToTab(HailData.tags.indexOf(tagResult)) }
        binding.recyclerView.run {
            val spanCount = resources.getInteger(
                if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
            )
            val gridLayoutManager = GridLayoutManager(activity, spanCount)
            // Tag-result rows span the full grid width instead of a single narrow column — the
            // tag name (not an icon) is the only thing identifying that tile, so it needs room.
            gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (position < tagResultAdapter.itemCount) spanCount else 1
            }
            layoutManager = gridLayoutManager
            adapter = ConcatAdapter(tagResultAdapter, pagerAdapter)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> activity.fab.run {
                            postDelayed({
                                if (tag == true) {
                                    show()
                                    activity.fabWhitelist.show()
                                    activity.fabPinShortcuts.show()
                                }
                            }, 1000)
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            activity.fab.hide()
                            activity.fabWhitelist.hide()
                            activity.fabPinShortcuts.hide()
                        }
                    }
                }
            })
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
            activity.fabContainer.doOnLayout { container ->
                val lp = container.layoutParams as ViewGroup.MarginLayoutParams
                updatePadding(bottom = paddingBottom + container.height + lp.bottomMargin)
            }
        }

        binding.refresh.apply {
            setOnRefreshListener {
                updateCurrentList()
                binding.refresh.isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateCurrentList()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        tabs.getTabAt(tabs.selectedTabPosition)?.view?.setOnLongClickListener {
            if (isResumed) manageTagDialog()
            true
        }
        activity.fab.setOnClickListener {
            actions.setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })
        }
        activity.fab.setOnLongClickListener {
            actions.setListFrozen(true)
            true
        }
        activity.fabWhitelist.setOnClickListener { showWhitelistDialog() }
    }

    internal fun updateCurrentList() {
        val apps = HailData.checkedList.filter {
            if (query.isEmpty()) tag.second in it.tagIdList
            else matchesSearchQuery(query, it.packageName, it.name.toString())
        }.filter {
            when (appTypeFilter) {
                APP_TYPE_USER -> it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) == 0
                APP_TYPE_SYSTEM -> it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
                else -> true
            }
        }.filter {
            HailData.showUninstalled || it.applicationInfo != null
        }.let { AppSort.sort(it, HailData.sortByScreen(HailData.SORT_SCREEN_HOME)) }
        // Tab categories whose name matches the active search, shown as leading "<name> (category)"
        // tiles (via tagResultAdapter, see onCreateView's ConcatAdapter). Empty while not searching.
        // Deliberately plain substring + pinyin matching, not the full matchesSearchQuery fuzzy/T9
        // logic used for apps: that's a Levenshtein subsequence match (e.g. "face" matching
        // "Finance" — F,A,C,E all appear in order within edit-distance tolerance), tolerable when
        // buried among many app results but too surprising for a single, prominent leading tile.
        val tagMatches = if (query.isEmpty()) emptyList()
        else HailData.tags.filter {
            it.first.contains(query, ignoreCase = true) || PinyinSearch.searchPinyinAll(it.first, query)
        }
        // Both lists factor into the empty-state check: fragment_pager.xml stacks "Nothing here"
        // directly over the RecyclerView, so a query matching only a tag (zero apps) must not show
        // it — that would overlap a still-visible, still-tappable tag tile.
        binding.empty.isVisible = apps.isEmpty() && tagMatches.isEmpty()
        tagResultAdapter.submitList(tagMatches)
        pagerAdapter.submitList(apps) {
            if (scrollToTopOnNextUpdate) {
                binding.recyclerView.scrollToPosition(0)
                scrollToTopOnNextUpdate = false
            }
        }
        app.setAutoFreezeService()
    }

    /** Instant jump (no smooth-scroll sweep), matching the existing fabHome "jump to Home tab" convention. */
    private fun jumpToTab(index: Int) {
        if (index !in HailData.tags.indices) return
        // Reuses the existing onMenuItemActionCollapse reset below (query="", tabs restored, list refreshed).
        searchItem?.collapseActionView()
        (parentFragment as HomeFragment).binding.pager.setCurrentItem(index, false)
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title =
            if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.app_name)
    }

    override fun onItemClick(info: AppInfo) {
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            updateCurrentList()
            updateBarTitle()
            return
        }
        actions.onItemClick(info)
    }

    override fun onItemLongClick(info: AppInfo): Boolean =
        actions.onItemLongClick(info, selectedList) { onMultiSelect() }

    private fun onMultiSelect() = actions.showMultiSelectDialog(selectedList, { pagerAdapter.currentList }) {
        updateCurrentList()
        updateBarTitle()
    }

    /** "Rename tag + manage apps" dialog, opened by long-pressing a tab. */
    private fun manageTagDialog() {
        val position = tabs.selectedTabPosition
        val currentTag = HailData.tags[position]
        val currentTagId = currentTag.second

        // Build the view with ViewBinding equivalent via inflate
        val dialogView = layoutInflater.inflate(R.layout.dialog_tag_manage, null)
        val tagNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.input_layout)
        val tagNameEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_text)
        val searchEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_text)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.app_list)

        tagNameInput.hint = getString(R.string.tag)
        tagNameEdit.setText(currentTag.first)

        // Build full app list: all checked apps sorted by name, excluding hidden apps, with checked state for this tag
        val allApps = HailData.checkedList
            .filter { it.packageName !in HailData.hiddenApps }
            .sortedWith(NameComparator)
            .toMutableList()
        // Track which ones are assigned to this tag (working copy)
        val tagAssigned = allApps.map { currentTagId in it.tagIdList }.toBooleanArray()

        // Simple adapter for the list
        val tagAppAdapter = TagAppAssignAdapter(allApps, tagAssigned)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = tagAppAdapter

        // Wire up "Show all apps" toggle
        val showAllCheck = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_show_all_apps)
        showAllCheck.setOnCheckedChangeListener { _, checked ->
            tagAppAdapter.setShowAll(checked)
        }

        // Wire up search filtering
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tagAppAdapter.filter(s?.toString() ?: "")
            }
        })

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_tag_set)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Apply rename
                val newName = tagNameEdit.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentTag.first) {
                    val newTagId = if (position == 0) 0 else newName.hashCode()
                    if (!HailData.tags.any { it.first == newName || (it.second == newTagId && it.second != currentTagId) }) {
                        HailData.tags[position] = newName to newTagId
                        if (position != 0 && newTagId != currentTagId) {
                            HailData.checkedList.forEach {
                                val idx = it.tagIdList.indexOf(currentTagId)
                                if (idx != -1) it.tagIdList[idx] = newTagId
                            }
                        }
                        adapter.notifyItemChanged(position)
                        HailData.saveTags()
                    }
                }
                // Apply app-tag assignments from the adapter's working state
                tagAppAdapter.applyAssignments(currentTagId)
                HailData.saveApps()
                updateCurrentList()
            }

        // Only show "Remove tag" for non-default tabs
        if (position != 0) {
            builder.setNeutralButton(R.string.action_tag_remove) { _, _ ->
                val defaultTagId = 0
                pagerAdapter.currentList.forEach { info ->
                    if (info.tagIdList.remove(currentTagId) && info.tagIdList.isEmpty()) {
                        // App lost its only tag — restore Default instead of removing it
                        info.tagIdList.add(defaultTagId)
                    }
                }
                HailData.tags.removeAt(position)
                adapter.notifyItemRemoved(position)
                if (tabs.tabCount == 1) tabs.isVisible = false
                HailData.saveApps()
                HailData.saveTags()
            }
        }

        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Shows a dialog listing all whitelisted apps across all tags for selective freezing. */
    private fun showWhitelistDialog() {
        val whitelistedApps = HailData.checkedList
            .filter { it.whitelisted && it.applicationInfo != null }
            .sortedWith(compareBy<AppInfo> { AppManager.isAppFrozen(it.packageName) }.then(NameComparator))

        if (whitelistedApps.isEmpty()) {
            HUI.showToast(R.string.msg_no_whitelisted_apps)
            return
        }

        val selected = BooleanArray(whitelistedApps.size)
        val removeWhitelist = BooleanArray(whitelistedApps.size)
        val dialogView = layoutInflater.inflate(R.layout.dialog_whitelist, null)
        val searchEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_text)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.whitelist_app_list)
        val btnSelectAll = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_select_all)
        val btnDeselectAll = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_deselect_all)

        val whitelistAdapter = WhitelistFreezeAdapter(whitelistedApps, selected, removeWhitelist)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = whitelistAdapter

        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                whitelistAdapter.filter(s?.toString() ?: "")
            }
        })
        btnSelectAll.setOnClickListener { whitelistAdapter.selectAll() }
        btnDeselectAll.setOnClickListener { whitelistAdapter.deselectAll() }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.whitelisted_apps)
            .setView(dialogView)
            .setPositiveButton(R.string.action_process) { _, _ ->
                val toFreeze = whitelistedApps.filterIndexed { i, _ -> selected[i] }
                if (toFreeze.isNotEmpty()) actions.setListFrozen(true, toFreeze)
                val anyRemoved = removeWhitelist.any { it }
                if (anyRemoved) {
                    whitelistedApps.forEachIndexed { i, info ->
                        if (removeWhitelist[i]) info.whitelisted = false
                    }
                    HailData.saveApps()
                    updateCurrentList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().also { dialog ->
                dialog.window?.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.95).toInt()
                )
            }
    }

    /** Adapter for the whitelisted-apps freeze dialog. */
    private inner class WhitelistFreezeAdapter(
        private val apps: List<AppInfo>,
        private val selected: BooleanArray,
        private val removeWhitelist: BooleanArray
    ) : RecyclerView.Adapter<WhitelistFreezeAdapter.VH>() {

        private var displayed: List<IndexedValue<AppInfo>> = apps.withIndex().toList()

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val icon = view.findViewById<android.widget.ImageView>(R.id.app_icon)
            val name = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.app_name)
            val pkg = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.app_desc)
            val checkRemove = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_remove_whitelist)
            val check = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.app_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_whitelist, parent, false))

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (srcIdx, info) = displayed[position]
            holder.name.text = info.name
            holder.pkg.text = info.packageName
            holder.checkRemove.setOnCheckedChangeListener(null)
            holder.checkRemove.isChecked = removeWhitelist[srcIdx]
            holder.checkRemove.setOnCheckedChangeListener { _, checked ->
                removeWhitelist[srcIdx] = checked
            }
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = selected[srcIdx]
            info.applicationInfo?.let {
                AppIconCache.loadIconBitmapAsync(
                    requireContext(), it, HPackages.myUserId, holder.icon,
                    HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                )
            } ?: holder.icon.setImageDrawable(requireContext().packageManager.defaultActivityIcon)
            holder.view.setOnClickListener {
                selected[srcIdx] = !selected[srcIdx]
                holder.check.isChecked = selected[srcIdx]
            }
            holder.check.setOnCheckedChangeListener { _, checked ->
                selected[srcIdx] = checked
            }
        }

        fun filter(query: String) {
            displayed = if (query.isBlank()) apps.withIndex().toList()
            else apps.withIndex().filter { (_, app) ->
                app.name.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)
            }.toList()
            notifyDataSetChanged()
        }

        fun selectAll() { selected.fill(true); notifyDataSetChanged() }
        fun deselectAll() { selected.fill(false); notifyDataSetChanged() }
    }

    /** Adapter for the app-assign list inside the tag management dialog. */
    private inner class TagAppAssignAdapter(
        private val source: List<AppInfo>,
        private val assigned: BooleanArray   // indexed by position in `source`
    ) : RecyclerView.Adapter<TagAppAssignAdapter.VH>() {

        // When false (default), only apps currently on the Default page are shown.
        // When true, all apps are shown (original behaviour).
        private var showAll: Boolean = false
        private var currentQuery: String = ""

        // Displayed (filtered) subset — pairs of (sourceIndex, AppInfo)
        private var displayed: List<Pair<Int, AppInfo>> = computeDisplayed()

        private fun computeDisplayed(): List<Pair<Int, AppInfo>> {
            val base = if (showAll) {
                source.mapIndexed { i, a -> i to a }
            } else {
                source.mapIndexed { i, a -> i to a }.filter { (_, info) -> 0 in info.tagIdList }
            }
            return if (currentQuery.isBlank()) base else base.filter { (_, info) ->
                FuzzySearch.search(info.packageName, currentQuery) ||
                FuzzySearch.search(info.name.toString(), currentQuery) ||
                (HailData.nineKeySearch && NineKeySearch.search(currentQuery, info.packageName, info.name.toString())) ||
                PinyinSearch.searchPinyinAll(info.name.toString(), currentQuery)
            }
        }

        fun setShowAll(value: Boolean) {
            showAll = value
            displayed = computeDisplayed()
            notifyDataSetChanged()
        }

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val icon = view.findViewById<android.widget.ImageView>(R.id.app_icon)
            val name = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.app_name)
            val pkg  = view.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.app_desc)
            val check = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.app_star)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_apps, parent, false)
            return VH(v)
        }

        override fun getItemCount() = displayed.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (srcIdx, info) = displayed[position]
            holder.name.text = info.name
            holder.pkg.text = info.packageName
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = assigned[srcIdx]
            // Load icon using the correct AppIconCache signature
            info.applicationInfo?.let {
                AppIconCache.loadIconBitmapAsync(
                    requireContext(), it, HPackages.myUserId, holder.icon
                )
            } ?: holder.icon.setImageDrawable(
                requireContext().packageManager.defaultActivityIcon
            )
            holder.view.setOnClickListener {
                assigned[srcIdx] = !assigned[srcIdx]
                holder.check.isChecked = assigned[srcIdx]
            }
            holder.check.setOnCheckedChangeListener { _, checked ->
                assigned[srcIdx] = checked
            }
        }

        fun filter(query: String) {
            currentQuery = query
            displayed = computeDisplayed()
            notifyDataSetChanged()
        }

        /** Write the checked state back to each AppInfo's tagIdList. */
        fun applyAssignments(tagId: Int) {
            val defaultTagId = 0
            val isNonDefaultTag = tagId != defaultTagId
            source.forEachIndexed { i, info ->
                if (assigned[i]) {
                    // Assigning to this tag
                    if (tagId !in info.tagIdList) info.tagIdList.add(tagId)
                    // If assigned to a real (non-default) tag, remove the Default tag
                    if (isNonDefaultTag) info.tagIdList.remove(defaultTagId)
                } else {
                    // Unassigning from this tag
                    info.tagIdList.remove(tagId)
                    if (info.tagIdList.isEmpty()) {
                        // No tags left — restore Default tag instead of removing the app
                        info.tagIdList.add(defaultTagId)
                    }
                }
            }
        }
    }

    private fun importFromClipboard() = runCatching {
        val str = HUI.pasteText() ?: throw IllegalArgumentException()
        val json = if (str.contains('[')) JSONArray(
            str.substring(
                str.indexOf('[')..str.indexOf(']', str.indexOf('['))
            )
        )
        else JSONArray().put(str)
        var i = 0
        for (index in 0 until json.length()) {
            val pkg = json.getString(index)
            if (HPackages.getApplicationInfoOrNull(pkg) != null && !HailData.isChecked(pkg)) {
                HailData.addCheckedApp(pkg, tag.second, false)
                i++
            }
        }
        if (i > 0) {
            HailData.saveApps()
            updateCurrentList()
        }
        HUI.showToast(getString(R.string.msg_imported, i.toString()))
    }

    private suspend fun importFrozenApp() = withContext(Dispatchers.IO) {
        HPackages.getInstalledApplications().map { it.packageName }
            .filter { AppManager.isAppFrozen(it) && !HailData.isChecked(it) }
            .onEach { HailData.addCheckedApp(it, tag.second, false) }.size
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_multiselect -> {
                multiselect = !multiselect
                item.updateIcon()
                if (multiselect) {
                    updateBarTitle()
                    HUI.showToast(R.string.tap_to_select)
                } else {
                    selectedList.clear()
                    updateCurrentList()
                    updateBarTitle()
                }
            }

            R.id.action_freeze_current -> actions.setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })

            R.id.action_unfreeze_current -> actions.setListFrozen(false, pagerAdapter.currentList)
            R.id.action_freeze_all -> actions.setListFrozen(true)
            R.id.action_unfreeze_all -> actions.setListFrozen(false)
            R.id.action_freeze_non_whitelisted -> actions.setListFrozen(true, HailData.checkedList.filterNot { it.whitelisted })

            R.id.action_filter_user_apps -> {
                appTypeFilter = if (appTypeFilter == APP_TYPE_USER) APP_TYPE_ALL else APP_TYPE_USER
                activity.invalidateOptionsMenu()
                updateCurrentList()
            }

            R.id.action_filter_system_apps -> {
                appTypeFilter = if (appTypeFilter == APP_TYPE_SYSTEM) APP_TYPE_ALL else APP_TYPE_SYSTEM
                activity.invalidateOptionsMenu()
                updateCurrentList()
            }

            R.id.action_show_uninstalled -> {
                HailData.showUninstalled = !HailData.showUninstalled
                activity.invalidateOptionsMenu()
                (parentFragment as HomeFragment).childFragmentManager.fragments
                    .filterIsInstance<PagerFragment>()
                    .forEach { it.updateCurrentList() }
            }

            R.id.action_import_clipboard -> importFromClipboard()
            R.id.action_import_frozen -> lifecycleScope.launch {
                val size = importFrozenApp()
                if (size > 0) {
                    HailData.saveApps()
                    updateCurrentList()
                }
                HUI.showToast(getString(R.string.msg_imported, size.toString()))
            }

            R.id.action_export_current -> actions.exportToClipboard(pagerAdapter.currentList)
            R.id.action_export_all -> actions.exportToClipboard(HailData.checkedList)

            R.id.sort_by_name_asc -> changeSort(HailData.SORT_NAME_ASC, item)
            R.id.sort_by_name_desc -> changeSort(HailData.SORT_NAME_DESC, item)
            R.id.sort_by_added_time_asc -> changeSort(HailData.SORT_ADDED_TIME_ASC, item)
            R.id.sort_by_added_time_desc -> changeSort(HailData.SORT_ADDED_TIME_DESC, item)
            R.id.sort_by_install_asc -> changeSort(HailData.SORT_INSTALL_ASC, item)
            R.id.sort_by_install_desc -> changeSort(HailData.SORT_INSTALL_DESC, item)
            R.id.sort_by_update_asc -> changeSort(HailData.SORT_UPDATE_ASC, item)
            R.id.sort_by_update_desc -> changeSort(HailData.SORT_UPDATE_DESC, item)
        }
        return false
    }

    private fun changeSort(sort: String, item: MenuItem) {
        item.isChecked = true
        HailData.changeScreenSort(HailData.SORT_SCREEN_HOME, sort)
        scrollToTopOnNextUpdate = true
        updateCurrentList()
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        menu.findItem(R.id.action_filter_user_apps)?.isChecked = appTypeFilter == APP_TYPE_USER
        menu.findItem(R.id.action_filter_system_apps)?.isChecked = appTypeFilter == APP_TYPE_SYSTEM
        menu.findItem(R.id.action_show_uninstalled)?.isChecked = HailData.showUninstalled
        menu.findItem(
            when (HailData.sortByScreen(HailData.SORT_SCREEN_HOME)) {
                HailData.SORT_NAME_DESC -> R.id.sort_by_name_desc
                HailData.SORT_ADDED_TIME_ASC -> R.id.sort_by_added_time_asc
                HailData.SORT_ADDED_TIME_DESC -> R.id.sort_by_added_time_desc
                HailData.SORT_INSTALL_ASC -> R.id.sort_by_install_asc
                HailData.SORT_INSTALL_DESC -> R.id.sort_by_install_desc
                HailData.SORT_UPDATE_ASC -> R.id.sort_by_update_asc
                HailData.SORT_UPDATE_DESC -> R.id.sort_by_update_desc
                else -> R.id.sort_by_name_asc
            }
        )?.isChecked = true
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
        val searchItem = menu.findItem(R.id.action_search)
        this.searchItem = searchItem
        val searchView = searchItem.actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }

        // Restore active query if one exists (e.g. after keyboard dismiss rebuilds the menu)
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()  // show text without re-opening keyboard
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                // Ignore the empty event fired when the SearchView collapses, including the
                // teardown/rebuild triggered by the RESUMED-scoped menu provider losing focus
                // (e.g. backgrounding the app) rather than an actual user-initiated close
                if (newText.isEmpty() && (!searchItem.isActionViewExpanded || !isResumed)) return true
                query = newText
                tabs.isVisible = query.isEmpty() && tabs.tabCount > 1
                updateCurrentList()
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()  // dismiss keyboard without collapsing
                return true
            }
        })

        // Only clear the query when the user explicitly closes the search (X button). The menu
        // is also rebuilt (and this collapse fired) whenever the fragment drops below RESUMED,
        // e.g. backgrounding the app or launching another app — ignore that case via isResumed.
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (isResumed) {
                    query = ""
                    tabs.isVisible = tabs.tabCount > 1
                    updateCurrentList()
                }
                return true
            }
        })

        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onDestroyView() {
        searchItem = null
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}