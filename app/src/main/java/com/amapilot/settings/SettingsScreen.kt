package com.amapilot.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amapilot.navigation.DriveStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用设置：导航偏好（默认策略）、语音播报、昼夜模式；持久化于 [Prefs]。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    dayNight: String = Prefs.DAY_NIGHT_AUTO,
    onDayNightChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var voiceEnabled by remember { mutableStateOf(Prefs.isVoiceEnabled(context)) }
    var voiceCamera by remember { mutableStateOf(Prefs.isVoiceCameraEnabled(context)) }
    var voiceSpeedLimit by remember { mutableStateOf(Prefs.isVoiceSpeedLimitEnabled(context)) }
    var voiceDetail by remember { mutableStateOf(Prefs.voiceDetail(context)) }
    var autoCruise by remember { mutableStateOf(Prefs.isAutoCruiseEnabled(context)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingCard(title = "导航偏好") {
                // 路线偏好独立开关（与车机设置半屏同源；互斥联动靠 prefVer 重读）。
                // 必须 Column：Box 会把多行开关叠放
                var prefVer by remember { mutableStateOf(0) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.runtime.key(prefVer) {
                        listOf(
                            "避免拥堵" to (Prefs::isRouteAvoidCongestion to Prefs::setRouteAvoidCongestion),
                            "避免收费" to (Prefs::isRouteAvoidToll to Prefs::setRouteAvoidToll),
                            "避免高速" to (Prefs::isRouteAvoidHighway to Prefs::setRouteAvoidHighway),
                            "高速优先" to (Prefs::isRoutePreferHighway to Prefs::setRoutePreferHighway),
                        ).forEach { (label, pair) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
                                Switch(
                                    checked = pair.first(context),
                                    onCheckedChange = {
                                        pair.second(context, it)
                                        prefVer++
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("驾驶时显示巡航卡片", modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Switch(
                        checked = autoCruise,
                        onCheckedChange = {
                            autoCruise = it
                            Prefs.setAutoCruiseEnabled(context, it)
                        }
                    )
                }
            }
            SettingCard(title = "语音播报") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("导航语音播报", modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Switch(
                        checked = voiceEnabled,
                        onCheckedChange = {
                            voiceEnabled = it
                            Prefs.setVoiceEnabled(context, it)
                        }
                    )
                }
                if (voiceEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "电子眼/违章拍照",
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = voiceCamera,
                            onCheckedChange = {
                                voiceCamera = it
                                Prefs.setVoiceCameraEnabled(context, it)
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "限速提醒",
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = voiceSpeedLimit,
                            onCheckedChange = {
                                voiceSpeedLimit = it
                                Prefs.setVoiceSpeedLimitEnabled(context, it)
                            }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                voiceDetail = if (voiceDetail == Prefs.VOICE_DETAIL_CONCISE) {
                                    Prefs.VOICE_DETAIL_DETAILED
                                } else {
                                    Prefs.VOICE_DETAIL_CONCISE
                                }
                                Prefs.setVoiceDetail(context, voiceDetail)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "路线引导细节",
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(voiceDetail, fontSize = 15.sp)
                    }
                }
            }
            SettingCard(title = "昼夜模式") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = when (dayNight) {
                                Prefs.DAY_NIGHT_AUTO -> Prefs.DAY_NIGHT_DAY
                                Prefs.DAY_NIGHT_DAY -> Prefs.DAY_NIGHT_NIGHT
                                else -> Prefs.DAY_NIGHT_AUTO
                            }
                            Prefs.setDayNightMode(context, next)
                            // 车连时底图由车机 Presentation 持有，须全局重应用
                            com.amapilot.auto.CarMapControl.dayNightTick.value++
                            onDayNightChange(next)
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("模式", modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Text(
                        dayNight,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                }
            }
            SettingCard(title = "高德 Key") {
                var showKeyDialog by remember { mutableStateOf(false) }
                var customKey by remember { mutableStateOf(Prefs.amapApiKey(context)) }
                val sha1 = remember { AmapKey.sha1Hex(context) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (AmapKey.copySha1(context)) {
                                android.widget.Toast.makeText(
                                    context, "SHA1 已复制", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "签名 SHA1",
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp
                        )
                        Text(
                            "点击复制",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                    // 指纹 59 字符：整行等宽展示，塞 Row 右侧会被压缩截断
                    Text(
                        sha1 ?: "未知",
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showKeyDialog = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Key", modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Text(
                        if (customKey.isBlank()) "内置" else "自定义",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                }
                Text(
                    // Release 零内置 key（用户自持），仅本地构建有内置值
                    if (AmapKey.bundled(context).isBlank()) {
                        "本安装包未内置高德 key（Release 版如此）：① 点击复制上方 SHA1；" +
                            "② 到高德开放平台 lbs.amap.com 创建 Android 平台 key（包名 " +
                            "com.amapilot + 粘贴该 SHA1）；③ 将 key 粘贴到「Key」保存，" +
                            "重启 app 生效。未配置时地图/搜索不可用"
                    } else {
                        "用法：① 点击复制上方 SHA1；② 到高德开放平台 lbs.amap.com 创建" +
                            " Android 平台 key（包名 com.amapilot + 粘贴该 SHA1）；③ 将 key" +
                            " 粘贴到「Key」保存，重启 app 生效。未配置时使用内置 key"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 启动鉴权探针结果（错 key/配额显式可见）
                when (val lp = AmapKey.lastProbe) {
                    is AmapKey.ProbeResult.KeyInvalid -> Text(
                        "上次校验：失败（code=${lp.code}，key/签名 SHA1/平台不匹配）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    AmapKey.ProbeResult.Quota -> Text(
                        "上次校验：配额超限（code=1004，配置自己的 key 可恢复）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    AmapKey.ProbeResult.Ok -> Text(
                        "上次校验：通过",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    AmapKey.ProbeResult.Unknown -> Unit
                }
                if (showKeyDialog) {
                    var keyText by remember { mutableStateOf(customKey) }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showKeyDialog = false },
                        title = { Text("高德 Key") },
                        text = {
                            androidx.compose.material3.OutlinedTextField(
                                value = keyText,
                                onValueChange = { keyText = it },
                                label = { Text("粘贴 key，留空恢复内置") }
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                if (keyText.isNotBlank() && !AmapKey.isValidFormat(keyText)) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "key 格式无效：应为 32 位十六进制字符串",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@TextButton
                                }
                                Prefs.setAmapApiKey(context, keyText)
                                customKey = keyText.trim()
                                AmapKey.apply(context)
                                showKeyDialog = false
                                android.widget.Toast.makeText(
                                    context, "已保存，重启 app 后完全生效",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }) { Text("保存") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showKeyDialog = false }
                            ) { Text("取消") }
                        }
                    )
                }
            }
            SettingCard(title = "配置备份") { BackupSection() }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

/**
 * 配置备份卡：导出/导入 设置、key、收藏与地点历史（[ConfigBackup]）。`.amapilot` 为
 * 文本文件，内容 AES-256-GCM 加密、无需口令；GCM 认证保证被改/损坏时显式报错。
 * 威胁模型 = 混淆级（防明文浏览，不防逆向 APK）。导入为合并语义，key 变更需重启生效。
 * 导入静默直落（无确认弹窗/无明细数字）：仅收藏有效性核查
 * （[com.amapilot.data.FavoriteValidator]）查出失效项才弹窗，全有效则零打扰。
 */
@Composable
private fun BackupSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ConfigBackup.exportText(context)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
            }
            result.onSuccess { toast("已导出 .${ConfigBackup.FILE_EXT} 备份") }
                .onFailure {
                    android.util.Log.w("ConfigBackup", "export failed", it)
                    toast("导出失败")
                }
        }
    }
    // 导入静默直落（不弹覆盖确认、不报细节）；仅收藏核查查出失效项才由 MainActivity 弹窗
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        ConfigBackup.readLimited(it)
                    } ?: error("无法读取文件")
                    ConfigBackup.importText(context, text)
                }
            }
            result.onSuccess {
                // 用户侧只报结果，损坏/跳过项等细节进日志
                android.util.Log.i(
                    "ConfigBackup",
                    "imported: prefs=${it.prefs} fav=${it.favorites} his=${it.history}" +
                        " skipped=${it.skipped}"
                )
                toast("已导入，重启 app 生效")
            }.onFailure {
                android.util.Log.w("ConfigBackup", "import failed", it)
                toast("导入失败")
            }
        }
    }

    Text(
        "导出/导入 设置项、高德 Key、收藏与地点历史，保存为 ." +
            ConfigBackup.FILE_EXT + " 文本文件：内容加密（AES-256-GCM），无法明文" +
            "读出。备份文件含敏感信息，请勿公开发布",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val name = "amapilot-config-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date()) + "." + ConfigBackup.FILE_EXT
            createDoc.launch(name)
        }) { Text("导出") }
        OutlinedButton(onClick = {
            // 不限 MIME：.amapilot 扩展名部分管理器报 octet-stream；格式校验在内容侧
            openDoc.launch(arrayOf("*/*"))
        }) { Text("导入") }
    }
}

/** 设置持久化。 */
object Prefs {
    private const val NAME = "amapilot_prefs"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_VOICE_CAMERA = "voice_camera_enabled"
    private const val KEY_VOICE_SPEED_LIMIT = "voice_speed_limit_enabled"
    private const val KEY_VOICE_DETAIL = "voice_detail"
    private const val KEY_AMAP_API_KEY = "amap_api_key_override"
    private const val KEY_KEY_SETUP_DISMISSED = "key_setup_dismissed"
    private const val KEY_DAY_NIGHT = "day_night_mode"
    private const val KEY_STRATEGY = "default_strategy"
    private const val KEY_AUTO_CRUISE = "auto_cruise_enabled"

    val DAY_NIGHT_OPTIONS = listOf(DAY_NIGHT_AUTO, DAY_NIGHT_DAY, DAY_NIGHT_NIGHT) // 循环顺序
    const val DAY_NIGHT_AUTO = "自动"
    const val DAY_NIGHT_DAY = "白天"
    const val DAY_NIGHT_NIGHT = "夜间"

    fun isVoiceEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    /** 电子眼/违章拍照播报开关。 */
    fun isVoiceCameraEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_CAMERA, true)

    fun setVoiceCameraEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE_CAMERA, enabled).apply()
    }

    /** 限速提醒播报开关。 */
    fun isVoiceSpeedLimitEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_SPEED_LIMIT, true)

    fun setVoiceSpeedLimitEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE_SPEED_LIMIT, enabled).apply()
    }

    /** 路线引导细节档位：简洁（合成两类播报）/ 详细（引擎全量）。 */
    const val VOICE_DETAIL_CONCISE = "简洁"
    const val VOICE_DETAIL_DETAILED = "详细"

    fun voiceDetail(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_DETAIL, VOICE_DETAIL_DETAILED) ?: VOICE_DETAIL_DETAILED

    fun setVoiceDetail(context: Context, detail: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOICE_DETAIL, detail).apply()
    }

    fun isVoiceConcise(context: Context): Boolean =
        voiceDetail(context) == VOICE_DETAIL_CONCISE

    /** 用户自持高德 key 覆盖（空 = 用内置 key）。 */
    fun amapApiKey(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_AMAP_API_KEY, "") ?: ""

    fun setAmapApiKey(context: Context, key: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AMAP_API_KEY, key.trim()).apply()
    }

    /** 首启 key 引导已跳过标记（跳过一次后不再强推，仍可从设置页配置）。 */
    fun keySetupDismissed(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEY_SETUP_DISMISSED, false)

    fun setKeySetupDismissed(context: Context, dismissed: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEY_SETUP_DISMISSED, dismissed).apply()
    }

    fun dayNightMode(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_DAY_NIGHT, "自动") ?: "自动"

    fun setDayNightMode(context: Context, mode: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DAY_NIGHT, mode).apply()
    }

    fun defaultStrategyLabel(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_STRATEGY, DriveStrategy.DEFAULT.label)
                ?: DriveStrategy.DEFAULT.label

    fun setDefaultStrategy(context: Context, label: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STRATEGY, label).apply()
    }

    /** 持久化的默认策略（规划/重算统一入口）。 */
    fun defaultStrategy(context: Context): DriveStrategy =
        DriveStrategy.entries.firstOrNull { it.label == defaultStrategyLabel(context) }
            ?: DriveStrategy.DEFAULT

    // ── 路线偏好独立开关（车机设置半屏 Toggle 行；映射见 [routeStrategyInt]） ──
    private const val KEY_AVOID_CONGESTION = "route_avoid_congestion"
    private const val KEY_AVOID_TOLL = "route_avoid_toll"
    private const val KEY_AVOID_HIGHWAY = "route_avoid_highway"
    private const val KEY_PREFER_HIGHWAY = "route_prefer_highway"

    fun isRouteAvoidCongestion(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AVOID_CONGESTION, false)

    fun setRouteAvoidCongestion(context: Context, v: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AVOID_CONGESTION, v).apply()
    }

    fun isRouteAvoidToll(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AVOID_TOLL, false)

    /** 避免收费与高速优先互斥（SDK 无 PRIORITY_HIGHSPEED+AVOID_COST 组合档）。 */
    fun setRouteAvoidToll(context: Context, v: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AVOID_TOLL, v)
            .apply { if (v) putBoolean(KEY_PREFER_HIGHWAY, false) }
            .apply()
    }

    fun isRouteAvoidHighway(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AVOID_HIGHWAY, false)

    fun setRouteAvoidHighway(context: Context, v: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AVOID_HIGHWAY, v)
            .apply { if (v) putBoolean(KEY_PREFER_HIGHWAY, false) }
            .apply()
    }

    fun isRoutePreferHighway(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFER_HIGHWAY, false)

    /** 高速优先仅可与避免拥堵组合（SDK 19/20 档）；开启即清避免高速/避免收费。 */
    fun setRoutePreferHighway(context: Context, v: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFER_HIGHWAY, v)
            .apply {
                if (v) {
                    putBoolean(KEY_AVOID_HIGHWAY, false)
                    putBoolean(KEY_AVOID_TOLL, false)
                }
            }
            .apply()
    }

    /** 开关组合 → 高德多路线策略常量；高速优先与不走高速互斥（同时开 = 避高速优先）。 */
    fun routeStrategyInt(context: Context): Int {
        val c = isRouteAvoidCongestion(context)
        val t = isRouteAvoidToll(context)
        val h = isRouteAvoidHighway(context)
        val p = isRoutePreferHighway(context) && !h
        return when {
            p && c -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_PRIORITY_HIGHSPEED_AVOID_CONGESTION
            p -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_PRIORITY_HIGHSPEED
            h && t && c -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED_COST_CONGESTION
            h && t -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHTSPEED_COST
            h && c -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED_CONGESTION
            h -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED
            t && c -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_COST_CONGESTION
            t -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_COST
            c -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_CONGESTION
            else -> com.amap.api.navi.enums.PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
        }
    }

    /** 是否在检测到持续行驶时显示巡航卡片（默认开）。 */
    fun isAutoCruiseEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CRUISE, true)

    fun setAutoCruiseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CRUISE, enabled).apply()
    }
}
