package com.amapilot.auto

import android.content.Context
import com.amapilot.map.PoiSearcher
import com.amapilot.map.SearchedPoi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 车机搜索中继：手机输入文字 → 车机展示结果并选择（车机键盘不可用时手机仅提供输入）。
 */
object CarSearchRelay {

    /** 最近一次提交的查询词。 */
    val query = MutableStateFlow("")

    val results = MutableStateFlow<List<SearchedPoi>>(emptyList())

    val searching = MutableStateFlow(false)

    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 手机端提交查询 → 后台检索（距离排序，基准取巡航/导航当前位置）。 */
    fun submit(context: Context, text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query.value = q
        searching.value = true
        error.value = null
        scope.launch {
            val located = runCatching {
                kotlinx.coroutines.withTimeout(15_000) {
                    val loc = (com.amapilot.navigation.CruiseSession.location.value
                        ?: com.amapilot.navigation.NaviSession.location.value)?.coord?.let {
                        com.amap.api.maps.model.LatLng(it.latitude, it.longitude)
                    }
                        // 引擎位置流冻死兜底（与手机沿途搜索/重算同源链）
                        ?: com.amapilot.map.SystemLocations.lastKnownGcj(context)
                    val searcher = PoiSearcher(context)
                    searcher.searchPoi(
                        q,
                        // 全城语义（v11 周边退化对策，与手机主搜索同源）
                        city = loc?.let { runCatching { searcher.regeoCity(it) }.getOrNull() },
                        location = loc
                    )
                }
            }
            searching.value = false
            located.fold(
                onSuccess = {
                    android.util.Log.i("CarSearchRelay", "search '$q' -> ${it.size} results")
                    results.value = it
                },
                onFailure = {
                    android.util.Log.e("CarSearchRelay", "search '$q' failed", it)
                    error.value = "搜索失败，请重试"; results.value = emptyList()
                }
            )
        }
    }

    /** 清空（进入搜索页/退出会话时）。 */
    fun reset() {
        query.value = ""
        results.value = emptyList()
        searching.value = false
        error.value = null
    }
}
