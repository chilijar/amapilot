package com.amapilot.map

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** POI 搜索结果（GCJ-02 坐标）。 */
data class SearchedPoi(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double
)

/** 搜索失败（高德错误码）。 */
class SearchException(val code: Int, message: String) : Exception(message)

/**
 * POI 搜索封装：关键词联想、周边搜索与沿途搜索。基于 [Inputtips] 与 [PoiSearch]，
 * 回调式 API 经 [suspendCancellableCoroutine] 转挂起函数；须在隐私同意后使用。
 */
class PoiSearcher(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 关键词联想（输入过程实时调用）。
     * @param city 城市名，null = 全国；[location] 传入后附近结果优先
     */
    suspend fun inputTips(
        keyword: String,
        city: String? = null,
        location: LatLng? = null
    ): List<Tip> = withQpsRetry { inputTipsOnce(keyword, city, location) }

    private suspend fun inputTipsOnce(
        keyword: String,
        city: String?,
        location: LatLng?
    ): List<Tip> {
        acquireQps()
        return suspendCancellableCoroutine { cont ->
            val query = InputtipsQuery(keyword, city).apply {
                setCityLimit(city != null)
                location?.let { setLocation(LatLonPoint(it.latitude, it.longitude)) }
            }
            val tips = Inputtips(appContext, query)
            tips.setInputtipsListener { list, code ->
                if (cont.isActive) {
                    if (code == CODE_OK) {
                        cont.resume(list.orEmpty())
                    } else {
                        cont.resumeWith(
                            Result.failure(SearchException(code, "inputtips failed: $code"))
                        )
                    }
                }
            }
            tips.requestInputtipsAsyn()
        }
    }

    /**
     * 逆地理编码取当前城市名（主搜索城市限定用）。
     * v11 带 location+距离排序的关键字搜索会退化为**周边搜索**（全称只回附近同名
     * 小区），主搜索须 city+cityLimit 恢复全城语义。
     * @return 城市名；失败返回 null（调用方回退不限城市）
     */
    suspend fun regeoCity(location: LatLng): String? =
        withQpsRetry { regeoCityOnce(location) }

    private suspend fun regeoCityOnce(location: LatLng): String? {
        acquireQps()
        return suspendCancellableCoroutine { cont ->
            val gs = com.amap.api.services.geocoder.GeocodeSearch(appContext)
            gs.setOnGeocodeSearchListener(object :
                com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(
                    result: com.amap.api.services.geocoder.RegeocodeResult?,
                    code: Int
                ) {
                    if (cont.isActive) {
                        cont.resume(result?.regeocodeAddress?.city?.takeIf { it.isNotBlank() })
                    }
                }

                override fun onGeocodeSearched(
                    result: com.amap.api.services.geocoder.GeocodeResult?,
                    code: Int
                ) = Unit
            })
            gs.getFromLocationAsyn(
                com.amap.api.services.geocoder.RegeocodeQuery(
                    LatLonPoint(location.latitude, location.longitude),
                    200f,
                    com.amap.api.services.geocoder.GeocodeSearch.AMAP
                )
            )
        }
    }

    /**
     * 逆地理**选点**（长按地图 → 地点）：最近 POI 名称 + 格式化地址，供地点卡/
     * 路线/收藏消费；失败返回 null（调用方以坐标串降级命名）。
     */
    suspend fun regeoPoint(location: LatLng): SearchedPoi? =
        withQpsRetry { regeoPointOnce(location) }

    private suspend fun regeoPointOnce(location: LatLng): SearchedPoi? {
        acquireQps()
        return suspendCancellableCoroutine { cont ->
            val gs = com.amap.api.services.geocoder.GeocodeSearch(appContext)
            gs.setOnGeocodeSearchListener(object :
                com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(
                    result: com.amap.api.services.geocoder.RegeocodeResult?,
                    code: Int
                ) {
                    if (!cont.isActive) return
                    val addr = result?.regeocodeAddress
                    // 名称：最近 POI 标题优先，缺省回退格式化地址；两者皆空视为失败
                    val nearest = addr?.pois?.firstOrNull()
                    val name = nearest?.title?.takeIf { it.isNotBlank() }
                        ?: addr?.formatAddress?.takeIf { it.isNotBlank() }
                    if (addr == null || name == null) {
                        cont.resume(null)
                        return
                    }
                    cont.resume(
                        SearchedPoi(
                            id = nearest?.poiId.orEmpty(),
                            name = name,
                            address = addr.formatAddress.orEmpty(),
                            lat = location.latitude,
                            lng = location.longitude
                        )
                    )
                }

                override fun onGeocodeSearched(
                    result: com.amap.api.services.geocoder.GeocodeResult?,
                    code: Int
                ) = Unit
            })
            gs.getFromLocationAsyn(
                com.amap.api.services.geocoder.RegeocodeQuery(
                    LatLonPoint(location.latitude, location.longitude),
                    200f,
                    com.amap.api.services.geocoder.GeocodeSearch.AMAP
                )
            )
        }
    }

    /**
     * 关键词 POI 搜索（回车触发），结果按距离由近到远排序。
     * @param location 距离排序基准；[page] 从 0 开始
     */
    suspend fun searchPoi(
        keyword: String,
        city: String? = null,
        location: LatLng? = null,
        page: Int = 0
    ): List<SearchedPoi> = withQpsRetry { searchPoiOnce(keyword, city, location, page) }

    private suspend fun searchPoiOnce(
        keyword: String,
        city: String?,
        location: LatLng?,
        page: Int
    ): List<SearchedPoi> {
        acquireQps()
        return suspendCancellableCoroutine { cont ->
            val query = PoiSearch.Query(keyword, "", city).apply {
                pageSize = 20
                pageNum = page
                // 城市限定：防跨城漂移（v11 周边退化下靠 city 恢复全城语义）
                if (city != null) setCityLimit(true)
                location?.let {
                    setLocation(LatLonPoint(it.latitude, it.longitude))
                    isDistanceSort = true
                }
            }
            val searcher = PoiSearch(appContext, query)
            searcher.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    if (cont.isActive) {
                        if (code == CODE_OK && result?.query != null) {
                            cont.resume(result.pois.orEmpty().map(::toSearchedPoi))
                        } else {
                            cont.resumeWith(
                                Result.failure(SearchException(code, "poi search failed: $code"))
                            )
                        }
                    }
                }

                override fun onPoiItemSearched(item: PoiItem?, code: Int) = Unit
            })
            searcher.searchPOIAsyn()
        }
    }

    private fun toSearchedPoi(item: PoiItem): SearchedPoi = SearchedPoi(
        id = item.poiId.orEmpty(),
        name = item.title.orEmpty(),
        address = fullAddress(
            item.provinceName.orEmpty(),
            item.cityName.orEmpty(),
            item.adName.orEmpty(),
            item.snippet.orEmpty()
        ),
        lat = item.latLonPoint.latitude,
        lng = item.latLonPoint.longitude
    )

    companion object {
        /** 周边搜索分类（车机「附近」屏与手机地点卡共用；手机另加「餐厅」）。 */
        val NEARBY_CATEGORIES: List<String> = listOf("加油站", "充电桩", "停车场", "洗手间")

        /** 高德搜索 SDK 成功码。 */
        const val CODE_OK = 1000

        /** 客户端 QPS 闸门：350ms 间隔 ≈2.8 QPS（上限 3），超限即 1008。 */
        private const val MIN_INTERVAL_MS = 350L
        private val gateNext = java.util.concurrent.atomic.AtomicLong(0L)

        /** 排队取发请求槽位：任意两次搜索类请求发起间隔 ≥ [MIN_INTERVAL_MS]。 */
        private suspend fun acquireQps() {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val cur = gateNext.get()
                val slot = maxOf(now, cur)
                if (gateNext.compareAndSet(cur, slot + MIN_INTERVAL_MS)) {
                    val wait = slot - now
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                    return
                }
            }
        }

        /** 1008（QPS/配额拒绝）退避 1s 重试一次；其余错误原样抛出。 */
        private suspend fun <T> withQpsRetry(block: suspend () -> T): T = try {
            block()
        } catch (e: SearchException) {
            if (e.code == 1008) {
                kotlinx.coroutines.delay(1_000)
                block()
            } else {
                throw e
            }
        }

        /** 完整地址：省 + 市 + 区 + 详细地址（去重：直辖市省市同名、snippet 已带前缀）。 */
        fun fullAddress(province: String, city: String, adName: String, snippet: String): String {
            val sb = StringBuilder()
            for (part in listOf(province, city, adName)) {
                if (part.isBlank()) continue
                if (part.length <= sb.length && sb.endsWith(part)) continue
                sb.append(part)
            }
            val prefix = sb.toString()
            return when {
                snippet.isBlank() -> prefix
                snippet.startsWith(prefix) -> snippet
                adName.isNotBlank() && snippet.startsWith(adName) && prefix.endsWith(adName) ->
                    prefix.dropLast(adName.length) + snippet
                else -> prefix + snippet
            }
        }
    }
}
