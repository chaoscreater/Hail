package com.aistra.hail.app

import android.content.Context
import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManager {
    /** Package name of the first app that returned a permission denial in the last setListFrozen call. */
    var lastDeniedPackage: String? = null
        private set
    val lockScreen: Boolean
        get() = when {
            HailData.workingMode.startsWith(HailData.OWNER) -> HPolicy.lockScreen
            HailData.workingMode.startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
            HailData.workingMode.startsWith(HailData.SU) -> HShell.lockScreen
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
            else -> false
        }

    fun isAppFrozen(packageName: String): Boolean = when {
        HailData.workingMode.endsWith(HailData.STOP) -> HPackages.isAppStopped(packageName)
        HailData.workingMode.endsWith(HailData.DISABLE) -> HPackages.isAppDisabled(packageName)
        HailData.workingMode.endsWith(HailData.HIDE) -> HPackages.isAppHidden(packageName)
        HailData.workingMode.endsWith(HailData.SUSPEND) -> HPackages.isAppSuspended(packageName)
        else -> HPackages.isAppDisabled(packageName)
                || HPackages.isAppHidden(packageName)
                || HPackages.isAppSuspended(packageName)
    }

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? {
        lastDeniedPackage = null
        val excludeMe = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
        var i = 0
        var denied = false
        var name = String()
        when (HailData.workingMode) {
            // call setListFrozen for some batch-style working mode here
            // fallback to setAppFrozen otherwise
            else -> {
                excludeMe.forEach {
                    when {
                        setAppFrozen(it.packageName, frozen) -> {
                            i++
                            name = it.name.toString()
                        }

                        it.applicationInfo != null -> {
                            denied = true
                            if (lastDeniedPackage == null) lastDeniedPackage = it.packageName
                        }
                    }
                }
            }
        }
        return if (denied && i == 0) null else if (i == 1) name else i.toString()
    }

    fun setAppFrozen(packageName: String, frozen: Boolean): Boolean =
        packageName != BuildConfig.APPLICATION_ID && when (HailData.workingMode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_SU_STOP -> !frozen || HShell.forceStopApp(packageName)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen)
            HailData.MODE_SHIZUKU_STOP -> !frozen || HShizuku.forceStopApp(packageName)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_PRIVAPP_STOP -> !frozen || HPackages.forceStopApp(packageName)
            HailData.MODE_PRIVAPP_DISABLE -> HPackages.setAppDisabled(packageName, frozen)
            else -> false
        }

    /**
     * Launches [packageName] directly, unfreezing it (and its configured prerequisite app, if
     * any) first — with no user-facing prompt. Used by the home-screen app-launch widget, which
     * must bypass [HailData.shortcutLaunchPrompt] entirely regardless of the user's shortcut
     * setting; pinned shortcuts and the API's `ACTION_LAUNCH` keep their own separate prompt
     * logic in [com.aistra.hail.ui.api.ApiActivity].
     */
    fun launchApp(context: Context, packageName: String): Boolean {
        val appInfo = HailData.checkedList.find { it.packageName == packageName }
        appInfo?.prereqPackage?.let { prereqPkg ->
            if ((appInfo.prereqLaunch || appInfo.prereqEnable) && isAppFrozen(prereqPkg)) {
                if (setAppFrozen(prereqPkg, false)) app.setAutoFreezeService()
            }
            if (appInfo.prereqLaunch) {
                context.packageManager.getLaunchIntentForPackage(prereqPkg)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        if (isAppFrozen(packageName) && setAppFrozen(packageName, false)) {
            app.setAutoFreezeService()
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            HUI.notifyShizukuRequired(packageName)
            HUI.showToast(R.string.activity_not_found)
            return false
        }
        HShortcuts.addDynamicShortcut(packageName)
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun uninstallApp(packageName: String): Boolean {
        when {
            HailData.workingMode.startsWith(HailData.OWNER) ->
                if (HPolicy.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.DHIZUKU) ->
                if (HDhizuku.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.SU) ->
                if (HShell.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.SHIZUKU) ->
                if (HShizuku.uninstallApp(packageName)) return true
        }
        HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        return false
    }

    fun reinstallApp(packageName: String): Boolean = when {
        HailData.workingMode.startsWith(HailData.SU) -> HShell.reinstallApp(packageName)
        HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName)
        else -> false
    }

    suspend fun execute(command: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        when {
            HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
            else -> 0 to null
        }
    }

    fun execCommand(command: String) {
        runCatching {
            when {
                HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
                HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
                else -> {
                    if (HShell.checkSU) HShell.execute(command, true)
                    else HShizuku.execute(command)
                }
            }
        }
        runCatching {
            Runtime.getRuntime().exec(command)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun enableBluetooth(context: Context) {
        runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            @Suppress("DEPRECATION")
            val adapter = btManager?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                @Suppress("DEPRECATION")
                adapter.enable()
            }
        }
        execCommand("svc bluetooth enable")
        execCommand("cmd bluetooth_manager enable")
        execCommand("cmd bluetooth set-state enable")
        execCommand("settings put global bluetooth_on 1")
    }

    fun enableLocation(context: Context) {
        runCatching {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.putInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
            )
        }
        execCommand("cmd location set-location-enabled true")
        execCommand("settings put secure location_mode 3")
        execCommand("settings put secure location_providers_allowed +gps,+network")
        execCommand("svc location enable")
    }
}