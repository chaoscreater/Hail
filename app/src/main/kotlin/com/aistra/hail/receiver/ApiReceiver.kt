package com.aistra.hail.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.ApiLog
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import com.aistra.hail.work.HWork.setAutoFreeze
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles the non-interactive, automation-oriented API actions (FREEZE, UNFREEZE, LOCK, etc.)
 * the same way [com.aistra.hail.ui.api.ApiActivity] does, but without ever creating a window.
 * ApiActivity still handles the exact same action strings via `am start`, for actions that need
 * to show UI (LAUNCH's prompt, SHOW_APP_INFO) and for backward compatibility. For these
 * non-interactive actions, prefer triggering this receiver with `am broadcast` instead —
 * since there's no window/Activity involved, there's nothing for Android to run a window
 * transition around, so it can't be the source of any touch-input stall the way repeated rapid
 * ApiActivity launches could be.
 */
class ApiReceiver : BroadcastReceiver() {
    companion object {
        private const val TOAST_DEBOUNCE_MS = 1500L

        // Process-lifetime, not tied to any single broadcast dispatch, so the debounce below can
        // span multiple separate onReceive() calls.
        private val scope = CoroutineScope(Dispatchers.Main)

        private val pendingCount = mutableMapOf(true to 0, false to 0)
        private val pendingLastLabel = mutableMapOf<Boolean, CharSequence?>(true to null, false to null)
        private val flushJobs = mutableMapOf<Boolean, Job?>(true to null, false to null)

        /**
         * Queues [count] more freeze/unfreeze changes (with [lastLabel] as the most recently
         * changed app's name) for a combined toast instead of showing one immediately. Every
         * toast-producing path — individual FREEZE/UNFREEZE calls *and* bulk ones like
         * FREEZE_ALL/FREEZE_NON_WHITELISTED — goes through this same debounce: two separate
         * toasts landing close together (one from an individual call, one from a bulk call) was
         * enough to reproduce the touch-block on its own, even with each side individually
         * debounced/batched.
         *
         * Deliberately does NOT hold any [android.content.BroadcastReceiver.PendingResult] open
         * across the debounce delay: `am broadcast` blocks its caller until the matching
         * PendingResult.finish() is called, so if this held one open until the batch flushed,
         * the automation loop sending these broadcasts couldn't send its next one until the
         * previous batch finished — which starved the batching of the very calls it was supposed
         * to be collecting (confirmed via logcat: each next call landed ~10-20ms after the
         * previous flush, tracking the debounce length almost exactly).
         */
        private fun queueToast(frozen: Boolean, count: Int, lastLabel: CharSequence) {
            pendingCount[frozen] = pendingCount.getValue(frozen) + count
            pendingLastLabel[frozen] = lastLabel
            flushJobs[frozen]?.cancel()
            flushJobs[frozen] = scope.launch {
                delay(TOAST_DEBOUNCE_MS)
                val total = pendingCount.getValue(frozen)
                if (total > 0) {
                    HUI.showToast(
                        if (frozen) R.string.msg_freeze else R.string.msg_unfreeze,
                        if (total == 1) pendingLastLabel.getValue(frozen)!! else total.toString()
                    )
                    pendingCount[frozen] = 0
                    pendingLastLabel[frozen] = null
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            ApiLog.log(intent.action, intent.getStringExtra(HailData.KEY_PACKAGE))
            runCatching { handleAction(intent) }
                .onFailure { HUI.showToast(it.message ?: it.stackTraceToString()) }
            pendingResult.finish()
        }
    }

    private suspend fun handleAction(intent: Intent) {
        when (intent.action) {
            HailApi.ACTION_FREEZE -> setAppFrozen(requirePackage(intent), true)
            HailApi.ACTION_UNFREEZE -> setAppFrozen(requirePackage(intent), false)
            HailApi.ACTION_FREEZE_TAG -> setListFrozen(
                true, HailData.checkedList.filter { requireTagId(intent) in it.tagIdList }, true
            )

            HailApi.ACTION_UNFREEZE_TAG -> setListFrozen(
                false, HailData.checkedList.filter { requireTagId(intent) in it.tagIdList })

            HailApi.ACTION_FREEZE_ALL -> setListFrozen(true)
            HailApi.ACTION_UNFREEZE_ALL -> setListFrozen(false)
            HailApi.ACTION_FREEZE_NON_WHITELISTED -> setListFrozen(true, skipWhitelisted = true)
            HailApi.ACTION_FREEZE_AUTO -> setAutoFreeze(false)
            HailApi.ACTION_LOCK -> lockScreen(false)
            HailApi.ACTION_LOCK_FREEZE -> lockScreen(true)
            HailApi.ACTION_ADD_WHITELIST -> addToWhitelist(requirePackage(intent))
            HailApi.ACTION_REMOVE_WHITELIST -> removeFromWhitelist(packageArg(intent))
            else -> throw IllegalArgumentException("Unknown action:\n${intent.action}")
        }
    }

    private fun packageArg(intent: Intent): String =
        intent.getStringExtra(HailData.KEY_PACKAGE) ?: throw IllegalArgumentException("Package must not be null")

    private fun requirePackage(intent: Intent): String = packageArg(intent).also {
        HPackages.getApplicationInfoOrNull(it) ?: throw NameNotFoundException(app.getString(R.string.app_not_installed))
    }

    private fun requireTagId(intent: Intent): Int = intent.getStringExtra(HailData.KEY_TAG)?.let {
        HailData.tags.find { tag -> tag.first == it }?.second
            ?: throw IllegalStateException("Tag unavailable:\n$it")
    } ?: throw IllegalArgumentException("Tag must not be null")

    private suspend fun setAppFrozen(pkg: String, frozen: Boolean) {
        if (frozen && !HailData.isChecked(pkg)) throw SecurityException("Package not checked: $pkg")
        val denied = withContext(Dispatchers.IO) {
            AppManager.isAppFrozen(pkg) != frozen && !AppManager.setAppFrozen(pkg, frozen)
        }
        if (denied) throw IllegalStateException(app.getString(R.string.permission_denied_pkg, pkg))
        app.setAutoFreezeService()
        if (!HailData.apiFreezeToast) return
        val label = withContext(Dispatchers.IO) {
            HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(app.packageManager) ?: pkg
        }
        queueToast(frozen, 1, label)
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
                app.getString(R.string.permission_denied_pkg, AppManager.lastDeniedPackage ?: "")
            )

            else -> {
                app.setAutoFreezeService()
                // AppManager.setListFrozen()'s return is either the single changed app's name
                // (when exactly one changed) or the count of changed apps as a string (when
                // more than one did) — recover the count so it can merge into the same running
                // total as individual FREEZE/UNFREEZE calls.
                if (HailData.apiFreezeToast) queueToast(frozen, result.toIntOrNull() ?: 1, result)
            }
        }
    }

    private suspend fun addToWhitelist(pkg: String) {
        val info = HailData.checkedList.find { it.packageName == pkg }
            ?: throw IllegalStateException(app.getString(R.string.app_not_in_home, pkg))
        if (info.whitelisted) throw IllegalStateException(
            app.getString(R.string.app_already_in_whitelist, pkg)
        )
        info.whitelisted = true
        HailData.saveApps()
        val label = withContext(Dispatchers.IO) {
            HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(app.packageManager) ?: pkg
        }
        HUI.showToast(R.string.msg_whitelist_add, label)
    }

    private suspend fun removeFromWhitelist(pkg: String) {
        val info = HailData.checkedList.find { it.packageName == pkg }
            ?: throw IllegalStateException(app.getString(R.string.app_not_in_home, pkg))
        if (!info.whitelisted) throw IllegalStateException(
            app.getString(R.string.app_not_in_whitelist, pkg)
        )
        info.whitelisted = false
        HailData.saveApps()
        val label = withContext(Dispatchers.IO) {
            HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(app.packageManager) ?: pkg
        }
        HUI.showToast(R.string.msg_whitelist_remove, label)
    }

    private suspend fun lockScreen(freezeAll: Boolean) {
        if (freezeAll) setListFrozen(true)
        if (!withContext(Dispatchers.IO) { AppManager.lockScreen }) {
            throw IllegalStateException(app.getString(R.string.permission_denied))
        }
    }
}
