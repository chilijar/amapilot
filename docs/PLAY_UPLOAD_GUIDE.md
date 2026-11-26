# Amapilot Play 内部测试上架清单

> 目标：通过 Play 商店安装到手机，让 Android Auto launcher 显示 Amapilot。
> 耗时：注册 15 分钟 + 上传 10 分钟 + 高德改绑 5 分钟 + 测试员安装 5 分钟。

## 前提

| 项 | 值 |
|---|---|
| AAB 文件 | CI/本地 `./gradlew bundleRelease` 产出（`app/build/outputs/bundle/release/*.aab`） |
| Upload 签名 SHA1 | 你本地 keystore 的 SHA1（`keytool -list -v -keystore keystores/release.keystore`） |
| 高德 Key | 当前架构为零内置 key，用户自持（见下方「高德 Key 与 Play 签名」） |
| Google 账号 | 用于注册 Play Console 和作为测试员 |
| 费用 | $25（一次性，终身） |

---

## 第 1 步：注册 Play Console（15 分钟）

1. 打开 https://play.google.com/console
2. 用 Google 账号登录
3. 选择「个人开发者账号」（个人/独立开发者）
4. 支付 $25 注册费
5. 完成身份验证（可能需要几小时到几天审核，通常个人账号当天通过）
6. 接受开发者分发协议

## 第 2 步：创建应用（5 分钟）

1. Play Console 首页 → 「创建应用」
2. 填写：
   - 应用名称：`Amapilot`
   - 默认语言：简体中文
   - 应用/游戏：应用
   - 免费/付费：免费
3. 点「创建」

## 第 3 步：上传 AAB 到内部测试轨道（10 分钟）

1. 左侧菜单 → **测试** → **内部测试**
2. 点「创建新版本」
3. 首次会提示签名设置 → **选择「让我选择并管理自己的密钥」或类似选项**
   - ⚠️ 关键决策（见下方「高德 Key 与 Play 签名」）
4. 上传 `./gradlew bundleRelease` 产出的 AAB
5. 版本名填当前 `versionName`（见 `app/build.gradle.kts`）
6. 点「保存」→「审核版本」→「开始发布到内部测试」

## 第 4 步：设置测试员（5 分钟）

1. 内部测试页 → 「测试员」标签
2. 选择「邮件列表」→ 输入你自己的 Gmail
3. 保存 → 复制 **opt-in 链接**（形如 `https://play.google.com/internal/test/xxxxx`）

## 第 5 步：手机安装（5 分钟）

1. 手机打开 opt-in 链接（Chrome）
2. 点「接受」加入测试计划
3. 跳转 Play 商店 → 安装 Amapilot
4. **确认来源**：手机连接 adb 后验证：
   ```
   adb shell dumpsys package com.amapilot | grep installer
   ```
   应显示 `installerPackageName=com.android.vending`（Play 商店）

## 第 6 步：验证 AA Launcher

1. 手机连接车机/插入车机
2. 车机屏 AA launcher → 应出现 **Amapilot**（地图图标）
3. 点击打开 → 应显示巡航界面
4. 如果仍不显示，抓日志：
   ```
   adb logcat -s CAR.VALIDATOR
   ```
   此时 `isPackageInstalledByPlay` 应返回 `true`，validator 放行

---

## 高德 Key 与 Play 签名（关键决策）

Play 上传 AAB 时有**两种签名方案**，直接影响高德 Key 是否需要重新绑定：

### 方案 A：让 Play 使用你的密钥（推荐，Key 不用改）

- 上传时选择「让我管理自己的密钥」或「使用与上传密钥相同的密钥签名」
- Play 应用签名 SHA1 = 你的 upload SHA1 = `FD:D6:AF:...:68:D9`
- **现有高德 Key 无需任何改动**
- 部分新账号可能不提供此选项；如果只能用 Google 管理的签名，走方案 B

### 方案 B：Google 管理签名（需要改绑 Key）

> **2026-11 起优先用运行时 key 覆盖替代改绑重传**：app 设置页「高德 Key」展示
> 当前安装包签名 SHA1（Play 重签后的真实值）并支持粘贴用户自持 key
> （`MapsInitializer/AMapNavi.setApiKey` 启动注入）。测试员/其他用户以自己的
> 高德账号按页内 SHA1 注册 key 填入即可，无需开发者改绑、无需重传 AAB。
> 下述改绑流程仅作为"全员共用开发者 key"的备选保留。

- Play 自动生成新的应用签名密钥（SHA1 与你的不同）
- 上传后在 Play Console → **设置 → 应用完整性（App integrity）** 查看「应用签名密钥证书」的 SHA-1
- 去高德控制台（lbs.amap.com）→ 你的应用 → Key 管理 → **新建一个 Key**，绑定：
  - 包名：`com.amapilot`
  - SHA1：Play 应用签名 SHA1
- 修改 `local.properties` 中的 `AMAP_API_KEY` 为新 Key
- 重新 `./gradlew bundleRelease` → 重新上传 AAB
- ⚠️ 建议同时保留原 Key（绑定你的 upload SHA1），这样本地侧载和 Play 版都能用

---

## 商店信息填写（内部测试最低要求）

| 项 | 内容 | 备注 |
|---|---|---|
| 隐私政策 URL | 必填 | 需要一个可访问的网页链接（GitHub Pages 即可） |
| 应用截图 | 1 张即可 | 任意手机截图 |
| 应用图标 | 512x512 | 已有 launcher icon 可导出 |
| 内容分级 | 填问卷 | 选择「所有人」+「不含广告」等 |
| 数据安全 | 声明收集数据 | 声明收集「位置」「应用活动」 |
| 目标受众 | 18+ | 选择成年用户 |

> 内部测试轨道对商店信息要求最宽松，隐私政策是唯一必填项。

---

## 隐私政策（如无现成的）

在 GitHub 仓库创建 `docs/privacy-policy.md`，启用 GitHub Pages 后即可作为 URL。内容要点：

```
Amapilot 隐私政策
- 收集数据：设备位置（用于导航）、搜索历史（本地存储）
- 不收集：个人身份信息、联系方式、设备标识符
- 不共享数据给第三方
- 数据仅存储在设备本地（SharedPreferences）
```

---

## 常见问题

**Q: 上传 AAB 报错 targetSdk 34 太低？**
A: 内部测试轨道接受 targetSdk 34。如果被拒，通知我，我来升 35（需先解决高德 16KB 对齐问题）。

**Q: 测试员安装后地图不显示/白屏？**
A: 高德 Key 绑定的 SHA1 与运行时不匹配（方案 B 场景）。确认 Play 应用签名 SHA1 并改绑高德 Key。

**Q: AA launcher 仍不显示？**
A: 确认安装来源为 Play（`adb shell dumpsys package com.amapilot | grep installer`），然后强停 Android Auto + 重启手机重试。

**Q: 以后更新版本？**
A: 修改版本号 → `./gradlew bundleRelease` → 上传新 AAB → 测试员在 Play 商店直接更新。
