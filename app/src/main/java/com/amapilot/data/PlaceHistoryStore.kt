package com.amapilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 已并入 [HistoryStore]：仅作迁移数据源保留，新代码勿用。 */
@Deprecated("use HistoryStore", ReplaceWith("HistoryStore(context)"))
class PlaceHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 地点记录。 */
    data class PlaceRec(
        val name: String,
        val address: String,
        val lat: Double,
        val lng: Double,
        val ts: Long = System.currentTimeMillis(),
    )

    /** 读取记录，最近在前。 */
    fun load(): List<PlaceRec> {
        val arr = prefs.getString(KEY_PLACES, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(arr)
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                PlaceRec(
                    o.optString("name"),
                    o.optString("address"),
                    o.optDouble("lat"),
                    o.optDouble("lng"),
                    o.optLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 追加选点（同名同坐标去重置顶），超容量截断并持久化。 */
    fun add(rec: PlaceRec) {
        if (rec.name.isBlank() || (rec.lat == 0.0 && rec.lng == 0.0)) return
        val rest = load().filterNot {
            it.name == rec.name && it.lat == rec.lat && it.lng == rec.lng
        }
        save((listOf(rec) + rest).take(MAX_ENTRIES))
    }

    private fun save(entries: List<PlaceRec>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("name", e.name)
                    put("address", e.address)
                    put("lat", e.lat)
                    put("lng", e.lng)
                    put("ts", e.ts)
                }
            )
        }
        prefs.edit().putString(KEY_PLACES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "amapilot_prefs"
        private const val KEY_PLACES = "car_place_history"
        private const val MAX_ENTRIES = 8
    }
}
