package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.aistra.hail.BuildConfig
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object HShizuku {
    val isRoot get() = Shizuku.getUid() == 0
    private val callerPackage get() = if (isRoot) BuildConfig.APPLICATION_ID else "com.android.shell"

    /**
     * Whether Shizuku is connected and has already been granted permission. Checked before calls
     * (like [resizeTask]) that aren't already gated behind [com.aistra.hail.app.HailData.workingMode]
     * being a Shizuku mode — those features don't require the user's freeze mode to be Shizuku,
     * just for Shizuku to happen to be set up, so no permission is requested here on their behalf.
     */
    val isReady: Boolean
        get() = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    // android.app.ActivityTaskManager.RESIZE_MODE_USER_FORCED — hidden constant, inlined since
    // it's not part of the public SDK. Forces the resize through past any declared min/max size
    // or resizability constraints, the same as a real user drag-resize would.
    private const val RESIZE_MODE_USER_FORCED = 3

    private fun asInterface(className: String, original: IBinder): Any = Class.forName("$className\$Stub").run {
        if (HTarget.P) HiddenApiBypass.invoke(this, null, "asInterface", ShizukuBinderWrapper(original))
        else getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(original))
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            val input = asInterface("android.hardware.input.IInputManager", Context.INPUT_SERVICE)
            val inject = input::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.java
            )
            val now = SystemClock.uptimeMillis()
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), 0
            )
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), 0
            )
            true
        }.getOrElse {
            HLog.e(it)
            false
        }

    fun forceStopApp(packageName: String): Boolean = runCatching {
        asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE).let {
            if (HTarget.P) HiddenApiBypass.invoke(
                it::class.java, it, "forceStopPackage", packageName, HPackages.myUserId
            ) else it::class.java.getMethod(
                "forceStopPackage", String::class.java, Int::class.java
            ).invoke(
                it, packageName, HPackages.myUserId
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean {
        HPackages.getApplicationInfoOrNull(packageName) ?: return false
        if (disabled) forceStopApp(packageName)
        runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            pm::class.java.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                String::class.java
            ).invoke(pm, packageName, newState, 0, HPackages.myUserId, BuildConfig.APPLICATION_ID)
        }.onFailure {
            HLog.e(it)
        }
        return HPackages.isAppDisabled(packageName) == disabled
    }

    fun setAppHidden(packageName: String, hidden: Boolean): Boolean {
        HPackages.getApplicationInfoOrNull(packageName) ?: return false
        if (hidden) forceStopApp(packageName)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            pm::class.java.getMethod(
                "setApplicationHiddenSettingAsUser", String::class.java, Boolean::class.java, Int::class.java
            ).invoke(pm, packageName, hidden, HPackages.myUserId) as Boolean
        }.getOrElse {
            HLog.e(it)
            false
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
        HPackages.getApplicationInfoOrNull(packageName) ?: return false
        if (HTarget.P) setAppRestricted(packageName, suspended)
        if (suspended) forceStopApp(packageName)
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            (when {
                HTarget.U -> runCatching {
                    HiddenApiBypass.invoke(
                        pm::class.java,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(packageName),
                        suspended,
                        null,
                        null,
                        if (suspended) suspendDialogInfo else null,
                        0,
                        callerPackage,
                        HPackages.myUserId /*suspendingUserId*/,
                        HPackages.myUserId /*targetUserId*/
                    )
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended)
                    else throw it
                }

                HTarget.Q -> runCatching {
                    setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended)
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceP(pm, packageName, suspended)
                    else throw it
                }

                HTarget.P -> setPackagesSuspendedAsUserSinceP(pm, packageName, suspended)

                HTarget.N -> pm::class.java.getMethod(
                    "setPackagesSuspendedAsUser", Array<String>::class.java, Boolean::class.java, Int::class.java
                ).invoke(pm, arrayOf(packageName), suspended, HPackages.myUserId)

                else -> return false
            } as Array<*>).isEmpty()
        }.getOrElse {
            HLog.e(it)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPackagesSuspendedAsUserSinceQ(pm: Any, packageName: String, suspended: Boolean): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            if (suspended) suspendDialogInfo else null,
            callerPackage,
            HPackages.myUserId
        )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setPackagesSuspendedAsUserSinceP(pm: Any, packageName: String, suspended: Boolean): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            null /*dialogMessage*/,
            callerPackage,
            HPackages.myUserId
        )

    private val suspendDialogInfo: Any
        @RequiresApi(Build.VERSION_CODES.Q) @SuppressLint("PrivateApi") get() = HiddenApiBypass.newInstance(
            Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        ).let {
            HiddenApiBypass.invoke(it::class.java, it, "setNeutralButtonAction", 1 /*BUTTON_ACTION_UNSUSPEND*/)
            HiddenApiBypass.invoke(it::class.java, it, "build")
        }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        val appops = asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
        HiddenApiBypass.invoke(
            appops::class.java,
            appops,
            "setMode",
            HiddenApiBypass.invoke(AppOpsManager::class.java, null, "strOpToOp", "android:run_any_in_background"),
            HPackages.packageUid(packageName),
            packageName,
            if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
        )
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    /**
     * Resizes the task [taskId] to [bounds] via the hidden AMS/ATMS `resizeTask` call — used to
     * maximize Hail's own already-running window under Samsung DeX (see
     * [HSamsungDex.maximizeSelfIfNeeded]), where, unlike a fresh launch, there's no
     * [android.app.ActivityOptions] to attach maximized bounds to before the window exists.
     * `resizeTask` lived on `IActivityManager` (service "activity") until Android 10 split task
     * management out into `IActivityTaskManager` (service "activity_task" — not part of the
     * public SDK, hence the raw string); this picks whichever interface actually owns it on the
     * running OS.
     */
    fun resizeTask(taskId: Int, bounds: Rect): Boolean = runCatching {
        val service = if (HTarget.Q) asInterface("android.app.IActivityTaskManager", "activity_task")
        else asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
        if (HTarget.P) HiddenApiBypass.invoke(
            service::class.java, service, "resizeTask", taskId, bounds, RESIZE_MODE_USER_FORCED
        ) else service::class.java.getMethod(
            "resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType
        ).invoke(service, taskId, bounds, RESIZE_MODE_USER_FORCED)
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    /**
     * Finds the taskId of [packageName]'s current top-level task by parsing `dumpsys activity
     * activities` for its `Task{<hash> #<id> ... A=<uid>:<packageName>...}` line — used to
     * [resizeTask] a target app's window after launch (see [HSamsungDex]) once its task actually
     * exists, rather than relying solely on launch-time [android.app.ActivityOptions] bounds,
     * which some OEM window-placement policies override right after applying regardless of what
     * was requested. Parses the exact `dumpsys`/`am task resize` command pair confirmed by hand on
     * a real device, rather than a hidden `getTasks()` AIDL call untested here.
     */
    fun findTaskId(packageName: String): Int? = runCatching {
        val output = execute("dumpsys activity activities | grep -m1 'Task{.*:$packageName'").second
        Regex("""#(\d+)""").find(output.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }.getOrElse {
        HLog.e(it)
        null
    }

    fun uninstallApp(packageName: String): Boolean =
        execute("pm ${if (HPackages.canUninstallNormally(packageName)) "uninstall" else "uninstall --user current"} $packageName").first == 0

    fun reinstallApp(packageName: String): Boolean =
        execute("pm install-existing --user current $packageName").first == 0

    /**
     * Overrides [packageName]'s own `resizeableActivity="false"`/max-aspect-ratio manifest
     * declaration via Android's compat framework, scoped to just that package — used before a
     * maximized DeX launch ([HSamsungDex.startMaximized]) for apps that would otherwise be
     * letterboxed by the OS to a smaller, pillarboxed window centered inside the freeform bounds
     * Hail requested, regardless of those launch options. `am compat` is a plain shell command
     * (not a hidden API), reachable via [execute] the same way `pm` commands above are; it's a
     * safe no-op on OS builds that don't have the FORCE_RESIZE_APP change id (fails silently) and
     * harmless to re-apply to an app that's already resizable.
     */
    fun forceResizable(packageName: String): Boolean =
        execute("am compat enable FORCE_RESIZE_APP $packageName").first == 0

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                    it.write(command.toByteArray())
                }
                waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
            }
    }.getOrElse { 1 to it.stackTraceToString() }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this).use { it.bufferedReader().readText() }
}
