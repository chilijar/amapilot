package com.amapilot.data

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amapilot.map.PoiSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入备份后的收藏有效性核查（唯一打扰点）：以收藏坐标为圆心按名称周边回搜，
 * 近距命中同名 POI 即有效。搜索失败（无 key/无网/配额）= 未知，**不报**——
 * 只有搜索成功且附近确无同名 POI 才列为失效。
 */
object FavoriteValidator {

    /** 单次核查条数上限（搜索 QPS 闸门 ~350ms/条，控制耗时与用户配额）。 */
    private const val MAX_CHECK = 20

    /** 同名 POI 距原坐标多近算"仍在原处"（米）。 */
    private const val NEARBY_METERS = 1000.0

    /** @return 可能已失效的收藏名称；无法核查（失败/无 key）返回空 = 不打扰。 */
    suspend fun findInvalidNames(
        context: Context,
        places: List<FavoritePlace>,
    ): List<String> = withContext(Dispatchers.IO) {
        val searcher = PoiSearcher(context)
        val invalid = mutableListOf<String>()
        for (p in places.take(MAX_CHECK)) {
            val results = runCatching {
                searcher.searchPoi(p.name, city = null, location = LatLng(p.lat, p.lng))
            }.getOrNull() ?: continue // 搜索失败 = 未知，不报
            val hit = results.any { r ->
                nameMatches(p.name, r.name) &&
                    distanceMeters(p.lat, p.lng, r.lat, r.lng) <= NEARBY_METERS
            }
            if (!hit) invalid += p.name
        }
        invalid
    }

    /** 名称匹配：去空格后相等或互相包含（收藏存全称、回搜可能回简称/分店名）。 */
    internal fun nameMatches(saved: String, found: String): Boolean {
        val a = saved.replace(" ", "")
        val b = found.replace(" ", "")
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** 等距圆柱近似距离（米）；核查阈值千米级，近似误差可忽略。 */
    internal fun distanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val mLat = (lat2 - lat1) * 111_320.0
        val mLng = (lng2 - lng1) * 111_320.0 *
            kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.hypot(mLat, mLng)
    }
}
