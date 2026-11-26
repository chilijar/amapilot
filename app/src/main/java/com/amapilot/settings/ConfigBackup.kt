package com.amapilot.settings

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置备份：设置项 + key + 收藏 + 地点历史 → 加密的 `.amapilot` 文本，经 SAF 导出/导入。
 * 无需口令：AES/GCM/NoPadding + 每文件 12B 随机 IV，密钥由内置材料 PBKDF2 派生（GCM
 * 认证标签保证文件被改/损坏时解密即失败，不会导入脏数据）。
 * 文件自描述（`MAGIC\n<kdf;iter;cipher;tag>\n<base64(iv‖ct)>`），解密按声明参数并做
 * 白名单校验（防塞弱参数降级），未来调参不破旧文件。
 * **威胁模型**：内置密钥 = 混淆级防护，防肉眼可读，不防逆向；刻意不用 Keystore
 * （卸载即灭失、换机不可解）。白名单导出不含 `privacy_agreed` 与启动自救标记。
 * [encrypt]/[decrypt] 无 Android 依赖，纯 JVM 单测覆盖。
 */
object ConfigBackup {

    const val MAGIC = "AMAPILOT-CFG-1"

    const val FILE_EXT = "amapilot"

    private const val PBKDF2_ITERATIONS = 10_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PREFS_NAME = "amapilot_prefs"

    /** 内置密钥材料（混淆级）：运行时分段拼接抬高静态字符串扫描成本，非真保密。 */
    private fun appSecret(): CharArray =
        ("com.amapilot" + "::" + "config" + "-backup" + "::" + "9f3aC7e1B5d8420f")
            .toCharArray()

    /** 固定盐：密钥不随设备/文件变化 = 备份文件换机可解（随机性由每文件 IV 提供）。 */
    private fun kdfSalt(): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("amapilot-config-backup-salt-v1".toByteArray(Charsets.UTF_8))
            .copyOf(16)

    /** key 覆盖值键名（预览 hasKey 判定与白名单共用）。 */
    private const val KEY_API_KEY = "amap_api_key_override"

    /** 导出的设置键白名单（Boolean/String 值原样往返）。 */
    private val PREF_KEYS = listOf(
        "voice_enabled",
        "voice_camera_enabled",
        "voice_speed_limit_enabled",
        "voice_detail",
        KEY_API_KEY,
        "key_setup_dismissed",
        "day_night_mode",
        "default_strategy",
        "route_avoid_congestion",
        "route_avoid_toll",
        "route_avoid_highway",
        "route_prefer_highway",
        "auto_cruise_enabled",
    )

    /** 收藏 JSON（FavoriteRepository 同键）。 */
    private const val KEY_FAVORITES = "favorites"

    /** 地点历史 JSON（HistoryStore 同键）。 */
    private const val KEY_HISTORY = "unified_history"

    /** 导入结果摘要（内部用；UI 只报"已导入"，细节不打扰用户）。 */
    data class Summary(
        val prefs: Int,
        val favorites: Int,
        val history: Int,
        val skipped: List<String> = emptyList(),
    )

    /** 收藏有效性核查待办标志键（导入含收藏时置位，MainActivity 消费即清）。 */
    private const val KEY_FAV_CHECK = "fav_check_pending"

    /** 核查触发信号（进程内）：导入含收藏时递增，MainActivity 监听后跑核查。 */
    val favCheckTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun favCheckPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FAV_CHECK, false)

    fun clearFavCheckPending(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FAV_CHECK, false).apply()
    }

    /** 备份文件大小上限：正常 <100KB，2MB 封顶防误选大文件读入 OOM。 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 限量读取备份文本（超限立即抛错，不整读进内存）。纯 JVM 可单测。 */
    fun readLimited(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) {
                throw IllegalArgumentException("文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB，不是备份文件")
            }
            out.write(chunk, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 合法 JSON 数组才放行，损坏返回 null（跳过该导入项，避免冲掉本机有效数据）。 */
    internal fun validJsonArrayOrNull(s: String): String? =
        runCatching { org.json.JSONArray(s) }.map { s }.getOrNull()

    // ── 导出/导入（设备侧，org.json + SharedPreferences） ──

    /** 组装配置明文并加密为可落盘文本。 */
    fun exportText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        val prefsJson = JSONObject()
        PREF_KEYS.forEach { k ->
            when (val v = all[k]) {
                is Boolean -> prefsJson.put(k, v)
                is String -> prefsJson.put(k, v)
                else -> Unit // 缺失/未知类型跳过（导入侧保持目标原值）
            }
        }
        val root = JSONObject()
            .put("v", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefsJson)
        (all[KEY_FAVORITES] as? String)?.let { root.put(KEY_FAVORITES, it) }
        (all[KEY_HISTORY] as? String)?.let { root.put(KEY_HISTORY, it) }
        return encrypt(root.toString())
    }

    /**
     * 解密并应用备份（损坏/非本格式抛异常，调用方转 UI 提示）。合并语义：备份里
     * **有**的项覆盖本机，没有的保持原值（跨版本缺新键不清数据）；损坏的收藏/历史
     * 跳过并经 [Summary.skipped] 回报。key 覆盖值需重启进程生效。
     */
    fun importText(context: Context, text: String): Summary {
        val root = JSONObject(decrypt(text))
        if (root.optInt("v", 0) != 1) throw IllegalArgumentException("不支持的备份版本")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var nPrefs = 0
        val pj = root.optJSONObject("prefs")
        if (pj != null) {
            val allowed = PREF_KEYS.toSet()
            pj.keys().forEach { k ->
                if (k !in allowed) return@forEach
                when (val v = pj.opt(k)) {
                    is Boolean -> editor.putBoolean(k, v)
                    is String -> editor.putString(k, v)
                    else -> return@forEach
                }
                nPrefs++
            }
        }
        val skipped = mutableListOf<String>()
        var nFav = 0
        if (root.has(KEY_FAVORITES)) {
            val fav = validJsonArrayOrNull(root.optString(KEY_FAVORITES, ""))
            if (fav != null) {
                editor.putString(KEY_FAVORITES, fav)
                nFav = countJsonArray(fav)
            } else {
                skipped += "收藏"
            }
        }
        var nHis = 0
        if (root.has(KEY_HISTORY)) {
            val his = validJsonArrayOrNull(root.optString(KEY_HISTORY, ""))
            if (his != null) {
                editor.putString(KEY_HISTORY, his)
                nHis = countJsonArray(his)
            } else {
                skipped += "历史"
            }
        }
        editor.apply()
        if (nFav > 0) {
            // 导入收藏后触发一次性有效性核查（见 FavoriteValidator；全有效则零打扰）
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FAV_CHECK, true).apply()
            favCheckTick.value++
        }
        return Summary(nPrefs, nFav, nHis, skipped)
    }

    private fun countJsonArray(s: String): Int =
        runCatching { org.json.JSONArray(s).length() }.getOrDefault(0)

    // ── 加密原语（纯 JVM，可单测） ──

    /** 文件头参数行（明文、自描述）：解密按此执行，白名单校验见 [decrypt]
     *  （KDF/cipher/tag 只接受已知值，iter 夹在 [MIN_KDF_ITERATIONS, MAX]）。 */
    private const val KDF_NAME = "PBKDF2WithHmacSHA256"
    private const val CIPHER_NAME = "AES/GCM/NoPadding"
    private const val MIN_KDF_ITERATIONS = 1_000
    private const val MAX_KDF_ITERATIONS = 10_000_000
    private val PARAMS_LINE =
        "kdf=$KDF_NAME;iter=$PBKDF2_ITERATIONS;cipher=$CIPHER_NAME;tag=$TAG_BITS"

    /** 明文 → `MAGIC\n参数行\n<base64(iv‖ct)>`；每文件随机 IV 保证同内容密文不同。 */
    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(PBKDF2_ITERATIONS),
            GCMParameterSpec(TAG_BITS, iv)
        )
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return MAGIC + "\n" + PARAMS_LINE + "\n" +
            Base64.getEncoder().encodeToString(iv + ct)
    }

    /** 解密 [encrypt] 产物（按文件声明参数执行并校验）；内容被改/非本格式/参数
     *  不支持均抛异常。 */
    fun decrypt(text: String): String {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size >= 3 && lines[0] == MAGIC) { "不是 Amapilot 备份文件" }
        val params = lines[1].split(';').associate {
            val kv = it.split('=', limit = 2)
            (kv.getOrNull(0) ?: "") to (kv.getOrNull(1) ?: "")
        }
        require(params["kdf"] == KDF_NAME) { "不支持的密钥派生算法: ${params["kdf"]}" }
        require(params["cipher"] == CIPHER_NAME) { "不支持的加密算法: ${params["cipher"]}" }
        require(params["tag"] == TAG_BITS.toString()) { "不支持的认证标签长度" }
        val iter = params["iter"]?.toIntOrNull()
        require(
            iter != null && iter in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS
        ) { "不支持的 KDF 迭代数: ${params["iter"]}" }
        val blob = Base64.getDecoder().decode(lines.drop(2).joinToString(""))
        require(blob.size > IV_BYTES) { "备份文件内容不完整" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(
            Cipher.DECRYPT_MODE, deriveKey(iter), GCMParameterSpec(TAG_BITS, iv)
        )
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** AES 密钥 = 内置材料 + 固定盐派生（不随设备/文件变化 = 换机可解）。 */
    private fun deriveKey(iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(appSecret(), kdfSalt(), iterations, 256)
        val key = SecretKeyFactory.getInstance(KDF_NAME).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }
}
