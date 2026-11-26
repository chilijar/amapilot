package com.amapilot.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.amapilot.map.PoiSearcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 车机搜索**首页**：宿主 SearchTemplate（顶置搜索栏 + 列表），默认态为历史地点行
 * 与「我的地点 ›」「附近 ›」入口行，查询切结果态，选点记历史后 pop 回地图。
 */
class CarSearchScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rows: List<SearchPanelRow> = emptyList()
    private var showingResults = false
    private var loading = false
    private var debounce: Job? = null

    private val searchCallback = object : SearchTemplate.SearchCallback {
        /** 输入过程：400ms 防抖联想检索（空词回默认首页态）。 */
        override fun onSearchTextChanged(text: String) {
            debounce?.cancel()
            if (text.isBlank()) {
                showingResults = false
                rows = emptyList()
                loading = false
                invalidate()
                return
            }
            showingResults = true
            loading = true
            invalidate()
            debounce = scope.launch {
                delay(400)
                runSearch(text)
            }
        }

        override fun onSearchSubmitted(text: String) {
            debounce?.cancel()
            scope.launch { runSearch(text) }
        }
    }

    init {
        // 手机中继：有输入即本屏切结果态（顶置搜索栏宿主渲染，中继仅喂词）
        scope.launch {
            CarSearchRelay.query.collect { q ->
                if (q.isNotBlank() && screenManager.top === this@CarSearchScreen) {
                    showingResults = true
                    loading = true
                    invalidate()
                    debounce?.cancel()
                    debounce = scope.launch { runSearch(q) }
                }
            }
        }
        scope.launch {
            CarSearchRelay.results.collect { res ->
                if (res.isNotEmpty() && screenManager.top === this@CarSearchScreen) {
                    rows = res.map { SearchPanelRow(it.name, it.address, it.lat, it.lng, it.id) }
                    loading = false
                    showingResults = true
                    invalidate()
                }
            }
        }
    }

    private suspend fun runSearch(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        loading = true
        invalidate()
        val res = runCatching {
            withTimeout(15_000) {
                val loc = (
                    com.amapilot.navigation.CruiseSession.location.value
                        ?: com.amapilot.navigation.NaviSession.location.value
                    )?.coord?.let {
                    com.amap.api.maps.model.LatLng(it.latitude, it.longitude)
                } ?: com.amapilot.map.SystemLocations.lastKnownGcj(carContext)
                val searcher = PoiSearcher(carContext)
                searcher.searchPoi(
                    q,
                    // 全城语义（v11 周边退化对策，与手机主搜索同源）
                    city = loc?.let { runCatching { searcher.regeoCity(it) }.getOrNull() },
                    location = loc
                )
            }
        }.getOrDefault(emptyList())
        Log.i(TAG, "car search '$q' -> ${res.size} results")
        rows = res.map { SearchPanelRow(it.name, it.address, it.lat, it.lng, it.id) }
        loading = false
        showingResults = true
        invalidate()
    }

    /** 选点提交：记地点历史 → 导航/途经点 → pop 回地图。 */
    private fun selectPlace(row: SearchPanelRow) {
        com.amapilot.data.HistoryStore(carContext).addPlace(
            row.title, row.subtitle, row.lat, row.lng, row.id
        )
        val wp = CarMapControl.waypointMode.value &&
            com.amapilot.navigation.NaviSession.isActive
        Log.i(TAG, "place selected: ${row.title} waypoint=$wp")
        CarMapControl.onSearchSelect?.invoke(row, wp)
        screenManager.pop()
    }

    /** 途经点模式开关行（导航中置顶；与卡内 📍 钮共享 [CarMapControl.waypointMode]）。 */
    private fun waypointToggleRow(): Row =
        Row.Builder()
            .setTitle(
                if (CarMapControl.waypointMode.value) "✓ 加为途经点" else "加为途经点"
            )
            .setBrowsable(true)
            .setOnClickListener {
                CarMapControl.toggleWaypoint()
                Log.i(TAG, "search mode -> waypoint=${CarMapControl.waypointMode.value}")
                invalidate()
            }
            .build()

    private fun placeRow(r: com.amapilot.data.HistoryStore.Rec): Row =
        Row.Builder()
            .setTitle(r.name)
            .addText(r.address.ifBlank { " " })
            .setBrowsable(true)
            .setOnClickListener {
                selectPlace(SearchPanelRow(r.name, r.address, r.lat, r.lng, r.id))
            }
            .build()

    private fun resultRow(r: SearchPanelRow): Row =
        Row.Builder()
            .setTitle(r.title)
            .addText(r.subtitle.ifBlank { " " })
            .setBrowsable(true)
            .setOnClickListener { selectPlace(r) }
            .build()

    override fun onGetTemplate(): Template = runCatching { buildTemplate() }
        .onFailure { Log.e(TAG, "onGetTemplate failed", it) }
        // 模板异常不得抛出（会崩溃进程 + 宿主错误屏），降级 loading 搜索屏
        .getOrElse { SearchTemplate.Builder(searchCallback).setLoading(true).build() }

    private fun buildTemplate(): Template {
        val list = ItemList.Builder()
        if (showingResults) {
            if (com.amapilot.navigation.NaviSession.isActive) {
                list.addItem(waypointToggleRow())
            }
            if (rows.isEmpty() && !loading) {
                list.setNoItemsMessage("无结果，换个词试试")
            }
            rows.take(MAX_ROWS).forEach { r -> list.addItem(resultRow(r)) }
        } else {
            val hist = com.amapilot.data.HistoryStore(carContext).load()
            if (hist.isEmpty()) {
                list.setNoItemsMessage("搜索目的地或从下方入口选择")
            }
            hist.take(MAX_ROWS).forEach { r -> list.addItem(placeRow(r)) }
            list.addItem(
                Row.Builder()
                    .setTitle("我的地点")
                    .addText("家 / 公司 / 收藏")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarPlacesScreen(carContext)) }
                    .build()
            )
            list.addItem(
                Row.Builder()
                    .setTitle("附近")
                    .addText("加油站 / 充电桩 / 停车场 / 洗手间")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarNearbyScreen(carContext)) }
                    .build()
            )
        }
        val b = SearchTemplate.Builder(searchCallback)
            .setHeaderAction(Action.BACK)
            .setSearchHint("搜索目的地")
            // 键盘不自动弹（手机中继为主输入；点搜索栏可手动唤起车机键盘）
            .setShowKeyboardByDefault(false)
            .setLoading(loading)
        if (!loading) {
            b.setItemList(list.build())
        }
        return b.build()
    }

    companion object {
        private const val TAG = "CarSearchScreen"
        private const val MAX_ROWS = 8
    }
}

/** 我的地点**二级半屏屏**：家 → 公司 → 普通收藏，选中直达算路（记地点历史）。 */
class CarPlacesScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { build() }
        .onFailure { Log.e("CarSearchScreen", "places onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun build(): Template {
        val list = ItemList.Builder()
        var count = 0
        fun row(f: com.amapilot.data.FavoritePlace, label: String) {
            count++
            list.addItem(
                Row.Builder()
                    .setTitle(label)
                    .addText(f.address.ifBlank { f.name })
                    .setBrowsable(true)
                    .setOnClickListener {
                        com.amapilot.data.HistoryStore(carContext).addPlace(
                            f.name, f.address, f.lat, f.lng, f.id
                        )
                        val wp = CarMapControl.waypointMode.value &&
                            com.amapilot.navigation.NaviSession.isActive
                        CarMapControl.onSearchSelect?.invoke(
                            SearchPanelRow(f.name, f.address, f.lat, f.lng, f.id), wp
                        )
                        screenManager.pop()
                        screenManager.pop()
                    }
                    .build()
            )
        }
        val favs = CarMapControl.favoritePlaces()
        favs.firstOrNull { it.kind == com.amapilot.data.FavoritePlace.Kind.HOME }
            ?.let { row(it, "家") }
        favs.firstOrNull { it.kind == com.amapilot.data.FavoritePlace.Kind.WORK }
            ?.let { row(it, "公司") }
        favs.filter { it.kind == com.amapilot.data.FavoritePlace.Kind.NORMAL }
            .take(MAX_ROWS)
            .forEach { row(it, it.name) }
        if (count == 0) {
            list.addItem(Row.Builder().setTitle("无收藏地点（手机 App 内添加）").build())
        }
        return ListTemplate.Builder()
            .setHeaderAction(Action.BACK)
            .setTitle("我的地点")
            .setSingleList(list.build())
            .build()
    }

    companion object {
        private const val MAX_ROWS = 8
    }
}

/** 附近**二级半屏屏**：分类行 → 本屏检索结果态，选中直达算路（记地点历史）。 */
class CarNearbyScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var category: String? = null
    private var rows: List<SearchPanelRow> = emptyList()
    private var loading = false

    override fun onGetTemplate(): Template = runCatching { build() }
        .onFailure { Log.e("CarSearchScreen", "nearby onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun build(): Template {
        val list = ItemList.Builder()
        if (category == null) {
            PoiSearcher.NEARBY_CATEGORIES.forEach { c ->
                list.addItem(
                    Row.Builder()
                        .setTitle(c)
                        .setBrowsable(true)
                        .setOnClickListener {
                            category = c
                            loading = true
                            invalidate()
                            scope.launch { runSearch(c) }
                        }
                        .build()
                )
            }
        } else {
            if (rows.isEmpty() && !loading) list.setNoItemsMessage("附近无结果")
            rows.take(MAX_ROWS).forEach { r ->
                list.addItem(
                    Row.Builder()
                        .setTitle(r.title)
                        .addText(r.subtitle.ifBlank { " " })
                        .setBrowsable(true)
                        .setOnClickListener {
                            com.amapilot.data.HistoryStore(carContext).addPlace(
                                r.title, r.subtitle, r.lat, r.lng, r.id
                            )
                            val wp = CarMapControl.waypointMode.value &&
                                com.amapilot.navigation.NaviSession.isActive
                            CarMapControl.onSearchSelect?.invoke(r, wp)
                            screenManager.pop()
                            screenManager.pop()
                        }
                        .build()
                )
            }
        }
        val b = ListTemplate.Builder()
            .setHeaderAction(Action.BACK)
            .setTitle(category ?: "附近")
        if (!loading || category == null) {
            b.setSingleList(list.build())
        } else {
            b.setLoading(true)
        }
        return b.build()
    }

    private suspend fun runSearch(q: String) {
        val res = runCatching {
            withTimeout(15_000) {
                PoiSearcher(carContext).searchPoi(
                    q,
                    location = (
                        com.amapilot.navigation.CruiseSession.location.value
                            ?: com.amapilot.navigation.NaviSession.location.value
                        )?.coord?.let {
                        com.amap.api.maps.model.LatLng(it.latitude, it.longitude)
                    }
                        // 引擎位置流冻死兜底（与手机沿途搜索/重算同源链）
                        ?: com.amapilot.map.SystemLocations.lastKnownGcj(carContext)
                )
            }
        }.getOrDefault(emptyList())
        Log.i("CarSearchScreen", "nearby '$q' -> ${res.size} results")
        rows = res.map { SearchPanelRow(it.name, it.address, it.lat, it.lng, it.id) }
        loading = false
        invalidate()
    }

    companion object {
        private const val MAX_ROWS = 8
    }
}
