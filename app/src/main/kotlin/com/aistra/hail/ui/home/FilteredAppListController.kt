package com.aistra.hail.ui.home

import android.content.pm.ApplicationInfo
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.marginRelative
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.NameComparator
import com.aistra.hail.utils.matchesSearchQuery
import com.google.android.material.color.MaterialColors

/**
 * Shared implementation behind [ShortcutSettingsFragment] ("PreReq") and [DexAppsFragment] ("Dex
 * apps") — both are flat, non-tag-scoped app lists differing only in [baseFilter]. Owns the
 * RecyclerView/[PagerAdapter], the full search + multiselect + freeze/filter/export toolbar menu
 * (`menu_filtered_list.xml`), and click/long-press via [AppContextActions] — mirroring what
 * [PagerFragment] does for Home's tag pages, minus the tag/tab-specific bits. Home is not routed
 * through this controller: it has real tab-scoped behavior (tag-scoped imports, tab visibility,
 * jump-to-category) this controller has no use for.
 *
 * Search is scoped to apps already matching [baseFilter] rather than searching every checked app
 * like Home does: neither screen offers a way to act on an app outside its own scope (e.g. Dex
 * apps' long-press menu has no action that sets [AppInfo.dexApp]), so a global search would only
 * surface dead-end results.
 */
class FilteredAppListController(
    private val fragment: Fragment,
    private val baseFilter: (AppInfo) -> Boolean
) : MenuProvider, PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener {

    companion object {
        private const val APP_TYPE_ALL = 0
        private const val APP_TYPE_USER = 1
        private const val APP_TYPE_SYSTEM = 2
    }

    private val activity: MainActivity get() = fragment.requireActivity() as MainActivity
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter

    private var multiselect: Boolean = false
    private val selectedList: MutableList<AppInfo> = mutableListOf()
    private var query: String = ""
    private var appTypeFilter: Int = APP_TYPE_ALL

    private val actions by lazy { AppContextActions(fragment, onListChanged = { updateList() }) }

    fun onCreateView(inflater: LayoutInflater, container: ViewGroup?): View {
        (fragment.requireActivity() as MenuHost).addMenuProvider(this, fragment.viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(selectedList).apply {
            onItemClickListener = this@FilteredAppListController
            onItemLongClickListener = this@FilteredAppListController
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, fragment.resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            applyDefaultInsetter { paddingRelative(fragment.isRtl, bottom = fragment.isLandscape) }
        }
        binding.refresh.apply {
            setOnRefreshListener {
                updateList()
                binding.refresh.isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(fragment.isRtl, start = !fragment.isLandscape, end = true) }
        }
        return binding.root
    }

    fun onResume() {
        updateList()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
    }

    fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        pagerAdapter.onDestroy()
        _binding = null
    }

    /** Public: [DexAppsFragment]'s pin-shortcuts picker calls this after it toggles `dexApp` flags. */
    fun updateList() {
        val list = HailData.checkedList.filter(baseFilter).filter {
            query.isEmpty() || matchesSearchQuery(query, it.packageName, it.name.toString())
        }.filter {
            when (appTypeFilter) {
                APP_TYPE_USER -> it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) == 0
                APP_TYPE_SYSTEM -> it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
                else -> true
            }
        }.filter {
            HailData.showUninstalled || it.applicationInfo != null
        }.sortedWith(NameComparator)
        binding.empty.isVisible = list.isEmpty()
        pagerAdapter.submitList(list)
        app.setAutoFreezeService()
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title =
            if (multiselect) fragment.getString(R.string.msg_selected, selectedList.size.toString())
            else fragment.getString(R.string.app_name)
    }

    override fun onItemClick(info: AppInfo) {
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info) else selectedList.add(info)
            updateList()
            updateBarTitle()
            return
        }
        actions.onItemClick(info)
    }

    override fun onItemLongClick(info: AppInfo): Boolean =
        actions.onItemLongClick(info, selectedList) { onMultiSelect() }

    private fun onMultiSelect() = actions.showMultiSelectDialog(selectedList, { pagerAdapter.currentList }) {
        updateList()
        updateBarTitle()
    }

    private fun deselect() {
        selectedList.clear()
        updateList()
        updateBarTitle()
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_filtered_list, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }

        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                if (newText.isEmpty() && (!searchItem.isActionViewExpanded || !fragment.isResumed)) return true
                query = newText
                updateList()
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return true
            }
        })

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (fragment.isResumed) {
                    query = ""
                    updateList()
                }
                return true
            }
        })

        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_filter_user_apps)?.isChecked = appTypeFilter == APP_TYPE_USER
        menu.findItem(R.id.action_filter_system_apps)?.isChecked = appTypeFilter == APP_TYPE_SYSTEM
        menu.findItem(R.id.action_show_uninstalled)?.isChecked = HailData.showUninstalled
    }

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
                updateList()
            }

            R.id.action_filter_system_apps -> {
                appTypeFilter = if (appTypeFilter == APP_TYPE_SYSTEM) APP_TYPE_ALL else APP_TYPE_SYSTEM
                activity.invalidateOptionsMenu()
                updateList()
            }

            R.id.action_show_uninstalled -> {
                HailData.showUninstalled = !HailData.showUninstalled
                activity.invalidateOptionsMenu()
                updateList()
            }

            R.id.action_export_current -> actions.exportToClipboard(pagerAdapter.currentList)
            R.id.action_export_all -> actions.exportToClipboard(HailData.checkedList)
        }
        return false
    }
}
