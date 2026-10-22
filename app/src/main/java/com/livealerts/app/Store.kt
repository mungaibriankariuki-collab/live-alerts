package com.livealerts.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Channel(
    /** "@handle" or "channel/UC…", appended to https://www.youtube.com/ */
    val path: String,
    val name: String,
    val live: Boolean = false,
    val liveVideoId: String? = null,
    val liveTitle: String? = null,
    val lastNotifiedVideoId: String? = null,
    val lastChecked: Long = 0,
    val error: Boolean = false,
)

object Store {
    const val MAX_CHANNELS = 20
    const val KEY_CHANNELS = "channels"
    private const val KEY_FAST = "fast_mode"
    private val lock = Any()

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("live_alerts", Context.MODE_PRIVATE)

    fun channels(ctx: Context): List<Channel> = synchronized(lock) {
        decode(prefs(ctx).getString(KEY_CHANNELS, null))
    }

    /** Read-modify-write under one lock so the checker and the UI never overwrite each other. */
    fun update(ctx: Context, change: (List<Channel>) -> List<Channel>) = synchronized(lock) {
        val updated = change(channels(ctx))
        prefs(ctx).edit().putString(KEY_CHANNELS, encode(updated)).commit()
    }

    fun isFast(ctx: Context) = prefs(ctx).getBoolean(KEY_FAST, false)

    fun setFast(ctx: Context, fast: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FAST, fast).apply()
    }

    private fun encode(list: List<Channel>): String = JSONArray().apply {
        list.forEach { c ->
            put(JSONObject().apply {
                put("path", c.path)
                put("name", c.name)
                put("live", c.live)
                put("liveVideoId", c.liveVideoId ?: "")
                put("liveTitle", c.liveTitle ?: "")
                put("lastNotifiedVideoId", c.lastNotifiedVideoId ?: "")
                put("lastChecked", c.lastChecked)
                put("error", c.error)
            })
        }
    }.toString()

    private fun decode(json: String?): List<Channel> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Channel(
                path = o.getString("path"),
                name = o.getString("name"),
                live = o.optBoolean("live"),
                liveVideoId = o.optString("liveVideoId").ifEmpty { null },
                liveTitle = o.optString("liveTitle").ifEmpty { null },
                lastNotifiedVideoId = o.optString("lastNotifiedVideoId").ifEmpty { null },
                lastChecked = o.optLong("lastChecked"),
                error = o.optBoolean("error"),
            )
        }
    }
}
