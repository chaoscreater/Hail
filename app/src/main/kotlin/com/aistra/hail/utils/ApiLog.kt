package com.aistra.hail.utils

import com.aistra.hail.HailApp.Companion.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ApiLogEntry(
    val timestamp: Long,
    val action: String,
    val packageName: String? = null,
    val detail: String? = null
)

/**
 * Records every API intent [com.aistra.hail.ui.api.ApiActivity] and
 * [com.aistra.hail.receiver.ApiReceiver] receive, so they can be reviewed later from the Logs
 * screen — the same information that previously required an adb logcat capture to see. Also used
 * by [HSamsungDex] to surface DeX-detection diagnostics there, since a device already switched
 * into desktop mode may not have easy adb access.
 */
object ApiLog {
    private const val MAX_ENTRIES = 300
    private const val KEY_TIME = "time"
    private const val KEY_ACTION = "action"
    private const val KEY_PACKAGE = "package"
    private const val KEY_DETAIL = "detail"
    private val path = "${app.filesDir.path}/v1/api_logs.json"
    private val lock = Any()

    // Shared across all logAsync() calls instead of a fresh CoroutineScope per call — a
    // SupervisorJob so one failed write can't cancel the scope for subsequent log calls.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val entries: MutableList<ApiLogEntry> by lazy {
        mutableListOf<ApiLogEntry>().apply {
            runCatching {
                val json = JSONArray(HFiles.read(path))
                for (i in 0 until json.length()) {
                    with(json.getJSONObject(i)) {
                        add(
                            ApiLogEntry(
                                getLong(KEY_TIME),
                                getString(KEY_ACTION),
                                optString(KEY_PACKAGE).ifEmpty { null },
                                optString(KEY_DETAIL).ifEmpty { null }
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun log(action: String?, packageName: String?, detail: String? = null): ApiLogEntry =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val entry = ApiLogEntry(System.currentTimeMillis(), action ?: "?", packageName, detail)
                entries.add(0, entry)
                while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
                save()
                entry
            }
        }

    /**
     * Attaches the outcome to an entry returned by [log] once the call has finished — entries are
     * written when a call arrives, before it's known whether it worked. Does nothing if the entry
     * has since been cleared or pushed out by newer ones.
     */
    suspend fun setDetail(entry: ApiLogEntry, detail: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val index = entries.indexOfFirst { it === entry }
            if (index >= 0) {
                entries[index] = entry.copy(detail = detail)
                save()
            }
        }
    }

    /** Fire-and-forget variant of [log] for callers outside a coroutine, e.g. [HSamsungDex]'s synchronous launch paths. */
    fun logAsync(action: String, packageName: String? = null, detail: String? = null) {
        ioScope.launch { log(action, packageName, detail) }
    }

    fun getAll(): List<ApiLogEntry> = synchronized(lock) { entries.toList() }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            entries.clear()
            save()
        }
    }

    private fun save() {
        HFiles.write(path, JSONArray().apply {
            entries.forEach {
                put(JSONObject().put(KEY_TIME, it.timestamp).put(KEY_ACTION, it.action).apply {
                    it.packageName?.let { pkg -> put(KEY_PACKAGE, pkg) }
                    it.detail?.let { d -> put(KEY_DETAIL, d) }
                })
            }
        }.toString())
    }
}
