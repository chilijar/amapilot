package com.amapilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 地点历史（搜索/分享/车机选点共享源）：**不记搜索关键词**（用户目标始终是地点）。
 * ts 倒序、同名同坐标去重置顶、容量 [MAX_ENTRIES]；首次使用迁移旧 [PlaceHistoryStore]。
 */
class HistoryStore(context: Context) {

    data class Rec(
        val name: String,
        val address: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val ts: Long = System.currentTimeMillis(),
        /** 原 POI id（收藏钮与收藏表同 id 语义对齐；旧记录为空 = 坐标回退）。 */
        val id: String = "",
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateOnce(context)
    }

    /** 全部地点历史，最近在前。 */
    fun load(): List<Rec> = parse().sortedByDescending { it.ts }

    /** 记录地点选点。 */
    fun addPlace(name: String, address: String, lat: Double, lng: Double, id: String = "") {
        if (name.isBlank() || (lat == 0.0 && lng == 0.0)) return
        val rec = Rec(name, address, lat, lng, id = id)
        val rest = load().filterNot {
            it.name == rec.name && it.lat == rec.lat && it.lng == rec.lng
        }
        save((listOf(rec) + rest).take(MAX_ENTRIES))
    }

    fun clear() = save(emptyList())

    /** 旧 PlaceHistoryStore 数据一次性并入（已有数据则跳过，避免覆盖新记录）。 */
    private fun migrateOnce(context: Context) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        if (!prefs.getString(KEY_HISTORY, null).isNullOrBlank()) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        val places = runCatching {
            PlaceHistoryStore(context).load().map {
                Rec(it.name, it.address, it.lat, it.lng, it.ts)
            }
        }.getOrDefault(emptyList())
        save(places.sortedByDescending { it.ts }.take(MAX_ENTRIES))
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun parse(): List<Rec> {
        val arr = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(arr)
            (0 until json.length()).mapNotNull { i ->
                val o = json.getJSONObject(i)
                // 旧统一历史的 kind=QUERY 条目：丢弃
                if (o.optString("kind") == "QUERY") return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) return@mapNotNull null
                Rec(
                    name = name,
                    address = o.optString("address"),
                    lat = o.optDouble("lat"),
                    lng = o.optDouble("lng"),
                    ts = o.optLong("ts"),
                    id = o.optString("id"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(entries: List<Rec>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("name", e.name)
                    put("address", e.address)
                    put("lat", e.lat)
                    put("lng", e.lng)
                    put("ts", e.ts)
                    put("id", e.id)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "amapilot_prefs"
        private const val KEY_HISTORY = "unified_history"
        private const val KEY_MIGRATED = "unified_history_migrated_v2"

        /** 地点历史容量。 */
        const val MAX_ENTRIES = 20
    }
}
