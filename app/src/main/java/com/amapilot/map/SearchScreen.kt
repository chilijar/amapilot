package com.amapilot.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.services.help.Tip

/**
 * 搜索页：顶部输入框（自动聚焦），下方依次为联想 / 历史 / 结果列表。[onPoiSelected]
 * 选中结果（地图侧打点移视角；选点模式下由调用方存为家/公司）；[banner] 顶部提示条；
 * [resetOnEnter] 复位上一轮状态；[initialQuery] 预填关键词并自动检索。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPoiSelected: (SearchedPoi) -> Unit,
    onFavorite: (SearchedPoi) -> Unit = {},
    favorites: List<com.amapilot.data.FavoritePlace> = emptyList(),
    banner: String? = null,
    resetOnEnter: Boolean = false,
    initialQuery: String? = null,
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (resetOnEnter) viewModel.begin(initialQuery)
        else focusRequester.requestFocus()
    }

    // Surface 设置 LocalContentColor，夜间模式下标题等默认文字跟随主题（非黑色）
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("搜索地点") },
                singleLine = true,
                // 键盘回车（搜索动作）直接提交搜索，免收起键盘再点屏上「搜索」
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Text(
                text = "搜索",
                modifier = Modifier
                    .clickable { viewModel.search() }
                    .padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 选点模式提示条（设置家/公司）
        banner?.let { text ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (state.loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.error?.let {
            Text(
                text = "搜索失败：$it",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error
            )
        }

        val tips = state.tips
        val showTips = tips.isNotEmpty() && state.results.isEmpty()
        when {
            showTips -> LazyColumn(
                modifier = Modifier.navigationBarsPadding()
            ) {
                items(tips, key = { it.poiID + it.name }) { tip: Tip ->
                    val point = tip.point
                    // 有坐标的具体 POI 联想：可直选可收藏；无坐标（纯关键词类）仅可点搜
                    val asPoi = if (point != null && !tip.poiID.isNullOrBlank()) {
                        SearchedPoi(
                            id = tip.poiID,
                            name = tip.name,
                            address = tip.district + tip.address,
                            lat = point.latitude,
                            lng = point.longitude
                        )
                    } else {
                        null
                    }
                    TipRow(
                        name = tip.name,
                        sub = tip.district + tip.address,
                        onClick = {
                            if (asPoi != null) {
                                // 联想即具体 POI：直接选中（按名字重搜在 v11 周边偏置下会丢身份）
                                onPoiSelected(asPoi)
                            } else {
                                viewModel.onTipClick(tip)
                            }
                        },
                        onFavorite = asPoi?.let { p -> { onFavorite(p) } },
                        favorited = asPoi?.let { p -> favorites.any { f -> f.id == p.id } } ?: false
                    )
                }
            }
            state.results.isNotEmpty() -> LazyColumn(
                modifier = Modifier.navigationBarsPadding()
            ) {
                items(state.results, key = { it.id }) { poi ->
                    TipRow(
                        name = poi.name,
                        sub = poi.address,
                        onClick = { onPoiSelected(poi) },
                        onFavorite = { onFavorite(poi) },
                        favorited = favorites.any { it.id == poi.id }
                    )
                }
            }
            else -> LazyColumn(modifier = Modifier.navigationBarsPadding()) {
                // 已保存的地点（收藏）置于搜索历史上方
                if (favorites.isNotEmpty()) {
                    item {
                        Text(
                            "已保存的地点",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(favorites, key = { "fav_${it.id}" }) { fav ->
                        FavoriteRow(fav) {
                            onPoiSelected(
                                SearchedPoi(
                                    id = fav.id,
                                    name = fav.name,
                                    address = fav.address,
                                    lat = fav.lat,
                                    lng = fav.lng
                                )
                            )
                        }
                    }
                }
                if (state.history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("历史", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "清空",
                                modifier = Modifier.clickable { viewModel.clearHistory() },
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    items(state.history, key = { "place_${it.ts}" }) { rec ->
                        // 收藏 id 与 FavoriteRepository.fromPoi 对齐：POI id 优先，空则坐标回退
                        val fid = rec.id.ifBlank {
                            String.format("%.4f_%.4f", rec.lat, rec.lng)
                        }
                        val hPoi = SearchedPoi(
                            id = fid,
                            name = rec.name,
                            address = rec.address,
                            lat = rec.lat,
                            lng = rec.lng
                        )
                        TipRow(
                            name = rec.name,
                            sub = rec.address,
                            onClick = { onPoiSelected(hPoi) },
                            onFavorite = { onFavorite(hPoi) },
                            favorited = favorites.any { f -> f.id == hPoi.id },
                            icon = com.amapilot.ui.theme.GmIcons.History
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TipRow(
    name: String,
    sub: String,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    favorited: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.LocationOn
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
        if (onFavorite != null) {
            androidx.compose.material3.IconButton(onClick = onFavorite) {
                Icon(
                    if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (favorited) "取消收藏" else "收藏",
                    modifier = Modifier.size(18.dp),
                    tint = if (favorited) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 已保存地点行：家/公司图标 + 名称 + 地址，点击作为目的地。 */
@Composable
private fun FavoriteRow(fav: com.amapilot.data.FavoritePlace, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (fav.kind) {
                com.amapilot.data.FavoritePlace.Kind.HOME -> Icons.Default.Home
                com.amapilot.data.FavoritePlace.Kind.WORK ->
                    com.amapilot.ui.theme.GmIcons.Work
                com.amapilot.data.FavoritePlace.Kind.NORMAL -> Icons.Default.Favorite
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(fav.name, style = MaterialTheme.typography.bodyLarge)
            if (fav.address.isNotBlank()) {
                Text(
                    fav.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
