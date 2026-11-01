package com.aistra.hail.app

import android.content.Context
import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * True when [packageName] should be left alone rather than frozen because
     * [HailData.excludeForegroundApp] is on and it's the app currently in use — checked in both
     * [setAppFrozen] and [setListFrozen] so the protection holds no matter which caller (API,
     * manual UI action, tile, shortcut, auto-freeze) triggered the freeze. [force] bypasses this
     * protection, e.g. for the API's `FREEZE` action explicitly overriding it.
     *
     * [HailApp.isInForeground] is checked first and, when true, short-circuits straight to "not
     * excluded": only one app can be in the foreground at a time, so Hail's own screen being the
     * one on top already proves [packageName] isn't it — no need to fall through to
     * [HSystem.isCurrentForegroundApp]'s usage-stats query, which reports batched data that can
     * lag behind an actual switch into Hail by more than a minute and would otherwise still show
     * whatever app was open right before as "current".
     */
    private fun isForegroundExcluded(packageName: String, frozen: Boolean, force: Boolean = false): Boolean =
        frozen && !force && HailData.excludeForegroundApp &&
                !app.isInForeground && HSystem.isCurrentForegroundApp(app, packageName)

    /**
     * Outcome of [setListFrozenWithReasons]. [skipped] holds one "package — reason" line for every
     * app that was left alone or couldn't be changed, so the API log can say why.
     */
    class ListResult(
        val changed: Int, private val name: String, val deniedPackage: String?, val skipped: List<String>
    ) {
        /** The single changed app's name, the count as a string if several changed, or null if every attempt was refused. */
        val summary: String? get() = if (deniedPackage != null && changed == 0) null else if (changed == 1) name else changed.toString()

        fun describe(frozen: Boolean): String = buildString {
            append(if (frozen) "Froze $changed" else "Unfroze $changed")
            append(if (changed == 1) " app" else " apps")
            if (skipped.isNotEmpty()) append("; not changed: ").append(skipped.joinToString("; "))
        }
    }

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? =
        setListFrozenWithReasons(frozen, *appInfo).also { lastDeniedPackage = it.deniedPackage }.summary

    fun setListFrozenWithReasons(frozen: Boolean, vararg appInfo: AppInfo): ListResult {
        var i = 0
        var deniedPackage: String? = null
        var name = String()
        val skipped = mutableListOf<String>()
        // call setListFrozen for some batch-style working mode here
        // fallback to applying each app individually otherwise
        appInfo.forEach {
            val blocked = blockedReason(it.packageName, frozen)
            when {
                blocked != null -> skipped += "${it.packageName} — $blocked"

                applyFrozen(it.packageName, frozen) -> {
                    i++
                    name = it.name.toString()
                }

                it.applicationInfo != null -> {
                    if (deniedPackage == null) deniedPackage = it.packageName
                    skipped += "${it.packageName} — $backendRefused"
                }
            }
        }
        return ListResult(i, name, deniedPackage, skipped)
    }

    private val backendRefused get() = "the working mode (${HailData.workingMode}) refused the change — check its service is running and has permission"

    /**
     * Why [packageName] is being left alone instead of changed, or null when nothing blocks it.
     * Phrased for the API log, so it names the setting responsible.
     */
    private fun blockedReason(packageName: String, frozen: Boolean, force: Boolean = false): String? = when {
        packageName == BuildConfig.APPLICATION_ID -> "Hail never freezes itself"
        isForegroundExcluded(packageName, frozen, force) ->
            "\"Exclude foreground app\" is on and this app was the most recently used app in the last minute"

        else -> null
    }

    /** Like [setAppFrozen], but returns why nothing changed (null on success) so callers can log it. */
    fun setAppFrozenOrReason(packageName: String, frozen: Boolean, force: Boolean = false): String? =
        blockedReason(packageName, frozen, force)
            ?: if (applyFrozen(packageName, frozen)) null else backendRefused

    fun setAppFrozen(packageName: String, frozen: Boolean, force: Boolean = false): Boolean =
        setAppFrozenOrReason(packageName, frozen, force) == null

    private fun applyFrozen(packageName: String, frozen: Boolean): Boolean =
        when (HailData.workingMode) {
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
    suspend fun launchApp(context: Context, packageName: String): Boolean {
        val appInfo = HailData.checkedList.find { it.packageName == packageName }
        // Three independent delays: Play Store's and the prerequisite app's "enable" delay only
        // apply if that app was frozen and so had to be unfrozen; the "launch" delay applies
        // whenever the prerequisite app is launched
        var unfrozePrereq = false
        var unfrozePlayStore = false
        var launchedPrereq = false
        if (appInfo?.enablePlayStore == true && isAppFrozen(HailData.PACKAGE_PLAY_STORE)) {
            if (setAppFrozen(HailData.PACKAGE_PLAY_STORE, false)) app.setAutoFreezeService()
            unfrozePlayStore = true
        }
        appInfo?.prereqPackage?.let { prereqPkg ->
            if ((appInfo.prereqLaunch || appInfo.prereqEnable) && isAppFrozen(prereqPkg)) {
                if (setAppFrozen(prereqPkg, false)) app.setAutoFreezeService()
                unfrozePrereq = true
            }
            if (appInfo.prereqLaunch) {
                context.packageManager.getLaunchIntentForPackage(prereqPkg)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    launchedPrereq = true
                }
            }
        }
        val launchDelay = maxOf(
            if (unfrozePlayStore) appInfo?.playStoreDelay ?: 0L else 0L,
            if (unfrozePrereq && appInfo?.prereqEnable == true) appInfo.prereqDelay else 0L,
            if (launchedPrereq) appInfo?.prereqLaunchDelay ?: 0L else 0L
        )
        if (launchDelay > 0L) delay(launchDelay)
        if (isAppFrozen(packageName) && setAppFrozen(packageName, false)) {
            app.setAutoFreezeService()
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            HUI.notifyShizukuRequired(packageName)
            HUI.showToast(R.string.activity_not_found)
            return false
        }
        HShortcuts.addDynamicShortcut(packageName)
        startTargetApp(context, launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * Starts [intent], the target app's launch intent, from [context] — every tap-to-launch path
     * ([launchApp], [com.aistra.hail.ui.api.ApiActivity], [com.aistra.hail.ui.home.AppContextActions])
     * goes through here. By default that's just [HSamsungDex.startMaximized], but a prerequisite
     * app launched moments earlier (see [launchApp]'s delays) has usually covered Hail's screen by
     * now, and Android — HyperOS in particular, via its "Display pop-up windows while running in
     * the background" permission — silently drops a `startActivity` from an app that isn't on
     * screen. A shell isn't subject to that, so when the app's [AppInfo.shellLaunch] setting is on
     * and a shell backend (Shizuku/root) is available, the launch goes through `am start` instead
     * (which skips [HSamsungDex]'s DeX maximizing); otherwise, or if that command fails, it falls
     * back to the plain call.
     */
    suspend fun startTargetApp(context: Context, intent: Intent) {
        val pkg = intent.component?.packageName ?: intent.`package`
        val shellLaunch = HailData.checkedList.find { it.packageName == pkg }?.shellLaunch == true
        if (shellLaunch && startViaShell(intent)) return
        HSamsungDex.startMaximized(context, intent)
    }

    private suspend fun startViaShell(intent: Intent): Boolean {
        val component = intent.component ?: return false
        if (!HailData.workingMode.startsWith(HailData.SU) && !HailData.workingMode.startsWith(HailData.SHIZUKU)) {
            ApiLog.logAsync(
                "Launch via shell", component.packageName,
                "\"Launch app through shell\" is on, but the working mode (${HailData.workingMode}) has no shell — fell back to a plain launch"
            )
            return false
        }
        val started = execute(
            "am start --user current -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_LAUNCHER} -n '${component.flattenToShortString()}'"
        ).first == 0
        ApiLog.logAsync(
            "Launch via shell", component.packageName,
            if (started) "Launched through the shell" else "The shell launch failed — fell back to a plain launch, which Android may block"
        )
        return started
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

    /** Turns Wi-Fi on only if it's currently off — unlike [enableBluetooth]/[enableLocation], a no-op when already on. */
    @android.annotation.SuppressLint("MissingPermission")
    fun enableWifiIfOff(context: Context) {
        val wifiManager = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        }.getOrNull()
        @Suppress("DEPRECATION")
        if (wifiManager?.isWifiEnabled == true) return
        runCatching {
            @Suppress("DEPRECATION")
            wifiManager?.isWifiEnabled = true
        }
        execCommand("svc wifi enable")
        execCommand("cmd wifi set-wifi-enabled enabled")
        execCommand("settings put global wifi_on 1")
    }

    /** Turns Bluetooth on only if it's currently off. */
    @android.annotation.SuppressLint("MissingPermission")
    fun enableBluetooth(context: Context) {
        val adapter = runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            @Suppress("DEPRECATION")
            btManager?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()
        if (adapter?.isEnabled == true) return
        runCatching {
            @Suppress("DEPRECATION")
            adapter?.enable()
        }
        execCommand("svc bluetooth enable")
        execCommand("cmd bluetooth_manager enable")
        execCommand("cmd bluetooth set-state enable")
        execCommand("settings put global bluetooth_on 1")
    }

    /** Turns Location on only if it's currently off. */
    fun enableLocation(context: Context) {
        val isLocationEnabled = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                locationManager?.isLocationEnabled == true
            } else {
                @Suppress("DEPRECATION")
                android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF
                ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
            }
        }.getOrDefault(false)
        if (isLocationEnabled) return
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

    private const val PACKAGE_MACRODROID = "com.arlosoft.macrodroid"

    /**
     * MacroDroid's "Intent Received" trigger action string for the macro that switches location
     * to high accuracy mode — the shell/Settings.Secure writes in [enableLocation] alone aren't
     * reliable on every OEM/Android version, so this hands the job to a user-configured macro
     * instead. Must match the action string entered in that macro's trigger exactly.
     */
    private const val ACTION_MACRODROID_HIGH_ACCURACY = "com.aistra.hail.ENABLE_HIGH_ACCURACY"

    /**
     * Fires the MacroDroid macro that enables high-accuracy location mode, but only when the
     * `assisted_gps_enabled` global setting reads 0 (i.e. assisted GPS/high accuracy is currently
     * off) — skipped when it's already on. Targets MacroDroid's package explicitly so delivery
     * isn't dropped by Android 8+'s implicit-broadcast limits, and is silently a no-op if
     * MacroDroid isn't installed or refuses the broadcast.
     */
    fun triggerHighAccuracyMacro(context: Context) {
        val assistedGpsEnabled = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, "assisted_gps_enabled", 0)
        }.getOrDefault(0)
        if (assistedGpsEnabled != 0) return
        runCatching {
            context.sendBroadcast(Intent(ACTION_MACRODROID_HIGH_ACCURACY).setPackage(PACKAGE_MACRODROID))
        }
    }

    /**
     * Applies the Bluetooth/location/high-accuracy prerequisite settings for a launch that's
     * actually going through. Shared by every tap-to-launch path — [com.aistra.hail.ui.api.ApiActivity]
     * (shortcuts, widgets, the `hail://` schema) and [com.aistra.hail.ui.home.AppContextActions]
     * (tapping an app inside Hail's own list) — so they can't drift apart the way they did when
     * only the former called this.
     */
    fun applyPrerequisiteExtras(
        context: Context, enableBT: Boolean, enableLoc: Boolean, enableHA: Boolean, enableWifi: Boolean
    ) {
        if (!enableBT && !enableLoc && !enableWifi) return
        Thread {
            if (enableWifi) enableWifiIfOff(context)
            if (enableBT) enableBluetooth(context)
            if (enableLoc) enableLocation(context)
            if (enableLoc && enableHA) triggerHighAccuracyMacro(context)
        }.start()
    }
}