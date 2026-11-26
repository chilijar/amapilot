# 贡献指南

感谢关注 Amapilot！提 issue / PR 前请先读完这一页。

## 本地构建

1. 环境：JDK 17 + Android SDK（compileSdk 36）
2. 获取高德 SDK（**二进制不入库**，服务协议禁止再分发，见 `docs/OPEN_SOURCE_COMPLIANCE.md`）：
   ```bash
   ./scripts/fetch-amap-sdk.sh    # 官网直链 + md5 校验，幂等
   ```
   运行脚本即表示你自行与高德缔约并接受其服务协议。
3. 申请你自己的高德 Key（免费，个人约 5 分钟）：[高德开放平台](https://lbs.amap.com/)，
   SHA1 取本地 keystore（没有就先 `keytool -genkeypair` 生成，见 README）
4. `local.properties`（gitignored）：
   ```properties
   sdk.dir=/path/to/android-sdk
   AMAP_API_KEY=你的key
   # 可选：KEYSTORE_PASSWORD / KEYSTORE_ALIAS（本地 release 签名）
   ```
5. 构建与测试：
   ```bash
   ./gradlew assembleDebug testDebugUnitTest
   ```

涉及 Android Auto 车机 UI 的改动：模拟器 + DHU 调试环境见 `docs/SIM_ENV_SETUP.md`，
车机交互契约速查见 `AGENTS.md`。

## 提交规范

- Conventional Commits：`type(scope): description`（feat / fix / docs / style / refactor / test / chore）
- 一个 PR 聚焦一件事，保持可独立回滚
- 提交请 sign-off（DCO）：`git commit -s`

## 硬性红线（违反直接关闭）

- ❌ 提交高德 SDK 二进制（`app/libs/*.jar`、`*.so`）或任何反编译/逆向产物
- ❌ 提交 keystore、API key、密码等任何凭据
- ❌ 提交含真实位置、账号、车牌等个人信息的日志或截图

## Bug 报告

使用 bug_report 模板开 issue，务必包含：app 版本、手机型号与系统版本、
Android Auto 版本与车机车型、复现步骤、`adb logcat` 片段（先脱敏）。

## 许可

提交 PR 即表示你同意以 [Apache-2.0](LICENSE)（项目主许可）授权你的贡献，
与项目其余部分一体分发。
