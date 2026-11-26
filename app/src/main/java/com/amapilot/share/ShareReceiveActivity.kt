package com.amapilot.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amapilot.R
import com.amapilot.ui.theme.AmapilotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 接收第三方应用分享的位置（含车机连接态）。支持 geo:/androidamap:/baidumap: 与
 * text/plain（裸坐标 / 高德直链 / surl 短链，见 [AmapShareResolver]）。解析成功后三选：
 * 设为目的地 / 加为途经点（导航中）/ 不设置导航。
 */
class ShareReceiveActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 隐私合规重注入（进程内静态；分享直达时可能全进程首次碰 SDK）
        val agreed = getSharedPreferences("amapilot_prefs", Context.MODE_PRIVATE)
            .getBoolean("privacy_agreed", false)
        if (agreed) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
        val raw = rawShareText()
        android.util.Log.i(
            "ShareReceive",
            "intent action=${intent.action} type=${intent.type} data=${intent.data} text=$raw"
        )
        setContent {
            AmapilotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { CircularProgressIndicator() }
                }
            }
        }
        scope.launch {
            // 隐私门槛：未同意前不得碰任何 SDK（否则抛隐私异常杀进程），先弹窗再继续
            val prefs = getSharedPreferences("amapilot_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("privacy_agreed", false)) {
                setContent {
                    AmapilotTheme {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            com.amapilot.ui.components.PrivacyPolicyDialog(
                                onAgree = {
                                    prefs.edit().putBoolean("privacy_agreed", true).apply()
                                    com.amapilot.settings.AmapPrivacy.assert(this@ShareReceiveActivity)
                                    scope.launch { proceed() }
                                },
                                onDisagree = { finish() }
                            )
                        }
                    }
                }
                return@launch
            }
            com.amapilot.settings.AmapPrivacy.assert(this@ShareReceiveActivity)
            proceed()
        }
    }

    /** 隐私已同意后的正常分享解析流程。 */
    private fun proceed() {
        val raw = rawShareText()
        scope.launch {
            val target = withContext(Dispatchers.IO) { resolveShare(raw) }
            if (target == null) {
                android.util.Log.w("ShareReceive", "unsupported share raw=$raw")
            }
            showResult(target, raw)
        }
    }

    /** 分享解析结果：坐标 + POI 名 + 地址（地理编码兜底时带地址）。 */
    private data class ShareTarget(
        val point: GeoPoint,
        val name: String? = null,
        val address: String? = null,
    )

    /** 解析顺序：scheme 直连 → 地图链接（逐跳/展开/落地页）→ 文本裸坐标 →
     *  纯地址文本地理编码首条（微信地址卡无坐标形态）。 */
    private suspend fun resolveShare(raw: String): ShareTarget? {
        parsePoint(intent)?.let { p ->
            return ShareTarget(
                p, AmapShareResolver.nameFromUrl(intent.data?.toString() ?: "")
            )
        }
        AmapShareResolver.resolveNamed(raw)?.let { return ShareTarget(it.point, it.name) }
        parseCoordsFromText(raw)?.let { return ShareTarget(it) }
        return geocode(raw)
    }

    /** 纯地址文本地理编码：去链接/符号后作关键词，POI 检索首条。 */
    private suspend fun geocode(raw: String): ShareTarget? {
        // 未同意隐私政策不得使用 SDK 检索
        if (!getSharedPreferences("amapilot_prefs", Context.MODE_PRIVATE)
                .getBoolean("privacy_agreed", false)
        ) {
            return null
        }
        val keyword = raw
            .replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("[【】\n\r]"), " ")
            .trim()
        if (keyword.length < 4) return null
        val poi = runCatching {
            com.amapilot.map.PoiSearcher(applicationContext).searchPoi(keyword).firstOrNull()
        }.getOrNull() ?: return null
        android.util.Log.i("ShareReceive", "geocode '$keyword' -> ${poi.name}")
        return ShareTarget(GeoPoint(poi.lat, poi.lng), poi.name, poi.address)
    }

    /** 分享载荷文本：EXTRA_TEXT → EXTRA_SUBJECT → data 链接。 */
    private fun rawShareText(): String =
        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.data?.toString()
            ?: ""

    /** 三选对话框 / 不支持诊断页。 */
    private fun showResult(target: ShareTarget?, raw: String) {
        setContent {
            AmapilotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (target == null) {
                            Text(getString(R.string.share_unsupported))
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            TextButton(onClick = { finish() }) { Text("关闭") }
                            return@Column
                        }
                        val point = target.point
                        val latLng = com.amap.api.maps.model.LatLng(point.lat, point.lng)
                        Text(target.name ?: "分享位置", style = MaterialTheme.typography.titleLarge)
                        if (!target.address.isNullOrBlank()) {
                            Text(
                                target.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Text(
                            "%.6f, %.6f".format(point.lat, point.lng),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        // StateFlow 须 collectAsState 订阅（直读 .value 不触发重组）
                        val carConnected =
                            com.amapilot.auto.CarMapControl.carConnected.collectAsState()
                        if (carConnected.value) {
                            Text(
                                "已连接车机：将在车机屏发起/变更导航",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.padding(top = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(onClick = {
                                recordHistory(target.name, latLng)
                                com.amapilot.auto.CarRouteStarter.start(
                                    applicationContext, latLng
                                )
                                finish()
                            }) { Text("设为目的地") }
                            val naviActive = com.amapilot.navigation.NaviSession.isActive
                            Button(
                                enabled = naviActive,
                                onClick = {
                                    recordHistory(target.name, latLng)
                                    com.amapilot.navigation.NaviSession.addWaypoint(
                                        target.name ?: "途经点", latLng
                                    )
                                    finish()
                                }
                            ) { Text("加为途经点") }
                            if (!naviActive) {
                                Text(
                                    "开始导航后可加为途经点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            TextButton(onClick = {
                                // 不设置导航也入地点历史（分享选点即记录）
                                recordHistory(target.name, latLng)
                                finish()
                            }) { Text("不设置导航") }
                        }
                    }
                }
            }
        }
    }

    /** 选点入共享地点历史（车机搜索首页历史行同源）。 */
    private fun recordHistory(name: String?, latLng: com.amap.api.maps.model.LatLng) {
        runCatching {
            com.amapilot.data.HistoryStore(applicationContext).addPlace(
                name = name ?: "分享位置",
                address = "",
                lat = latLng.latitude,
                lng = latLng.longitude,
            )
        }.onFailure {
            android.util.Log.w("ShareReceive", "record history failed", it)
        }
    }

    private fun parsePoint(intent: Intent): GeoPoint? {
        intent.data?.let { data ->
            when (data.scheme) {
                "geo" -> return parseGeoUri(data)
                "androidamap" -> {
                    val lat = data.getQueryParameter("lat")?.toDoubleOrNull()
                    val lng = data.getQueryParameter("lon")?.toDoubleOrNull()
                    if (lat != null && lng != null) return GeoPoint(lat, lng)
                }
                "baidumap" -> {
                    val location = data.getQueryParameter("location") ?: return null
                    val lat = location.split(",").getOrNull(0)?.toDoubleOrNull()
                    val lng = location.split(",").getOrNull(1)?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        return CoordConverter.bd09ToGcj02(lat, lng)
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun parseGeoUri(uri: Uri): GeoPoint? {
        val parts = uri.schemeSpecificPart.substringBefore('?').split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.toDoubleOrNull()
        return if (lat != null && lng != null) GeoPoint(lat, lng) else null
    }
}
