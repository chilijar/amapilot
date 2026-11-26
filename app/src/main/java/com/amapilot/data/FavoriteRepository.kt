package com.amapilot.data

import android.content.Context
import com.amapilot.map.SearchedPoi
import org.json.JSONArray
import org.json.JSONObject

/** 收藏地点（GCJ-02 坐标）；[kind] 区分普通收藏与常用地点（家/公司）。 */
data class FavoritePlace(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val kind: Kind = Kind.NORMAL
) {
    enum class Kind { NORMAL, HOME, WORK }
}

/**
 * 收藏与常用地点存储（SharedPreferences + JSON）。列表操作为 companion 纯函数，
 * 便于单元测试；数据供 Android Auto 车机端复用。
 */
class FavoriteRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取收藏列表（加入顺序）。 */
    fun load(): List<FavoritePlace> {
        val arr = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(arr)
            (0 until json.length()).mapNotNull { i ->
                val o = json.optJSONObject(i) ?: return@mapNotNull null
                FavoritePlace(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    address = o.optString("address"),
                    lat = o.optDouble("lat"),
                    lng = o.optDouble("lng"),
                    kind = o.optSerializableKind("kind")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 收藏一个地点（重复项幂等），kind 冲突时以现有为准。 */
    fun add(place: FavoritePlace) {
        save(upsert(load(), place))
    }

    /** 移除收藏。 */
    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** 清空。 */
    fun clear() = save(emptyList())

    private fun save(places: List<FavoritePlace>) {
        prefs.edit()
            .putString(KEY_FAVORITES, JSONArray(places.map(::toJson)).toString())
            .apply()
    }

    private fun toJson(p: FavoritePlace): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("address", p.address)
        .put("lat", p.lat)
        .put("lng", p.lng)
        .put("kind", p.kind.name)

    private fun JSONObject.optSerializableKind(key: String): FavoritePlace.Kind =
        runCatching { FavoritePlace.Kind.valueOf(optString(key, FavoritePlace.Kind.NORMAL.name)) }
            .getOrDefault(FavoritePlace.Kind.NORMAL)

    companion object {
        private const val PREFS_NAME = "amapilot_prefs"
        private const val KEY_FAVORITES = "favorites"

        const val MAX_FAVORITES = 50

        /** 收藏上限内追加/去重（按 id）；家/工作地 kind 唯一（后设覆盖先设）。 */
        fun upsert(existing: List<FavoritePlace>, place: FavoritePlace): List<FavoritePlace> {
            val deduped = existing.filterNot { it.id == place.id || it.kind == place.kind && place.kind != FavoritePlace.Kind.NORMAL }
            return (deduped + place).let { list ->
                if (list.size > MAX_FAVORITES) list.takeLast(MAX_FAVORITES) else list
            }
        }

        /** 搜索结果转收藏（无 id 时用坐标哈希）。 */
        fun fromPoi(poi: SearchedPoi): FavoritePlace = FavoritePlace(
            id = poi.id.ifBlank { "${poi.lat.toFixed4()}_${poi.lng.toFixed4()}" },
            name = poi.name,
            address = poi.address,
            lat = poi.lat,
            lng = poi.lng
        )

        private fun Double.toFixed4(): String = String.format("%.4f", this)
    }
}
