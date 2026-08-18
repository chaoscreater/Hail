package com.aistra.hail.ui.api

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block this window from intercepting touches/focus while it's invisible: without this,
        // rapid repeat API calls (e.g. FREEZE fired 2-3x/sec) stack up transparent windows that
        // eat all touch input on top of the foreground app until they finish() and clear.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        handleIntent()
    }

    // launchMode="singleInstance" (see the manifest) means a second rapid API call is delivered
    // here instead of creating a new instance/window, as long as this one hasn't finished yet.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        lifecycleScope.launch {
            ApiLog.log(intent.action, runCatching { packageArg }.getOrNull())
            runCatching {
                if (handleAction(intent.action)) finish() else allowTouchInput()
            }.onFailure {
                allowTouchInput()
                setErrorDialog(it)
            }
        }
    }

    /** Restores normal touch/focus handling for actions that surface an interactive Compose UI. */
    private fun allowTouchInput() = window.clearFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )

    private suspend fun handleAction(action: String?): Boolean {
        when (action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                setContent { AppTheme { RedirectBottomSheet(requirePackage) } }
                return false
            }

            Intent.ACTION_VIEW -> return handleSchema(intent.data)

            HailApi.ACTION_LAUNCH -> {
                val pkg = requirePackage
                val tagId = runCatching { requireTagId }.getOrNull()
                val appInfo = HailData.checkedList.find { it.packageName == pkg }
                val enableBT = intent.getBooleanExtra(HailData.KEY_ENABLE_BLUETOOTH, false) || (appInfo?.enableBluetooth == true)
                val enableLoc = intent.getBooleanExtra(HailData.KEY_ENABLE_LOCATION, false) || (appInfo?.enableLocation == true)
                val fromShell = referrer?.toString() == "android-app://com.android.shell"
                if (!fromShell && HailData.shortcutLaunchPrompt) {
                    setContent { AppTheme { LaunchPromptDialog(pkg, tagId, enableBT, enableLoc) } }
                    return false
                }
                applyLaunchExtras(enableBT, enableLoc)
                launchApp(pkg, tagId)
            }
            HailApi.ACTION_FREEZE -> setAppFrozen(requirePackage, true)
            HailApi.ACTION_UNFREEZE -> setAppFrozen(requirePackage, false)
            HailApi.ACTION_FREEZE_TAG -> setListFrozen(
                true, HailData.checkedList.filter { requireTagId in it.tagIdList }, true
            )

            HailApi.ACTION_UNFREEZE_TAG -> setListFrozen(
                false, HailData.checkedList.filter { requireTagId in it.tagIdList })

            HailApi.ACTION_FREEZE_ALL -> setListFrozen(true)
            HailApi.ACTION_UNFREEZE_ALL -> setListFrozen(false)
            HailApi.ACTION_FREEZE_NON_WHITELISTED -> setListFrozen(true, skipWhitelisted = true)
            HailApi.ACTION_FREEZE_AUTO -> setAutoFreeze(false)
            HailApi.ACTION_LOCK -> lockScreen(false)
            HailApi.ACTION_LOCK_FREEZE -> lockScreen(true)
            HailApi.ACTION_ADD_WHITELIST -> addToWhitelist(requirePackage)
            HailApi.ACTION_REMOVE_WHITELIST -> removeFromWhitelist(packageArg)
            else -> throw IllegalArgumentException("Unknown action:\n$action")
        }
        return true
    }

    /**
     * Handle schema actions
     *
     * hail://launch?package=xxx
     * hail://freeze?package=xxx
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
     */
    private suspend fun handleSchema(uri: Uri?): Boolean {
        if (uri?.scheme != "hail") throw IllegalArgumentException("Unknown scheme:\n${uri?.scheme}")
        return handleAction(
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
    private fun LaunchPromptDialog(pkg: String, tagId: Int?, enableBT: Boolean, enableLoc: Boolean) {
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
                                    applyLaunchExtras(enableBT, enableLoc)
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

    /** Raw package name from the intent — no installation check. */
    private val packageArg: String
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_PACKAGE)
            else getStringExtra(
                if (action != Intent.ACTION_SHOW_APP_INFO) HailData.KEY_PACKAGE
                else if (HTarget.N) Intent.EXTRA_PACKAGE_NAME
                else "android.intent.extra.PACKAGE_NAME"
            )
        } ?: throw IllegalArgumentException("Package must not be null")

    /** Package name, guaranteed to be currently installed. */
    private val requirePackage: String
        get() = packageArg.also {
            HPackages.getApplicationInfoOrNull(it) ?: throw NameNotFoundException(getString(R.string.app_not_installed))
        }

    private val requireTagId: Int
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_TAG)
            else getStringExtra(HailData.KEY_TAG)
        }?.let {
            HailData.tags.find { tag -> tag.first == it }?.second
                ?: throw IllegalStateException("Tag unavailable:\n$it")
        } ?: throw IllegalArgumentException("Tag must not be null")

    /** Enables Bluetooth/location for a launch that's actually going through — never before the user commits to launching. */
    private fun applyLaunchExtras(enableBT: Boolean, enableLoc: Boolean) {
        if (!enableBT && !enableLoc) return
        val appContext = applicationContext
        kotlin.concurrent.thread {
            if (enableBT) AppManager.enableBluetooth(appContext)
            if (enableLoc) AppManager.enableLocation(appContext)
        }
    }

    private suspend fun launchApp(pkg: String, tagId: Int? = null) {
        handlePrerequisiteApp(pkg)
        if (tagId != null) setListFrozen(false, HailData.checkedList.filter { tagId in it.tagIdList })
        val unfroze = withContext(Dispatchers.IO) {
            AppManager.isAppFrozen(pkg) && AppManager.setAppFrozen(pkg, false)
        }
        if (unfroze) app.setAutoFreezeService()
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            HShortcuts.addDynamicShortcut(pkg)
            startActivity(it)
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
        val prereqPkg = appInfo.prereqPackage ?: return

        // Unfreeze the prerequisite app if it's frozen and either launch or enable is requested
        if (appInfo.prereqLaunch || appInfo.prereqEnable) {
            val unfroze = withContext(Dispatchers.IO) {
                AppManager.isAppFrozen(prereqPkg) && AppManager.setAppFrozen(prereqPkg, false)
            }
            if (unfroze) app.setAutoFreezeService()
        }
        // Launch the prerequisite app after unfreezing
        if (appInfo.prereqLaunch) {
            packageManager.getLaunchIntentForPackage(prereqPkg)?.let { startActivity(it) }
        }
    }

    private suspend fun setAppFrozen(pkg: String, frozen: Boolean) {
        if (frozen && !HailData.isChecked(pkg)) throw SecurityException("Package not checked: $pkg")
        val denied = withContext(Dispatchers.IO) {
            AppManager.isAppFrozen(pkg) != frozen && !AppManager.setAppFrozen(pkg, frozen)
        }
        if (denied) throw IllegalStateException(getString(R.string.permission_denied_pkg, pkg))
        if (HailData.apiFreezeToast) {
            val label = withContext(Dispatchers.IO) {
                HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(packageManager) ?: pkg
            }
            HUI.showToast(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, label)
        }
        app.setAutoFreezeService()
    }

    private suspend fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, skipWhitelisted: Boolean = false
    ) {
        val result = withContext(Dispatchers.IO) {
            val filtered = list.filter {
                AppManager.isAppFrozen(it.packageName) != frozen && !(skipWhitelisted && it.whitelisted)
            }
            AppManager.setListFrozen(frozen, *filtered.toTypedArray())
        }
        when (result) {
            null -> throw IllegalStateException(
                getString(R.string.permission_denied_pkg, AppManager.lastDeniedPackage ?: "")
            )
            else -> {
                if (HailData.apiFreezeToast) HUI.showToast(
                    if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result
                )
                app.setAutoFreezeService()
            }
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

    private suspend fun lockScreen(freezeAll: Boolean) {
        if (freezeAll) setListFrozen(true)
        if (!withContext(Dispatchers.IO) { AppManager.lockScreen }) {
            throw IllegalStateException(getString(R.string.permission_denied))
        }
    }
}