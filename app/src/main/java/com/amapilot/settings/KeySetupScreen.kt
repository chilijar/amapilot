package com.amapilot.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首启高德 key 配置引导。Release 不内置 key（[AmapKey.bundled] 恒空），用户以自己
 * 身份申请 key = 与高德直接缔约（自持配额）。本页在隐私弹窗之后、地图可用之前出现
 * 一次：SHA1 复制 → 控制台申请 → key 粘贴。可跳过（[Prefs.keySetupDismissed] 落盘）；
 * 本地构建已注入 key 时（[AmapKey.isConfigured]）不出现。
 * 页首「导入配置备份」为换机捷径：`.amapilot` 备份含 key + 设置 + 收藏 + 历史，
 * 导入成功且带 key 即完成引导。
 */
@Composable
fun KeySetupScreen(
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var keyText by remember { mutableStateOf("") }
    var sha1Copied by remember { mutableStateOf(false) }
    val sha1 = remember { AmapKey.sha1Hex(context) }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    // 导入静默直落（无确认弹窗）；带 key 即注入并推进引导
    fun applyImport(text: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigBackup.importText(context, text) }
            }
            result.onSuccess {
                if (AmapKey.isConfigured(context)) {
                    // 运行时注入（与「保存并完成」同路径），完全生效仍以重启为准
                    AmapKey.apply(context)
                    toast("已导入，重启 app 后完全生效")
                    onDone()
                } else {
                    toast("已导入，但备份中没有 key：请继续完成下方配置步骤")
                }
            }.onFailure {
                // 用户侧只报"导入失败"，原因进日志
                android.util.Log.w("ConfigBackup", "first-run import failed", it)
                toast("导入失败")
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        ConfigBackup.readLimited(it)
                    } ?: error("无法读取文件")
                }
            }
            result.onSuccess { text -> applyImport(text) }
                .onFailure {
                    android.util.Log.w("ConfigBackup", "first-run import read failed", it)
                    toast("导入失败")
                }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "配置高德 Key",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "本应用为开源软件，不附带高德服务账号：地图、搜索与导航由你自己的" +
                    "高德 key 提供服务（免费，个人申请约 5 分钟），配额与服务条款由你" +
                    "的账号直接承载。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 换机捷径：导入旧机备份 = key + 设置 + 收藏/历史一步到位
            OutlinedButton(
                onClick = {
                    // 不限 MIME：.amapilot 扩展名部分管理器报 octet-stream；格式校验在内容侧
                    openDoc.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入配置")
            }

            // 步骤 ①：SHA1 复制
            Text("① 复制本安装包的签名 SHA1（高德控制台绑定用）", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Text(
                sha1 ?: "未知",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    if (AmapKey.copySha1(context)) {
                        sha1Copied = true
                        android.widget.Toast.makeText(
                            context, "SHA1 已复制", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (sha1Copied) "已复制 ✓（点击重新复制）" else "复制 SHA1")
            }

            // 步骤 ②：控制台申请
            Text("② 到高德开放平台创建 Android 平台 key", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Text(
                "登录 lbs.amap.com → 控制台 → 应用管理 → 创建应用 → 添加 Key：" +
                    "平台选 Android，包名填 com.amapilot，发布版/调试版 SHA1 都粘贴" +
                    "上方复制的值。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://console.amap.com/dev/key/create"))
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开高德控制台")
            }

            // 步骤 ③：粘贴 key
            Text("③ 将生成的 key 粘贴到此处", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("32 位 key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    if (!AmapKey.isValidFormat(keyText)) {
                        android.widget.Toast.makeText(
                            context,
                            "key 格式无效：应为 32 位十六进制字符串（检查是否复制完整）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }
                    Prefs.setAmapApiKey(context, keyText)
                    AmapKey.apply(context)
                    android.widget.Toast.makeText(
                        context, "已保存，重启 app 后生效", android.widget.Toast.LENGTH_LONG
                    ).show()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = keyText.isNotBlank()
            ) {
                Text("保存并完成")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = {
                    Prefs.setKeySetupDismissed(context, true)
                    onDone()
                }) {
                    Text("稍后再说（可随时到 设置 → 高德 Key 配置）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
