package com.aistra.hail.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainFragment

/**
 * Lists every checked app that has custom add-to-home-screen settings applied — a prerequisite
 * app, or the Bluetooth/location toggles — regardless of which tag category it lives under.
 * Search, multiselect, and the toolbar menu are all provided by [FilteredAppListController]; this
 * class only supplies the membership predicate and forwards lifecycle calls.
 */
class ShortcutSettingsFragment : MainFragment() {

    private val controller by lazy {
        FilteredAppListController(this, HailData.SORT_SCREEN_PREREQ) {
            !it.prereqPackage.isNullOrEmpty() || it.enableBluetooth || it.enableLocation
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = controller.onCreateView(inflater, container)

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onDestroyView() {
        controller.onDestroyView()
        super.onDestroyView()
    }
}
