package com.aistra.hail.utils

import com.aistra.hail.HailApp.Companion.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ApiLogEntry(val timestamp: Long, val action: String, val packageName: String?)

/**
 * Records every API intent [com.aistra.hail.ui.api.ApiActivity] and
 * [com.aistra.hail.receiver.ApiReceiver] receive, so they can be reviewed later from the Logs
 * screen — the same information that previously required an adb logcat capture to see.
 */
object ApiLog {
    private const val MAX_ENTRIES = 300
    private const val KEY_TIME = "time"
    private const val KEY_ACTION = "action"
    private const val KEY_PACKAGE = "package"
    private val path = "${app.filesDir.path}/v1/api_logs.json"
    private val lock = Any()

    private val entries: MutableList<ApiLogEntry> by lazy {
        mutableListOf<ApiLogEntry>().apply {
            runCatching {
                val json = JSONArray(HFiles.read(path))
                for (i in 0 until json.length()) {
                    with(json.getJSONObject(i)) {
                        add(ApiLogEntry(getLong(KEY_TIME), getString(KEY_ACTION), optString(KEY_PACKAGE).ifEmpty { null }))
                    }
                }
            }
        }
    }

    suspend fun log(action: String?, packageName: String?) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            entries.add(0, ApiLogEntry(System.currentTimeMillis(), action ?: "?", packageName))
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
            save()
        }
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
                })
            }
        }.toString())
    }
}
