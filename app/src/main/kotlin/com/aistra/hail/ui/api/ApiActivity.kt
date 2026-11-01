package com.aistra.hail.ui.api

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.ApiLog
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
import com.aistra.hail.work.HWork.setAutoFreeze
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiActivity : ComponentActivity() {
    /** One API call. [fromShell] is captured at delivery time, the only moment `referrer` is valid. */
    private class Request(val intent: Intent, val fromShell: Boolean) {
        /** What happened (or why nothing did), written to the log entry once the call finishes. */
        var detail: String? = null
    }

    // Calls are handled strictly one at a time, in arrival order. Rapid calls (e.g. several LAUNCH
    // `am start` commands back to back) used to share one mutable `intent` and one finish(), so a
    // later call could overwrite an earlier one before it was read, or the earlier call's finish()
    // could cancel a later one mid-flight.
    private val requests = Channel<Request>(Channel.UNLIMITED)

    // Main thread only: calls queued or running, and whether one in the current batch left UI
    // (a prompt or error dialog) that finish() would dismiss.
    private var pending = 0
    private var holdOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block this window from intercepting touches/focus while it's invisible: without this,
        // rapid repeat API calls (e.g. FREEZE fired 2-3x/sec) stack up transparent windows that
        // eat all touch input on top of the foreground app until they finish() and clear.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        lifecycleScope.launch { for (request in requests) process(request) }
        enqueue(intent)
    }

    // launchMode="singleInstance" (see the manifest) means a second rapid API call is delivered
    // here instead of creating a new instance/window, as long as this one hasn't finished yet.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFinishing) {
            // Android delivered this call before it noticed the previous one finished us. This
            // instance is about to be destroyed (cancelling anything queued on it), so hand the
            // call to a fresh instance instead.
            startActivity(Intent(intent).apply { referrer?.let { putExtra(Intent.EXTRA_REFERRER, it) } })
            return
        }
        enqueue(intent)
    }

    private fun enqueue(intent: Intent) {
        if (pending++ == 0) holdOpen = false
        requests.trySend(Request(intent, referrer?.toString() == "android-app://com.android.shell"))
    }

    private suspend fun process(request: Request) {
        val entry = ApiLog.log(request.intent.action, runCatching { packageArg(request.intent) }.getOrNull())
        runCatching {
            if (!handleAction(request)) {
                holdOpen = true
                allowTouchInput()
            }
        }.onFailure {
            if (it is CancellationException) throw it
            request.detail = it.message?.replace("\n", " — ") ?: it.javaClass.simpleName
            holdOpen = true
            allowTouchInput()
            setErrorDialog(it)
        }
        request.detail?.let { ApiLog.setDetail(entry, it) }
        if (--pending == 0 && !holdOpen) finish()
    }

    /** Restores normal touch/focus handling for actions that surface an interactive Compose UI. */
    private fun allowTouchInput() = window.clearFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )

    /** @return whether the activity can finish now; false when the call left interactive UI showing. */
    private suspend fun handleAction(request: Request, action: String? = request.intent.action): Boolean {
        val intent = request.intent
        when (action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                setContent { AppTheme { RedirectBottomSheet(requirePackage(intent)) } }
                return false
            }

            Intent.ACTION_VIEW -> return handleSchema(request)

            HailApi.ACTION_LAUNCH -> {
                val pkg = requirePackage(intent)
                val tagId = runCatching { requireTagId(intent) }.getOrNull()
                val appInfo = HailData.checkedList.find { it.packageName == pkg }
                val enableBT = intent.getBooleanExtra(HailData.KEY_ENABLE_BLUETOOTH, false) || (appInfo?.enableBluetooth == true)
                val enableLoc = intent.getBooleanExtra(HailData.KEY_ENABLE_LOCATION, false) || (appInfo?.enableLocation == true)
                val enableWifi = intent.getBooleanExtra(HailData.KEY_ENABLE_WIFI, false) || (appInfo?.enableWifi == true)
                val enableHA = intent.getBooleanExtra(HailData.KEY_ENABLE_HIGH_ACCURACY, false) || (appInfo?.enableHighAccuracy == true)
                if (!request.fromShell && HailData.shortcutLaunchPrompt) {
                    setContent { AppTheme { LaunchPromptDialog(pkg, tagId, enableBT, enableLoc, enableHA, enableWifi) } }
                    return false
                }
                applyLaunchExtras(enableBT, enableLoc, enableHA, enableWifi)
                launchApp(pkg, tagId)
            }
            HailApi.ACTION_FREEZE -> request.detail = setAppFrozen(requirePackage(intent), true, forceArg(intent))
            HailApi.ACTION_UNFREEZE -> request.detail = setAppFrozen(requirePackage(intent), false)
            HailApi.ACTION_FREEZE_TAG -> request.detail = setListFrozen(
                true, HailData.checkedList.filter { requireTagId(intent) in it.tagIdList }, true
            )

            HailApi.ACTION_UNFREEZE_TAG -> request.detail = setListFrozen(
                false, HailData.checkedList.filter { requireTagId(intent) in it.tagIdList })

            HailApi.ACTION_FREEZE_ALL -> request.detail = setListFrozen(true)
            HailApi.ACTION_UNFREEZE_ALL -> request.detail = setListFrozen(false)
            HailApi.ACTION_FREEZE_NON_WHITELISTED -> request.detail = setListFrozen(true, skipWhitelisted = true)
            HailApi.ACTION_FREEZE_AUTO -> setAutoFreeze(false)
            HailApi.ACTION_LOCK -> lockScreen(false)
            HailApi.ACTION_LOCK_FREEZE -> request.detail = lockScreen(true)
            HailApi.ACTION_ADD_WHITELIST -> addToWhitelist(requirePackage(intent))
            HailApi.ACTION_REMOVE_WHITELIST -> removeFromWhitelist(packageArg(intent))
            HailApi.ACTION_DEX_MODE_ON -> HailData.dexModeExternal = true
            HailApi.ACTION_DEX_MODE_OFF -> HailData.dexModeExternal = false
            HailApi.ACTION_EXCLUDE_FOREGROUND_APP_ON -> HailData.excludeForegroundApp = true
            HailApi.ACTION_EXCLUDE_FOREGROUND_APP_OFF -> HailData.excludeForegroundApp = false
            HailApi.ACTION_FREEZE_TOAST_ON -> HailData.apiFreezeToast = true
            HailApi.ACTION_FREEZE_TOAST_OFF -> HailData.apiFreezeToast = false
            else -> throw IllegalArgumentException("Unknown action:\n$action")
        }
        return true
    }

    /**
     * Handle schema actions
     *
     * hail://launch?package=xxx
     * hail://freeze?package=xxx[&force=true]
     * hail://unfreeze?package=xxx
     * hail://freeze_tag?tag=xxx
     * hail://unfreeze_tag?tag=xxx
     * hail://freeze_all
     * hail://unfreeze_all
     * hail://freeze_non_whitelisted
     * hail://freeze_auto
     * hail://lock
     * hail://lock_freeze
     * hail://add_whitelist?package=xxx[&tag=xxx]
     * hail://remove_whitelist?package=xxx
     * hail://dex_mode_on
     * hail://dex_mode_off
     * hail://exclude_foreground_app_on
     * hail://exclude_foreground_app_off
     * hail://freeze_toast_on
     * hail://freeze_toast_off
     */
    private suspend fun handleSchema(request: Request): Boolean {
        val uri = request.intent.data
        if (uri?.scheme != "hail") throw IllegalArgumentException("Unknown scheme:\n${uri?.scheme}")
        return handleAction(
            request,
            when (uri.host) {
                "launch" -> HailApi.ACTION_LAUNCH
                "freeze" -> HailApi.ACTION_FREEZE
                "unfreeze" -> HailApi.ACTION_UNFREEZE
                "freeze_tag" -> HailApi.ACTION_FREEZE_TAG
                "unfreeze_tag" -> HailApi.ACTION_UNFREEZE_TAG
                "freeze_all" -> HailApi.ACTION_FREEZE_ALL
                "unfreeze_all" -> HailApi.ACTION_UNFREEZE_ALL
                "freeze_non_whitelisted" -> HailApi.ACTION_FREEZE_NON_WHITELISTED
                "freeze_auto" -> HailApi.ACTION_FREEZE_AUTO
                "lock" -> HailApi.ACTION_LOCK
                "lock_freeze" -> HailApi.ACTION_LOCK_FREEZE
                "add_whitelist" -> HailApi.ACTION_ADD_WHITELIST
                "remove_whitelist" -> HailApi.ACTION_REMOVE_WHITELIST
                "dex_mode_on" -> HailApi.ACTION_DEX_MODE_ON
                "dex_mode_off" -> HailApi.ACTION_DEX_MODE_OFF
                "exclude_foreground_app_on" -> HailApi.ACTION_EXCLUDE_FOREGROUND_APP_ON
                "exclude_foreground_app_off" -> HailApi.ACTION_EXCLUDE_FOREGROUND_APP_OFF
                "freeze_toast_on" -> HailApi.ACTION_FREEZE_TOAST_ON
                "freeze_toast_off" -> HailApi.ACTION_FREEZE_TOAST_OFF
                else -> throw IllegalArgumentException("Unknown host:\n${uri.host}")
            }
        )
    }

    private fun setErrorDialog(t: Throwable) = setContent { AppTheme { ErrorDialog(t) } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RedirectBottomSheet(pkg: String) = ModalBottomSheet(
        onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column {
            Text(
                text = HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager)?.toString() ?: pkg,
                modifier = Modifier.padding(
                    horizontal = dimensionResource(R.dimen.padding_medium),
                    vertical = dimensionResource(R.dimen.padding_small)
                ),
                style = MaterialTheme.typography.headlineSmall
            )
            ClickableItem(
                icon = Icons.AutoMirrored.Outlined.Launch, title = R.string.action_launch
            ) { launchApp(pkg) }
            ClickableItem(
                icon = Icons.Rounded.AcUnit, title = R.string.action_freeze
            ) {
                if (!HailData.isChecked(pkg)) HailData.addCheckedApp(pkg)
                setAppFrozen(pkg, true)
            }
            ClickableItem(
                icon = Icons.Rounded.BrightnessLow, title = R.string.action_unfreeze
            ) { setAppFrozen(pkg, false) }
        }
    }

    @Composable
    private fun ClickableItem(icon: ImageVector, @StringRes title: Int, onClick: suspend () -> Unit) = Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = {
            lifecycleScope.launch {
                runCatching {
                    onClick()
                    finish()
                }.onFailure(::setErrorDialog)
            }
        }), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
        )
        Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
    }

    @Composable
    private fun LaunchPromptDialog(
        pkg: String, tagId: Int?, enableBT: Boolean, enableLoc: Boolean, enableHA: Boolean, enableWifi: Boolean
    ) {
        val label = HPackages.getApplicationInfoOrNull(pkg)
            ?.loadLabel(packageManager)?.toString() ?: pkg
        AlertDialog(
            onDismissRequest = ::finish,
            title = { Text(text = label) },
            text = { Text(text = stringResource(R.string.shortcut_launch_prompt_title)) },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            lifecycleScope.launch {
                                runCatching {
                                    applyLaunchExtras(enableBT, enableLoc, enableHA, enableWifi)
                                    launchApp(pkg, tagId)
                                    finish()
                                }.onFailure(::setErrorDialog)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(text = stringResource(R.string.action_launch)) }
                    TextButton(
                        onClick = {
                            lifecycleScope.launch {
                                runCatching {
                                    if (!HailData.isChecked(pkg)) HailData.addCheckedApp(pkg)
                                    setAppFrozen(pkg, true)
                                    finish()
                                }.onFailure(::setErrorDialog)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(text = stringResource(R.string.action_freeze)) }
                }
            }
        )
    }

    @Composable
    private fun ErrorDialog(t: Throwable) = AlertDialog(
        text = { Text(text = t.message ?: t.stackTraceToString()) },
        onDismissRequest = ::finish,
        confirmButton = {
            TextButton(onClick = ::finish) {
                Text(text = stringResource(android.R.string.ok))
            }
        })

    // The arg readers below take the call's own [intent] rather than reading the activity's
    // current one, which a later queued call may already have replaced.

    /** Raw package name from [intent] — no installation check. */
    private fun packageArg(intent: Intent): String = intent.run {
        if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_PACKAGE)
        else getStringExtra(
            if (action != Intent.ACTION_SHOW_APP_INFO) HailData.KEY_PACKAGE
            else if (HTarget.N) Intent.EXTRA_PACKAGE_NAME
            else "android.intent.extra.PACKAGE_NAME"
        )
    } ?: throw IllegalArgumentException("Package must not be null")

    /** Package name from [intent], guaranteed to be currently installed. */
    private fun requirePackage(intent: Intent): String = packageArg(intent).also {
        HPackages.getApplicationInfoOrNull(it) ?: throw NameNotFoundException(getString(R.string.app_not_installed))
    }

    private fun requireTagId(intent: Intent): Int = intent.run {
        if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_TAG)
        else getStringExtra(HailData.KEY_TAG)
    }?.let {
        HailData.tags.find { tag -> tag.first == it }?.second
            ?: throw IllegalStateException("Tag unavailable:\n$it")
    } ?: throw IllegalArgumentException("Tag must not be null")

    /** Whether [HailApi.ACTION_FREEZE] should override [HailData.excludeForegroundApp]. */
    private fun forceArg(intent: Intent): Boolean = intent.run {
        if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_FORCE).toBoolean()
        else getBooleanExtra(HailData.KEY_FORCE, false)
    }

    /** Enables Wi-Fi/Bluetooth/location for a launch that's actually going through — never before the user commits to launching. */
    private fun applyLaunchExtras(enableBT: Boolean, enableLoc: Boolean, enableHA: Boolean = false, enableWifi: Boolean = false) =
        AppManager.applyPrerequisiteExtras(applicationContext, enableBT, enableLoc, enableHA, enableWifi)

    private suspend fun launchApp(pkg: String, tagId: Int? = null) {
        handlePrerequisiteApp(pkg)
        if (tagId != null) setListFrozen(false, HailData.checkedList.filter { tagId in it.tagIdList })
        val unfroze = withContext(Dispatchers.IO) {
            AppManager.isAppFrozen(pkg) && AppManager.setAppFrozen(pkg, false)
        }
        if (unfroze) app.setAutoFreezeService()
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            HShortcuts.addDynamicShortcut(pkg)
            AppManager.startTargetApp(this, it)
        } ?: run {
            // Launch failed (commonly because the backend e.g. Shizuku is not running and
            // the app is still frozen). Fire the automation signal before surfacing the error
            // so MacroDroid can start Shizuku.
            HUI.notifyShizukuRequired(pkg)
            throw ActivityNotFoundException(getString(R.string.activity_not_found))
        }
    }

    private suspend fun handlePrerequisiteApp(pkg: String) {
        val appInfo = HailData.checkedList.find { it.packageName == pkg } ?: return
        // Three independent delays: Play Store's and the prerequisite app's "enable" delay only
        // apply if that app was frozen and so had to be unfrozen; the "launch" delay applies
        // whenever the prerequisite app is launched
        var unfrozePrereq = false
        var unfrozePlayStore = false
        var launchedPrereq = false

        if (appInfo.enablePlayStore) {
            val (wasFrozen, unfroze) = withContext(Dispatchers.IO) {
                val frozen = AppManager.isAppFrozen(HailData.PACKAGE_PLAY_STORE)
                frozen to (frozen && AppManager.setAppFrozen(HailData.PACKAGE_PLAY_STORE, false))
            }
            if (unfroze) app.setAutoFreezeService()
            unfrozePlayStore = wasFrozen
        }

        val prereqPkg = appInfo.prereqPackage
        if (prereqPkg != null) {
            // Unfreeze the prerequisite app if it's frozen and either launch or enable is requested
            if (appInfo.prereqLaunch || appInfo.prereqEnable) {
                val (wasFrozen, unfroze) = withContext(Dispatchers.IO) {
                    val frozen = AppManager.isAppFrozen(prereqPkg)
                    frozen to (frozen && AppManager.setAppFrozen(prereqPkg, false))
                }
                if (unfroze) app.setAutoFreezeService()
                unfrozePrereq = wasFrozen
            }
            // Launch the prerequisite app after unfreezing
            if (appInfo.prereqLaunch) {
                packageManager.getLaunchIntentForPackage(prereqPkg)?.let {
                    startActivity(it)
                    launchedPrereq = true
                }
            }
        }

        val launchDelay = maxOf(
            if (unfrozePlayStore) appInfo.playStoreDelay else 0L,
            if (unfrozePrereq && appInfo.prereqEnable) appInfo.prereqDelay else 0L,
            if (launchedPrereq) appInfo.prereqLaunchDelay else 0L
        )
        if (launchDelay > 0L) delay(launchDelay)
    }

    /** @return a one-line outcome for the API log; throws (with the reason) when the change was refused. */
    private suspend fun setAppFrozen(pkg: String, frozen: Boolean, force: Boolean = false): String {
        if (frozen && !HailData.isChecked(pkg)) throw SecurityException("Package not checked: $pkg")
        val (already, reason) = withContext(Dispatchers.IO) {
            val already = AppManager.isAppFrozen(pkg) == frozen
            already to if (already) null else AppManager.setAppFrozenOrReason(pkg, frozen, force)
        }
        if (reason != null) throw IllegalStateException(
            "Couldn't ${if (frozen) "freeze" else "unfreeze"} $pkg\n$reason"
        )
        if (HailData.apiFreezeToast) {
            val label = withContext(Dispatchers.IO) {
                HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager) ?: pkg
            }
            HUI.showToast(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, label)
        }
        app.setAutoFreezeService()
        val state = if (frozen) "frozen" else "unfrozen"
        return if (already) "Already $state — nothing to do" else state.replaceFirstChar { it.uppercase() }
    }

    /** @return a one-line outcome for the API log; throws (with the reasons) when every attempt was refused. */
    private suspend fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, skipWhitelisted: Boolean = false
    ): String {
        val (attempted, result) = withContext(Dispatchers.IO) {
            val filtered = list.filter {
                AppManager.isAppFrozen(it.packageName) != frozen && !(skipWhitelisted && it.whitelisted)
            }
            filtered.size to AppManager.setListFrozenWithReasons(frozen, *filtered.toTypedArray())
        }
        val summary = result.summary ?: throw IllegalStateException(
            getString(R.string.permission_denied_pkg, result.deniedPackage ?: "") + "\n" + result.describe(frozen)
        )
        if (HailData.apiFreezeToast) HUI.showToast(
            if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, summary
        )
        app.setAutoFreezeService()
        return when {
            list.isEmpty() -> "Nothing to change — no apps in the list"
            attempted == 0 -> "Nothing to change — every app in the list is already " +
                    (if (frozen) "frozen" else "unfrozen") + (if (skipWhitelisted) " or whitelisted" else "")
            else -> result.describe(frozen)
        }
    }

    private suspend fun addToWhitelist(pkg: String) {
        val info = HailData.checkedList.find { it.packageName == pkg }
            ?: throw IllegalStateException(getString(R.string.app_not_in_home, pkg))
        if (info.whitelisted) throw IllegalStateException(
            getString(R.string.app_already_in_whitelist, pkg)
        )
        info.whitelisted = true
        HailData.saveApps()
        val label = withContext(Dispatchers.IO) {
            HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager) ?: pkg
        }
        HUI.showToast(R.string.msg_whitelist_add, label)
    }

    private suspend fun removeFromWhitelist(pkg: String) {
        val info = HailData.checkedList.find { it.packageName == pkg }
            ?: throw IllegalStateException(getString(R.string.app_not_in_home, pkg))
        if (!info.whitelisted) throw IllegalStateException(
            getString(R.string.app_not_in_whitelist, pkg)
        )
        info.whitelisted = false
        HailData.saveApps()
        val label = withContext(Dispatchers.IO) {
            HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager) ?: pkg
        }
        HUI.showToast(R.string.msg_whitelist_remove, label)
    }

    /** @return the freeze outcome when [freezeAll], for the API log. */
    private suspend fun lockScreen(freezeAll: Boolean): String? {
        val detail = if (freezeAll) setListFrozen(true) else null
        if (!withContext(Dispatchers.IO) { AppManager.lockScreen }) {
            throw IllegalStateException(getString(R.string.permission_denied))
        }
        return detail
    }
}