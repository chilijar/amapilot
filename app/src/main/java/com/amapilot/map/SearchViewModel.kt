package com.amapilot.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.model.LatLng
import com.amapilot.data.HistoryStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 搜索页 UI 状态：[tips] 仅输入中，[results] 为提交结果，[error] 非空时提示。 */
data class SearchUiState(
    val query: String = "",
    val tips: List<com.amap.api.services.help.Tip> = emptyList(),
    val results: List<SearchedPoi> = emptyList(),
    val history: List<HistoryStore.Rec> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * 搜索页 ViewModel：输入防抖联想、提交搜索、历史管理。
 * @param locationProvider 距离排序/位置加权基准
 * @param centerProvider 周边探索中心：非空时联想、检索与排序基准从「我的位置」
 *   切到该点（页头显示「在 · XX 附近」）
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val searcher: PoiSearcher,
    private val historyStore: HistoryStore? = null,
    private val locationProvider: (() -> LatLng?)? = null,
    private val centerProvider: (() -> SearchedPoi?)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private val queryInput = MutableStateFlow("")
    private var tipsJob: Job? = null
    private var searchJob: Job? = null
    /** 逆地理城市缓存（主搜索城市限定用；位置移动 ~0.2° 内复用）。 */
    private var cachedCity: String? = null
    private var cachedCityAt: LatLng? = null

    /** 检索基准点：探索中心优先，回退当前位置。 */
    private fun effectiveLocation(): LatLng? =
        centerProvider?.invoke()?.let { LatLng(it.lat, it.lng) }
            ?: locationProvider?.invoke()

    /** 当前位置城市名（逆地理 + 缓存；失败回退 null = 不限城市）。 */
    private suspend fun cityFor(loc: LatLng?): String? {
        if (loc == null) return null
        val at = cachedCityAt
        val cached = cachedCity
        if (cached != null && at != null &&
            kotlin.math.abs(at.latitude - loc.latitude) < 0.2 &&
            kotlin.math.abs(at.longitude - loc.longitude) < 0.2
        ) {
            return cached
        }
        val city = runCatching { searcher.regeoCity(loc) }.getOrNull()
        if (!city.isNullOrBlank()) {
            cachedCity = city
            cachedCityAt = loc
        }
        return cachedCity
    }

    init {
        historyStore?.let { _uiState.value = _uiState.value.copy(history = it.load()) }
        viewModelScope.launch {
            queryInput
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { keyword -> requestTips(keyword) }
        }
    }

    /** 进入搜索页复位（周边探索入口）：清上一轮残留，可选预填关键词并立即检索。 */
    fun begin(keyword: String? = null) {
        tipsJob?.cancel()
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            query = keyword.orEmpty(),
            tips = emptyList(),
            results = emptyList(),
            error = null,
            loading = false
        )
        if (!keyword.isNullOrBlank()) search(keyword)
    }

    /** 输入变化：更新状态并触发防抖联想。改词须清空旧结果，否则非空 results
     *  会压住联想（showTips 要求 results 空）。 */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query, error = null, results = emptyList())
        if (query.isBlank()) {
            tipsJob?.cancel()
            _uiState.value = _uiState.value.copy(tips = emptyList())
        } else {
            queryInput.value = query
        }
    }

    /** 提交搜索：POI 检索并写入历史。 */
    fun search(keyword: String = _uiState.value.query) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        tipsJob?.cancel()
        _uiState.value = _uiState.value.copy(query = trimmed, loading = true, tips = emptyList(), error = null)
        searchJob = viewModelScope.launch {
            try {
                val loc = effectiveLocation()
                val results = searcher.searchPoi(trimmed, city = cityFor(loc), location = loc)
                _uiState.value = _uiState.value.copy(
                    results = results,
                    loading = false,
                    history = historyStore?.load().orEmpty()
                )
            } catch (e: Exception) {
                val code = (e as? SearchException)?.code
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = when (code) {
                        1008 -> "高德限流/配额拒绝（1008，已自动重试一次仍失败）：稍后再试"
                        else -> e.message
                    }
                )
            }
        }
    }

    /** 点击联想词直接作为关键词搜索。 */
    fun onTipClick(tip: com.amap.api.services.help.Tip) {
        search(tip.name)
    }

    fun clearHistory() {
        historyStore?.clear()
        _uiState.value = _uiState.value.copy(history = emptyList())
    }

    private suspend fun requestTips(keyword: String) {
        if (keyword.isBlank()) return
        // 单字不联想（减请求量；单字歧义大联想价值低）
        if (keyword.trim().length < 2) {
            if (_uiState.value.query == keyword) {
                _uiState.value = _uiState.value.copy(tips = emptyList())
            }
            return
        }
        try {
            val tips = searcher.inputTips(keyword, location = effectiveLocation())
                .filter { it.poiID != null }
            if (_uiState.value.query == keyword) {
                _uiState.value = _uiState.value.copy(tips = tips)
            }
        } catch (_: Exception) {
            // 联想失败静默，最终以提交搜索结果为准
        }
    }

    companion object {
        /** 联想防抖（500ms：配合 QPS 闸门压低输入期请求速率）。 */
        private const val DEBOUNCE_MS = 500L
    }
}
