package com.aistra.hail.utils

import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData

/** Sorts [AppInfo] lists for the Home, Prereq, and Dex apps screens, keeping pinned apps first
 * regardless of the chosen criterion (matching the existing pinned-first behavior of [NameComparator]). */
object AppSort {
    private fun installTime(info: AppInfo) =
        HPackages.getUnhiddenPackageInfoOrNull(info.packageName)?.firstInstallTime ?: 0L

    private fun updateTime(info: AppInfo) =
        HPackages.getUnhiddenPackageInfoOrNull(info.packageName)?.lastUpdateTime ?: 0L

    fun sort(list: List<AppInfo>, sortBy: String): List<AppInfo> {
        val pinnedFirst = compareBy<AppInfo> { !it.pinned }
        return when (sortBy) {
            HailData.SORT_NAME_DESC -> list.sortedWith(pinnedFirst.thenComparator { a, b -> NameComparator.compare(b, a) })
            HailData.SORT_ADDED_TIME_ASC -> list.sortedWith(pinnedFirst.thenBy { it.addedTime })
            HailData.SORT_ADDED_TIME_DESC -> list.sortedWith(pinnedFirst.thenByDescending { it.addedTime })
            HailData.SORT_INSTALL_ASC -> list.sortedWith(pinnedFirst.thenBy(::installTime))
            HailData.SORT_INSTALL_DESC -> list.sortedWith(pinnedFirst.thenByDescending(::installTime))
            HailData.SORT_UPDATE_ASC -> list.sortedWith(pinnedFirst.thenBy(::updateTime))
            HailData.SORT_UPDATE_DESC -> list.sortedWith(pinnedFirst.thenByDescending(::updateTime))
            else /* SORT_NAME_ASC */ -> list.sortedWith(pinnedFirst.thenComparator { a, b -> NameComparator.compare(a, b) })
        }
    }
}
