package com.aistra.hail.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aistra.hail.R
import com.aistra.hail.ui.main.MainFragment

/**
 * A user-curated list of "Dex apps" — apps flagged via the fab's "Dex app shortcuts" picker
 * (same picker UI as Home screen shortcuts, just backed by [AppInfo.dexApp] instead of
 * [AppInfo.addToHomeScreen]). Membership here is independent of tag categories: an app can
 * appear both under its tag in Apps and here. Search, multiselect, and the toolbar menu are all
 * provided by [FilteredAppListController]. Unlike Home screen shortcuts, "Add to Home screen"
 * here pins each app as a widget rather than a shortcut — see `pinAsWidget` on
 * [PinShortcutsDialogController], driven by [pinShortcutsDialog]/`fabDexApps` independently of
 * the controller.
 */
class DexAppsFragment : MainFragment() {

    private val controller by lazy { FilteredAppListController(this) { it.dexApp } }
    private val pinShortcutsDialog by lazy {
        PinShortcutsDialogController(
            fragment = this,
            titleRes = R.string.dex_app_shortcuts,
            getFlag = { it.dexApp },
            setFlag = { info, value -> info.dexApp = value },
            onSelectionChanged = { controller.updateList() },
            pinAsWidget = true
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = controller.onCreateView(inflater, container)

    override fun onResume() {
        super.onResume()
        controller.onResume()
        activity.fabDexApps.setOnClickListener { pinShortcutsDialog.show() }
        pinShortcutsDialog.onResume()
    }

    override fun onDestroyView() {
        controller.onDestroyView()
        pinShortcutsDialog.onDestroy()
        super.onDestroyView()
    }
}
