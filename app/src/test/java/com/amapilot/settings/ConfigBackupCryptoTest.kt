package com.amapilot.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置备份加密原语（[ConfigBackup.encrypt]/[ConfigBackup.decrypt]）：
 * AES-256-GCM，密钥 = app 内置材料派生（无口令，用户定稿）。仅测纯 JVM
 * 部分（JSON/SharedPreferences 组装在设备侧，不入单测）。
 */
class ConfigBackupCryptoTest {

    private val secret = """{"v":1,"prefs":{"amap_api_key_override":"0123456789abcdef0123456789abcdef"}}"""

    @Test
    fun `roundtrip recovers plaintext`() {
        val payload = ConfigBackup.encrypt(secret)
        assertTrue(payload.startsWith(ConfigBackup.MAGIC + "\n"))
        // 密文本体不含明文 key（敏感信息不可直接文本读出）
        assertNotEquals(true, payload.contains("0123456789abcdef"))
        assertEquals(secret, ConfigBackup.decrypt(payload))
    }

    @Test
    fun `random iv makes ciphertexts differ`() {
        val a = ConfigBackup.encrypt(secret)
        val b = ConfigBackup.encrypt(secret)
        assertNotEquals(a, b)
        assertEquals(ConfigBackup.decrypt(a), ConfigBackup.decrypt(b))
    }

    @Test
    fun `tampered ciphertext fails gcm authentication`() {
        val payload = ConfigBackup.encrypt(secret)
        // base64 在第三行（魔数 + 参数行之后）
        val lines = payload.lines()
        val blob = java.util.Base64.getDecoder().decode(lines[2])
        // 翻转密文区一个字节（iv 12B 之后）
        blob[20] = (blob[20].toInt() xor 0xFF).toByte()
        val tampered = lines[0] + "\n" + lines[1] + "\n" +
            java.util.Base64.getEncoder().encodeToString(blob)
        val ex = runCatching { ConfigBackup.decrypt(tampered) }.exceptionOrNull()
        assertTrue(
            "expected GeneralSecurityException, got $ex",
            ex is java.security.GeneralSecurityException
        )
    }

    @Test
    fun `weakened kdf params rejected (downgrade guard)`() {
        // 参数行是明文：篡改塞弱迭代数必须被白名单拒绝，而非照单执行
        val payload = ConfigBackup.encrypt(secret)
        val lines = payload.lines()
        val weakened = lines[0] + "\n" +
            lines[1].replace(Regex("iter=\\d+"), "iter=10") + "\n" + lines[2]
        val ex = runCatching { ConfigBackup.decrypt(weakened) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `foreign text file rejected by magic check`() {
        val ex = runCatching { ConfigBackup.decrypt("just some notes\nhello\nworld") }
            .exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `readLimited accepts normal backup size`() {
        val payload = ConfigBackup.encrypt(secret)
        val read = ConfigBackup.readLimited(
            java.io.ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        )
        assertEquals(secret, ConfigBackup.decrypt(read))
    }

    @Test
    fun `readLimited rejects oversized file without buffering it all`() {
        // 2MB+1 的垃圾流：超限即抛，不整读进内存（防误选大文件 OOM）
        val junk = ByteArray((ConfigBackup.MAX_FILE_BYTES + 1).toInt()) { 'x'.code.toByte() }
        val ex = runCatching {
            ConfigBackup.readLimited(java.io.ByteArrayInputStream(junk))
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `corrupted favorites json detected before write`() {
        // GCM 保证传输完整——走到校验还坏 = 旧机导出时即损坏，须跳过该项
        // （防坏串冲掉新机有效收藏）
        assertEquals("[{\"id\":\"a\"}]", ConfigBackup.validJsonArrayOrNull("[{\"id\":\"a\"}]"))
        assertEquals(null, ConfigBackup.validJsonArrayOrNull("{\"not\":\"an array\"}"))
        assertEquals(null, ConfigBackup.validJsonArrayOrNull("garbage"))
        assertEquals(null, ConfigBackup.validJsonArrayOrNull(""))
    }
}
