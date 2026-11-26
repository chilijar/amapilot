package com.amapilot.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.amapilot.settings.Prefs
import java.util.Locale

/**
 * 导航语音播报：系统 TTS 播放引擎广播文本（[com.amap.api.navi.AMapNaviListener.onGetNavigationText]）。
 * 开关实时读设置（[Prefs.isVoiceEnabled]）；重复文本 1 秒内去重（int/String 双回调）。
 * 引擎文本无分类字段，电子眼/限速等按关键词识别（[classifyVoiceText]）分别屏蔽。
 */
object VoiceSpeaker {

    private const val TAG = "NaviVoice"

    /** 双回调重复文本的去重窗口。 */
    private const val DEDUP_WINDOW_MS = 1000L

    internal enum class VoiceCategory {
        CAMERA, SPEED_LIMIT, LANE, STRAIGHT, FACILITY, NORMAL
    }

    private val CAMERA_KEYWORDS = listOf("拍照", "电子眼", "探头", "测速", "摄像头", "违章")

    private val SPEED_KEYWORDS = listOf("限速", "超速")

    private val LANE_KEYWORDS = listOf("车道")

    /** 转向动词：含这些词的文本算转向播报，不归 LANE（否则关车道会连转向一起静音）。 */
    private val TURN_KEYWORDS = listOf("左转", "右转", "掉头", "转弯")

    private val FACILITY_KEYWORDS = listOf("服务区", "隧道", "收费站", "停车场")

    /** "继续直行/继续行驶"类途中提醒（机动路口直行指令不含"继续/保持"）。 */
    private val STRAIGHT_PATTERN = Regex("继续.{0,8}直行|保持.{0,8}直行|继续沿|继续行驶|继续前行")

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var lastText: String = ""
    private var lastAt = 0L

    /** 导航会话开始时初始化 TTS 引擎（无论开关状态，支持中途开启）。 */
    fun start(context: Context) {
        if (tts != null) return
        appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                ready = true
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    /** 播报一段文本：按分类读开关，新播报打断未播完的旧文本。 */
    fun speak(text: String?) = speakInternal(text, bypassConcise = false)

    /** 合成播报（[GuidanceController]）：绕过简洁静音（主开关/去重仍生效）。 */
    fun speakForce(text: String?) = speakInternal(text, bypassConcise = true)

    private fun speakInternal(text: String?, bypassConcise: Boolean) {
        if (text.isNullOrBlank()) return
        val category = classifyVoiceText(text)
        Log.d(TAG, "speak [$category]: $text")
        if (!ready) return
        val context = appContext ?: return
        if (!Prefs.isVoiceEnabled(context)) return
        val allowed = when (category) {
            VoiceCategory.CAMERA -> Prefs.isVoiceCameraEnabled(context)
            VoiceCategory.SPEED_LIMIT -> Prefs.isVoiceSpeedLimitEnabled(context)
            // 车道/继续直行/设施：详细=引擎原文，简洁=静音（由合成器替代）
            VoiceCategory.LANE,
            VoiceCategory.STRAIGHT,
            VoiceCategory.FACILITY -> !Prefs.isVoiceConcise(context)
            VoiceCategory.NORMAL -> true
        }
        if (!allowed) return
        // 简洁模式：引擎转向类文本静音（由 GuidanceController 合成），安全/终点放行
        if (!bypassConcise &&
            isConciseSuppressed(text, category, Prefs.isVoiceConcise(context))
        ) {
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < DEDUP_WINDOW_MS) return
        lastText = text
        lastAt = now
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navi_$now")
    }

    /** 安全/终点类关键词：简洁模式下不静音。 */
    private val ESSENTIAL_KEYWORDS = listOf("到达", "目的地", "重新规划", "偏航", "新路线", "改道")

    /** 简洁模式是否静音该引擎文本（仅转向类 NORMAL；安全/终点放行）。 */
    internal fun isConciseSuppressed(
        text: String,
        category: VoiceCategory,
        concise: Boolean,
    ): Boolean = concise &&
        category == VoiceCategory.NORMAL &&
        ESSENTIAL_KEYWORDS.none { text.contains(it) }

    /** 停止当前播报（保留引擎，导航会话内使用）。 */
    fun stop() {
        tts?.stop()
    }

    /** 释放 TTS 引擎（导航会话结束）。 */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        lastText = ""
    }

    /** 按关键词分类播报文本（电子眼优先于限速，含"测速"归电子眼）。 */
    internal fun classifyVoiceText(text: String): VoiceCategory = when {
        CAMERA_KEYWORDS.any { text.contains(it) } -> VoiceCategory.CAMERA
        SPEED_KEYWORDS.any { text.contains(it) } -> VoiceCategory.SPEED_LIMIT
        LANE_KEYWORDS.any { text.contains(it) } &&
            TURN_KEYWORDS.none { text.contains(it) } -> VoiceCategory.LANE
        FACILITY_KEYWORDS.any { text.contains(it) } -> VoiceCategory.FACILITY
        STRAIGHT_PATTERN.containsMatchIn(text) -> VoiceCategory.STRAIGHT
        else -> VoiceCategory.NORMAL
    }
}
