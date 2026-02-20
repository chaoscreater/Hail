package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.*
import kotlinx.coroutines.*

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val apps = MutableLiveData<List<ApplicationInfo>>()
    val isRefreshing = MutableLiveData(false)
    val query = MutableLiveData("")
    val displayApps = MutableLiveData<List<ApplicationInfo>>()

    init {
        updateAppList()
    }

    private var refreshJob: Job? = null
    private var refreshStateJob: Job? = null

    /**
     * Delaying changes to the refreshing state prevents the progress bar from flickering.
     * */
    private fun postRefreshState(state: Boolean, delayTime: Long = 200L) {
        if (!state) {
            refreshStateJob?.cancel()
            isRefreshing.postValue(false)
        } else if (refreshStateJob == null || refreshStateJob!!.isCompleted) {
            refreshStateJob = viewModelScope.launch {
                delay(delayTime)
                isRefreshing.postValue(true)
            }
        }
    }

    fun postQuery(text: String, delayTime: Long = 300L) {
        refreshJob?.cancel()
        if (delayTime == 0L)
            query.postValue(text)
        else {
            refreshJob = viewModelScope.launch {
                delay(delayTime)
                query.postValue(text)
            }
        }
    }

    /**
     * This method is only used to refresh all the applications that the user has installed
     * and has no filtering or sorting effect.
     * */
    fun updateAppList() {
        viewModelScope.launch {
            postRefreshState(true)
            apps.postValue(HPackages.getInstalledApplications())
        }
    }

    /**
     * The list that the user actually sees.
     *
     * This method is different from `updateAppList()` in that it filters and rearranges the data
     * from `apps` and places it in `displayApps`.
     * */
    fun updateDisplayAppList() {
        apps.value?.let {
            viewModelScope.launch {
                postRefreshState(true)
                displayApps.postValue(filterList(it, query.value))
                postRefreshState(false)
            }
        }
    }


    private val ApplicationInfo.isSystemApp: Boolean
        get() = flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM
    private val ApplicationInfo.isAppFrozen get() = AppManager.isAppFrozen(packageName)
    private val ApplicationInfo.isAdded get() = HailData.isChecked(packageName)

    private suspend fun filterList(
        appList: List<ApplicationInfo>,
        query: String?
    ): List<ApplicationInfo> {
        val pm = getApplication<HailApp>().packageManager
        return withContext(Dispatchers.Default) {
            return@withContext appList.filter { info ->
                // Exclude hidden apps
                info.packageName !in HailData.hiddenApps

                        // User/System + Added/Unadded combined filter logic
                        && run {
                    val isUser = !info.isSystemApp
                    val isSys = info.isSystemApp
                    val isAdded = info.isAdded
                    val hasSpecificFilter = HailData.filterAddedUserApps
                            || HailData.filterUnaddedUserApps
                            || HailData.filterAddedSystemApps
                            || HailData.filterUnaddedSystemApps
                    if (hasSpecificFilter) {
                        (HailData.filterAddedUserApps && isUser && isAdded)
                                || (HailData.filterUnaddedUserApps && isUser && !isAdded)
                                || (HailData.filterAddedSystemApps && isSys && isAdded)
                                || (HailData.filterUnaddedSystemApps && isSys && !isAdded)
                    } else {
                        ((HailData.filterUserApps && isUser) || (HailData.filterSystemApps && isSys))
                                && ((HailData.filterAddedApps && isAdded)
                                || (HailData.filterUnaddedApps && !isAdded))
                    }
                }

                        // Frozen/Unfrozen filter
                        && ((HailData.filterFrozenApps && info.isAppFrozen)
                        || (HailData.filterUnfrozenApps && !info.isAppFrozen))

                        // Search
                        && ((HailData.nineKeySearch
                        && (NineKeySearch.search(query, info.packageName, info.loadLabel(pm).toString())))
                        || FuzzySearch.search(info.packageName, query)
                        || FuzzySearch.search(info.loadLabel(pm).toString(), query)
                        || PinyinSearch.searchPinyinAll(info.loadLabel(pm).toString(), query))
            }.run {
                when (HailData.sortBy) {
                    HailData.SORT_INSTALL -> sortedBy {
                        HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.firstInstallTime ?: 0
                    }
                    HailData.SORT_UPDATE -> sortedByDescending {
                        HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.lastUpdateTime ?: 0
                    }
                    HailData.SORT_UNADDED -> sortedWith(
                        compareBy<ApplicationInfo> { it.isAdded }.then(NameComparator)
                    )
                    HailData.SORT_ADDED -> sortedWith(
                        compareByDescending<ApplicationInfo> { it.isAdded }.then(NameComparator)
                    )
                    else -> sortedWith(NameComparator)
                }
            }
        }
    }
}