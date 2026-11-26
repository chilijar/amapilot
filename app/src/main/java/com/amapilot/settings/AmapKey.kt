package com.amapilot.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import com.amap.api.maps.MapsInitializer

/**
 * 高德 key 运行时配置（用户自持 key，摆脱开发者配额依赖）。Play App Signing 会重签
 * 安装包（签名 SHA1 ≠ 本地 keystore），内置 key 必然鉴权失败，故设置页展示当前安装包
 * 的 SHA1 供用户注册自有 key；覆盖值经 [MapsInitializer.setApiKey] 启动早期注入。
 */
object AmapKey {

    /** 生效 key：用户覆盖值优先，否则内置（manifest meta-data 兜底由 SDK 自读）。 */
    fun effective(context: Context): String = Prefs.amapApiKey(context)

    /** 高德 key 格式：32 位十六进制。错格式注入会让 SDK 初始化期崩溃（进不了设置
     *  自救），故注入与保存双侧门禁。 */
    fun isValidFormat(key: String): Boolean =
        Regex("^[0-9a-fA-F]{32}$").matches(key.trim())

    /** 进程启动早期调用（MainActivity / CarAppService onCreate）：有覆盖即注入。 */
    fun apply(context: Context) {
        val key = Prefs.amapApiKey(context)
        if (key.isBlank()) return
        if (!isValidFormat(key)) {
            android.util.Log.w("AmapKey", "override key malformed, ignore: $key")
            return
        }
        // 地图引擎与导航引擎各自的静态注入点
        runCatching { MapsInitializer.setApiKey(key) }
            .onFailure { android.util.Log.w("AmapKey", "maps setApiKey failed", it) }
        runCatching { com.amap.api.navi.AMapNavi.setApiKey(context, key) }
            .onFailure { android.util.Log.w("AmapKey", "navi setApiKey failed", it) }
    }

    /** manifest 内置 key（本地构建取自 local.properties/env；Release 产物恒为空 =
     *  用户自持配额）。 */
    fun bundled(context: Context): String = runCatching {
        val meta = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData ?: return@runCatching ""
        meta.getString(BUNDLED_META_NAME)?.trim() ?: ""
    }.getOrDefault("")

    /** key 是否已就绪：用户覆盖或内置任一格式有效。首启 key 引导的判据。 */
    fun isConfigured(context: Context): Boolean {
        val override = Prefs.amapApiKey(context)
        if (override.isNotBlank()) return isValidFormat(override)
        return isValidFormat(bundled(context))
    }

    private const val BUNDLED_META_NAME = "com.amap.api.v2.apikey"

    /** 当前安装包签名证书 SHA1（XX:XX:… 格式，与高德控制台一致）。**必须取
     *  [SigningInfo.getSigningCertificateHistory]**：apkContentsSigners 首项在 Play
     *  签名包上是 Source Stamp 证书，会致 1008 签名不匹配。 */
    fun sha1Hex(context: Context): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        } else {
            null
        }
        val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info != null) {
            // 显式 SDK_INT 分支满足 lint NewApi（SigningInfo API 均 28+）
            info.signingCertificateHistory?.firstOrNull()
                ?: info.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }
        val bytes = cert?.toByteArray() ?: return@runCatching null
        java.security.MessageDigest.getInstance("SHA1").digest(bytes)
            .joinToString(":") { String.format("%02X", it) }
    }.getOrNull()

    fun copySha1(context: Context): Boolean {
        val sha = sha1Hex(context) ?: return false
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("amap-sha1", sha))
        return true
    }

    /** 启动鉴权探针结果（设置页展示 + 错 key 对话框依据）。 */
    sealed class ProbeResult {
        object Unknown : ProbeResult()
        object Ok : ProbeResult()
        data class KeyInvalid(val code: Int) : ProbeResult()
        object Quota : ProbeResult()
    }

    @Volatile
    var lastProbe: ProbeResult = ProbeResult.Unknown
        private set

    /** 轻量鉴权探针：一次 POI 请求按错误码分类（1001/1002/1008/1009 = key 无效，
     *  1004 = 配额超限）；网络等非鉴权失败不判 key 问题。1008 必须映射，否则会假阴性。 */
    suspend fun probe(context: Context): ProbeResult {
        val res = runCatching {
            com.amapilot.map.PoiSearcher(context).searchPoi("地图")
            ProbeResult.Ok as ProbeResult
        }.recoverCatching { t ->
            when (val code = (t as? com.amapilot.map.SearchException)?.code ?: -1) {
                1001, 1002, 1008, 1009 -> ProbeResult.KeyInvalid(code)
                1004 -> ProbeResult.Quota
                else -> ProbeResult.Ok
            }
        }.getOrDefault(ProbeResult.Ok)
        lastProbe = res
        return res
    }
}
