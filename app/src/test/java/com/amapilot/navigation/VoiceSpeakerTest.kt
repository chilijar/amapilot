package com.amapilot.navigation

import com.amapilot.navigation.VoiceSpeaker.VoiceCategory.CAMERA
import com.amapilot.navigation.VoiceSpeaker.VoiceCategory.FACILITY
import com.amapilot.navigation.VoiceSpeaker.VoiceCategory.LANE
import com.amapilot.navigation.VoiceSpeaker.VoiceCategory.NORMAL
import com.amapilot.navigation.VoiceSpeaker.VoiceCategory.SPEED_LIMIT
import com.amapilot.navigation.VoiceSpeaker.VoiceCategory.STRAIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeakerTest {

    @Test
    fun classifyCameraTexts() {
        assertEquals(CAMERA, VoiceSpeaker.classifyVoiceText("四百米处,有接打电话拍照,"))
        assertEquals(CAMERA, VoiceSpeaker.classifyVoiceText("前方一点二公里内,有多个不礼让行人拍照"))
        assertEquals(CAMERA, VoiceSpeaker.classifyVoiceText("前方有测速摄像头"))
        assertEquals(CAMERA, VoiceSpeaker.classifyVoiceText("经过电子眼路段"))
    }

    @Test
    fun classifySpeedLimitTexts() {
        assertEquals(SPEED_LIMIT, VoiceSpeaker.classifyVoiceText("前方道路限速六十公里"))
        assertEquals(SPEED_LIMIT, VoiceSpeaker.classifyVoiceText("您已超速,请减速"))
    }

    @Test
    fun classifyNormalTexts() {
        assertEquals(NORMAL, VoiceSpeaker.classifyVoiceText("准备出发,全程五公里,大约需要二十分钟"))
        assertEquals(NORMAL, VoiceSpeaker.classifyVoiceText("两百米后,在红绿灯路口左转"))
        // 路口直行指令（无"继续/保持"前缀）不算途中直行提醒
        assertEquals(NORMAL, VoiceSpeaker.classifyVoiceText("在路口直行"))
    }

    @Test
    fun classifyLaneTexts() {
        assertEquals(LANE, VoiceSpeaker.classifyVoiceText("保持直行,走右侧两车道"))
        assertEquals(LANE, VoiceSpeaker.classifyVoiceText("请走左侧车道"))
        assertEquals(LANE, VoiceSpeaker.classifyVoiceText("建议走左侧车道"))
    }

    @Test
    fun turnTextWithLaneAdviceStaysNormal() {
        // 转向播报附带车道建议：不得归 LANE（车道开关关时不能连转向一起静音）
        assertEquals(NORMAL, VoiceSpeaker.classifyVoiceText("三百米后左转,请走左侧车道"))
        assertEquals(NORMAL, VoiceSpeaker.classifyVoiceText("前方路口右转,走右侧车道"))
    }

    @Test
    fun classifyStraightAndFacilityTexts() {
        assertEquals(STRAIGHT, VoiceSpeaker.classifyVoiceText("继续沿沪渝高速直行五公里"))
        assertEquals(STRAIGHT, VoiceSpeaker.classifyVoiceText("继续行驶两公里"))
        assertEquals(FACILITY, VoiceSpeaker.classifyVoiceText("前方一公里进入服务区"))
        assertEquals(FACILITY, VoiceSpeaker.classifyVoiceText("进入隧道,请开大灯"))
    }

    @Test
    fun cameraKeywordTakesPrecedence() {
        assertEquals(CAMERA, VoiceSpeaker.classifyVoiceText("进入测速路段,限速八十"))
    }

    @Test
    fun conciseSuppressesTurnTextsButKeepsEssentials() {
        assertTrue(
            VoiceSpeaker.isConciseSuppressed("三百米后,在红绿灯路口左转", NORMAL, true)
        )
        // 安全/终点类放行
        assertFalse(VoiceSpeaker.isConciseSuppressed("即将到达目的地", NORMAL, true))
        assertFalse(VoiceSpeaker.isConciseSuppressed("已为您重新规划路线", NORMAL, true))
        // 电子眼/限速走各自开关，不受简洁静音
        assertFalse(
            VoiceSpeaker.isConciseSuppressed("四百米处,有接打电话拍照", CAMERA, true)
        )
        // 详细模式不静音
        assertFalse(
            VoiceSpeaker.isConciseSuppressed("三百米后,在红绿灯路口左转", NORMAL, false)
        )
    }
}
