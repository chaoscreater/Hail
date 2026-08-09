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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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
import com.aistra.hail.ui.theme.AppTheme
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
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
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

    internal fun updateCurrentList() = HailData.checkedList.filter {
        if (query.isEmpty()) tag.second in it.tagIdList
        else ((HailData.nineKeySearch && NineKeySearch.search(
            query, it.packageName, it.name.toString()
        )) || FuzzySearch.search(it.packageName, query) || FuzzySearch.search(
            it.name.toString(), query
        ) || PinyinSearch.searchPinyinAll(it.name.toString(), query))
    }.filter {
        when (appTypeFilter) {
            APP_TYPE_USER -> it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) == 0
            APP_TYPE_SYSTEM -> it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
            else -> true
        }
    }.filter {
        HailData.showUninstalled || it.applicationInfo != null
    }.sortedWith(NameComparator).let {
        binding.empty.isVisible = it.isEmpty()
        pagerAdapter.submitList(it)
        app.setAutoFreezeService()
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

    private fun deselect(update: Boolean = true) {
        selectedList.clear()
        if (!update) return
        updateCurrentList()
        updateBarTitle()
    }

    private fun onMultiSelect() {
        MaterialAlertDialogBuilder(activity).setTitle(
            getString(
                R.string.msg_selected, selectedList.size.toString()
            )
        ).setItems(
            intArrayOf(
                R.string.action_freeze,
                R.string.action_unfreeze,
                R.string.action_tag_set,
                R.string.action_export_clipboard,
                R.string.action_remove_home,
                R.string.action_unfreeze_remove_home
            ).map { getString(it) }.toTypedArray()
        ) { _, which ->
            when (which) {
                0 -> {
                    actions.setListFrozen(true, selectedList, false)
                    deselect()
                }

                1 -> {
                    actions.setListFrozen(false, selectedList, false)
                    deselect()
                }

                2 -> triStateTagDialog()

                3 -> {
                    actions.exportToClipboard(selectedList)
                    deselect()
                }

                4 -> {
                    selectedList.forEach { actions.removeCheckedApp(it.packageName, false) }
                    HailData.saveApps()
                    deselect()
                }

                5 -> {
                    actions.setListFrozen(false, selectedList, false)
                    selectedList.forEach {
                        if (!AppManager.isAppFrozen(it.packageName)) actions.removeCheckedApp(it.packageName, false)
                    }
                    HailData.saveApps()
                    deselect()
                }
            }
        }.setNegativeButton(R.string.action_deselect) { _, _ ->
            deselect()
        }.setNeutralButton(R.string.action_select_all) { _, _ ->
            selectedList.addAll(pagerAdapter.currentList.filterNot { it in selectedList })
            updateCurrentList()
            updateBarTitle()
            onMultiSelect()
        }.show()
    }

    private fun triStateTagDialog() {
        val initialStates = Array(HailData.tags.size) { index ->
            val tagId = HailData.tags[index].second
            when (selectedList.count { tagId in it.tagIdList }) {
                selectedList.size -> ToggleableState.On
                0 -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
        }
        val states = mutableStateListOf(*initialStates)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AppTheme { TriStateTagList(initialStates, states) } }
        }).setPositiveButton(android.R.string.ok) { _, _ ->
            val defaultTagId = 0
            selectedList.forEach { info ->
                states.forEachIndexed { index, state ->
                    val tagId = HailData.tags[index].second
                    when (state) {
                        ToggleableState.On -> {
                            if (tagId !in info.tagIdList) info.tagIdList.add(tagId)
                        }
                        ToggleableState.Off -> info.tagIdList.remove(tagId)
                        ToggleableState.Indeterminate -> {}
                    }
                }
                if (info.tagIdList.isEmpty()) {
                    // No tags left — restore Default instead of removing the app
                    info.tagIdList.add(defaultTagId)
                } else if (info.tagIdList.any { it != defaultTagId }) {
                    // Has real tags — strip Default if present
                    info.tagIdList.remove(defaultTagId)
                }
            }
            HailData.saveApps()
            deselect()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            actions.addTagDialog(selectedList) { triStateTagDialog() }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Composable
    private fun TriStateTagList(initialStates: Array<ToggleableState>, states: MutableList<ToggleableState>) = Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        HailData.tags.forEachIndexed { index, tag ->
            Row(modifier = Modifier.fillMaxWidth().clickable {
                states[index] = if (initialStates[index] == ToggleableState.Indeterminate) when (states[index]) {
                    ToggleableState.On -> ToggleableState.Off
                    ToggleableState.Off -> ToggleableState.Indeterminate
                    ToggleableState.Indeterminate -> ToggleableState.On
                }
                else if (states[index] == ToggleableState.On) ToggleableState.Off
                else ToggleableState.On
            }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = states[index],
                    onClick = null,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = tag.first,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
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
                } else deselect()
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
        }
        return false
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        menu.findItem(R.id.action_filter_user_apps)?.isChecked = appTypeFilter == APP_TYPE_USER
        menu.findItem(R.id.action_filter_system_apps)?.isChecked = appTypeFilter == APP_TYPE_SYSTEM
        menu.findItem(R.id.action_show_uninstalled)?.isChecked = HailData.showUninstalled
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
        val searchItem = menu.findItem(R.id.action_search)
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
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}