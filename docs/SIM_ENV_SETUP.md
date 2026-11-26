# 模拟器 + DHU 车机调试环境搭建（迁移到新机器照此清单执行）

目标：在一台新机器上复刻本机已跑通的「Android 模拟器（手机端）+ Desktop Head
Unit（车机端）」调试链路，用于验证 `com.amapilot` 的 Android Auto 自绘地图与
全部车机交互。

> **前提**：全链路（模拟器 + 双地图引擎 + AA + DHU）负载很高——低配机器（1 vCPU）
> load 常态 10~25、每隔几分钟断连（形态见 `AGENTS.md`「负载与断连」）。**需要
> ≥4 个真实可用 vCPU、≥8G 空闲内存、KVM 可用、有 X server（或 VNC）显示 DHU 窗口。**

---

## 0. 判据速查（每步做完怎么算成功）

| 检查 | 命令 | 期望 |
|------|------|------|
| KVM 可用 | `ls -l /dev/kvm` | 存在且当前用户可读写（否则用 `sg kvm -c "..."` 包一层） |
| 模拟器起来 | `adb devices` | `emulator-5554  device` |
| root/remount | `adb shell id` | `uid=0(root)` |
| AA 装好 | `adb shell dumpsys package com.google.android.projection.gearhead \| grep versionName` | `17.9.664004-release` |
| head unit server | `adb shell "netstat -tln \| grep 5277"` | 有监听行 |
| DHU 握手成功 | DHU 日志 | `Verify returned: ok` |
| app 会话拿到 Surface | `adb shell dumpsys display \| grep -c amapilot-car` | ≥1 |
| 车机屏出图 | DHU 控制台 `screenshot /tmp/x.png` | 800x480 PNG，能看到高德地图 |
| 负载可动手 | `adb shell cat /proc/loadavg` | 第一列 < 10 |

---

## 1. 宿主依赖

```bash
# JDK 17（本机用 conda 环境；任何 JDK 17 都行）
java -version    # 需要 17.x

# Android SDK：platform-tools / emulator / 系统镜像 / DHU
sdkmanager "platform-tools" "emulator" \
  "system-images;android-34;google_apis;x86_64" \
  "extras;google;auto"          # ← Desktop Head Unit 2.0（官方最新，无更新版）

# DHU 宿主依赖库（Ubuntu noble；缺则 desktop-head-unit 启动报
# libc++.so.1 / libunwind.so.1 not found 秒退）
sudo apt-get install -y libc++1-18 libc++abi1-18 libunwind-18

# DHU 证书时偏移用的 preload 库
gcc --version
mkdir -p build && gcc -shared -fPIC -o build/dhu-faketime.so scripts/dhu-faketime.c

# 截图验证/OCR（可选，但强烈建议——无图像识别能力时靠它自主验证车机 UI）
python3 -m venv /tmp/opencode/ocr-venv
/tmp/opencode/ocr-venv/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
  rapidocr_onnxruntime pillow numpy
```

网络：Gradle 发行版走腾讯镜像（`gradle/wrapper/gradle-wrapper.properties` 已配），
pip 加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`。

---

## 2. 仓库本地配置（**两个文件都不在 Git 里，必须从旧机器拷或重建**）

### 2.1 `local.properties`

```properties
sdk.dir=/home/<user>/android-sdk
AMAP_API_KEY=<高德 key>
```

### 2.2 `keystores/release.keystore`（**关键，别重新生成**）

高德 key 绑定的是这个 keystore 的 SHA1
（`FD:D6:AF:46:CE:1E:FC:6B:B5:C7:59:EA:4F:6B:ED:54:01:27:68:D9`）。
debug/release 共用它。**拷旧文件**：

```bash
mkdir -p keystores && scp 旧机:.../keystores/release.keystore keystores/
# 凭据（storepass/keypass/alias）见私有运维记录，不写入公开仓库
```

若只能重新生成，则必须去高德控制台为新 SHA1 重新申请 key 并改 `AMAP_API_KEY`，
否则地图/导航 SDK 静默鉴权失败（定位、算路、地图全不可用）：

```bash
keytool -genkeypair -v -keystore keystores/release.keystore -alias amapilot \
  -keyalg RSA -keysize 2048 -validity 10000
keytool -list -v -keystore keystores/release.keystore | grep SHA1
```

### 2.3 构建自检

```bash
export JAVA_HOME=<jdk17>
./gradlew assembleDebug testDebugUnitTest   # 全部测试通过
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. AVD 与模拟器

```bash
avdmanager create avd -n amapilot_test -k "system-images;android-34;google_apis;x86_64"
# 本机 config.ini：abi.type=x86_64 / hw.cpu.ncore=4 / hw.ramSize=1536M
# 新机器建议 hw.ramSize ≥ 3072、hw.cpu.ncore = 可用物理核数

# 启动（-writable-system 是装 AA priv-app 的前提；换 AVD/删 overlay 后需重做 §4）
sg kvm -c "$HOME/android-sdk/emulator/emulator -avd amapilot_test \
  -writable-system -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim"
```

- **必须 x86_64 google_apis 镜像**：它自带 `libndk_translation.so`，才能跑
  arm64-only 的高德 SDK。**AAOS/automotive 镜像不可行**（无 ARM 转译）
- 冷启动后 **`adb root` 必须重做一次**（否则 remount/push 到 /product 失败）
- ABI workaround 已内置构建配置，勿移除（详见 `AGENTS.md`「ABI 说明」）：
  `ndk.abiFilters += "arm64-v8a"` + `jniLibs.useLegacyPackaging = true` +
  manifest `android:extractNativeLibs="true"`
- 冷启动可能出现 SystemUI ANR：`uiautomator dump` 后点 "Wait"
- 注入 GPS：`adb emu geo fix 121.4737 31.2304`（上海人民广场；默认位置在海外，
  首个 fix 会有大跳变）

---

## 4. 装 Android Auto 17.9 到 /product（一次性）

镜像自带的 AA 只是 Play Store 跳转壳（无 head unit server），必须以 priv-app
身份装完整版。AA 17.9 的 Host API = 8，配套 `car-app 1.7.0`。

```bash
# 4.1 取 APK：APKMirror 的 .apkm（split APK 包，本质是 zip）
#     com.google.android.projection.gearhead_17.9.664004-release-*_apkmirror.com.apkm
mkdir -p /tmp/aa17 && cd /tmp/aa17 && unzip -o ~/Downloads/*17.9*.apkm
#     → base.apk + split_config.arm64_v8a.apk + split_config.<lang>.apk ...

# 4.2 原生库从 arm64 split 里解出（手动放入的 priv-app 不走 installd 提取，
#     缺库会在会话启动时 UnsatisfiedLinkError 崩溃）
mkdir -p /tmp/aa17libs && cd /tmp/aa17libs
unzip -o -j /tmp/aa17/split_config.arm64_v8a.apk "lib/arm64-v8a/*"
#     libgmm-jni.so / libhwrword.so / libbase_native.so / libbridge_native.so /
#     libfilesystem_native.so / libflagging_native.so / liblogging_native.so /
#     libandroidx.graphics.path.so / libdatastore_shared_counter.so ...（全推）

# 4.3 推入镜像
export PATH=$HOME/android-sdk/platform-tools:$PATH
GH=com.google.android.projection.gearhead
adb root; sleep 2; adb remount
adb shell pm uninstall --user 0 $GH 2>/dev/null || true
adb shell "rm -rf /product/priv-app/AndroidAutoStubPrebuilt"
adb shell "mkdir -p /product/priv-app/AndroidAuto/lib/arm64-v8a"
adb push /tmp/aa17/*.apk            /product/priv-app/AndroidAuto/
adb push /tmp/aa17libs/*.so         /product/priv-app/AndroidAuto/lib/arm64-v8a/

# 4.4 特权权限白名单（**缺这步 system_server 启动崩溃 = boot loop**）
#     仓库已存副本：scripts/privapp-permissions-androidauto-aa17.xml
adb push scripts/privapp-permissions-androidauto-aa17.xml \
  /product/etc/permissions/privapp-permissions-androidauto.xml

# 4.5 权限/属主/SELinux 标签
adb shell "chmod 644 /product/priv-app/AndroidAuto/*.apk \
    /product/priv-app/AndroidAuto/lib/arm64-v8a/*.so \
    /product/etc/permissions/privapp-permissions-androidauto.xml; \
  chmod 755 /product/priv-app/AndroidAuto/lib/arm64-v8a /product/priv-app/AndroidAuto/lib; \
  chown -R root:root /product/priv-app/AndroidAuto; \
  chcon -R u:object_r:system_file:s0 /product/priv-app/AndroidAuto; \
  chcon u:object_r:system_file:s0 /product/etc/permissions/privapp-permissions-androidauto.xml"

# 4.6 重启模拟器（仍带 -writable-system），然后校验
adb root; adb shell dumpsys package $GH | grep versionName   # 17.9.664004-release（17.8.663814 同样可行）
```

### 4.7 原生库 ABI 注册坑（2026-11 新机器实测，**照 §4.3 用 lib/arm64-v8a 可避开；本节是救砖手册**）

- priv-app 的 lib 目录名必须是**完整 ABI 名** `lib/arm64-v8a`。写成 `lib/arm64` 时 PM
  不识别 → `primaryCpuAbi=null` → `ApplicationInfo.nativeLibraryDir=null` →
  `:car` 进程 dlopen 报 `libresampling_jni.so not found` 崩溃循环。外部症状：
  **DHU 握手 `Verify returned: ok` 后秒断 / 「新 server 首连必败、二连才活」**
  （二连"活"只是 :car 崩溃循环放弃后的降级假象）
- PM 把扫描结果缓存在 `/data/system/packages.xml`：**首次扫描目录名错之后，改正目录
  再重启无效**（primaryCpuAbi 保持 null）；改 codePath（重命名 priv-app 目录）也无效；
  `setenforce 0` 无效（不是 SELinux）
- 恢复（标准 PM 流程把 split 包装成系统应用更新，installd 重新提取库并推导 ABI，
  无需重启、无需动 packages.xml）：

```bash
adb push ~/tmp/aa17/*.apk /data/local/tmp/aa17/
SID=$(adb shell pm install-create -r | tr -d '\r' | grep -oE '[0-9]+')
for f in $(adb shell ls /data/local/tmp/aa17/ | grep '\.apk$' | tr -d '\r'); do
  SZ=$(adb shell stat -c %s /data/local/tmp/aa17/$f | tr -d '\r')
  adb shell pm install-write $SID $SZ /data/local/tmp/aa17/$f $f   # 顺序:<session> <size> <path> [name]
done
adb shell pm install-commit $SID
adb shell dumpsys package $GH | grep primaryCpuAbi   # arm64-v8a 即恢复
```

> `scripts/dhu-connect.sh --prep` 是 **AA 8.1** 的一键版本（历史方案，仍可用但
> 宿主 chrome 较旧、会画黑底转向条）。AA 17 用上面的手动步骤。

---

## 5. 每次连接链（`scripts/dhu-connect.sh` 自动做 1~7）

1. CDM 角色：`cmd role set-bypassing-role-qualification true` +
   `cmd role add-role-holder android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION $GH`
   （`internal|role` 权限唯一来源，**模拟器重启即丢**）
2. `pm enable` FirstActivityImpl / VnLaunchPadActivity（manifest 默认禁用）
3. 补运行时权限（定位）+ 通知监听器 `cmd notification allow_listener '...$ListenerService'`
   （注意 `$` 转义）
4. `am force-stop $GH` → 重拉 `DeveloperHeadUnitNetworkService`
   （**失败会话后服务楔死**：TCP accept 但不处理，必须重启），
   然后 **`netstat` 验证 5277 真的起来再继续**
5. `adb forward tcp:5277 tcp:5277`（模拟器重启即丢）
6. AA 设置页置前台：`am start -a $GH.SETTINGS`
   （Android 14 BAL 豁免：首连 DUPLEX 首启页要求 AA 有可见窗口）
7. 启 DHU（两个致命坑，缺一即静默退出）：
   - **stdin 必须保持打开**（Console 线程读到 EOF 就 `requestQuit()`）
   - **faketime 把进程时间拨回 2022**（手机端 GAL 开发证书 2023-03-22 过期，
     否则 TLS `-24 auth failure`）

```bash
# 交互式（脚本方式，前台窗口）
scripts/dhu-connect.sh

# 自动化方式（agent/脚本用：pty 包装拿到行缓冲回显 + fifo 持续喂命令）
mkfifo /tmp/opencode/dhu.fifo 2>/dev/null
setsid nohup bash -c 'sleep 100000 > /tmp/opencode/dhu.fifo' &   # writer 必须先活着；
      # setsid 脱离进程组——agent harness 会回收后台进程组，无 setsid 时 writer/DHU
      # 会被连带杀（writer 死 → script 阻塞在 fifo open 或读到 EOF 秒退）
cd ~/android-sdk/extras/google/auto && setsid nohup script -qfc \
  "LD_PRELOAD=/path/to/build/dhu-faketime.so DISPLAY=:0 ./desktop-head-unit -a" \
  /dev/null < /tmp/opencode/dhu.fifo 2>&1 | awk '{ print strftime("%T"), $0; fflush() }' \
  > /tmp/opencode/dhu.log &     # awk 加时间戳便于和 logcat 对时间
grep -c "Verify returned: ok" /tmp/opencode/dhu.log          # =1 即握手成功
echo "tap 322 62"    > /tmp/opencode/dhu.fifo                # AA17 launcher 网格 Amapilot 图块
      # （一排二列；AA12 旧坐标 (200,150) 已失效；拿不准先 screenshot 看图）
echo "screenshot /tmp/opencode/car.png" > /tmp/opencode/dhu.fifo
```

- **历史症状「新 server 首连必败、二连才活」= :car 原生库崩溃的假象**（§4.7）；
  ABI 修复后首连即成功。若仍首连秒断：先查 `adb logcat | grep UnsatisfiedLinkError`
```

- DHU stdout **块缓冲**：直接重定向到文件看不到回显，必须 `script -qfc` 伪终端包装
- DHU 控制台命令表（`help`）：`tap x y` / `screenshot <file>` / `dpad *` /
  `keycode *` / `speed` / `location` / `day|night` / `quit`——见 `AGENTS.md`
- **`adb install -r` 之后必须整链重建**（每个车机连接只有首个 app 会话拿 Surface）

---

## 6. 跑通验证（车机端全链路）

```bash
adb emu geo fix 121.4737 31.2304      # 有定位才会有巡航/导航态
echo "tap 322 62" > fifo; sleep 45    # AA17 网格图块 → 等引擎初始化（转译模拟器 4~6s+）
adb shell dumpsys display | grep -c amapilot-car    # ≥1 = VD/Presentation 起来了
echo "tap 756 42" > fifo              # 顶栏搜索钮 → push 宿主 SearchTemplate
echo "screenshot /tmp/opencode/panel.png" > fifo
echo "tap <历史地点行坐标>" > fifo      # 首页历史地点行 → 直达算路选路
adb logcat -d | grep -E "route redrawn|traffic colors|camera:"
```

手机中继搜索（车机卡片里输入来自手机）：
`adb shell input keyevent KEYCODE_WAKEUP` → `am start -n com.amapilot/.ui.MainActivity`
→ **确认 `topResumedActivity=MainActivity` 再输入**（冷启 3.5~8s）→
`input tap 540 1344` → `input keyevent 67`×12 清旧词 → `input text "..."` →
`input keyevent 4` → `input tap 540 1520` → `input keyevent 3` 立即回桌面（降负载）。

全部车机控件坐标见 `docs/CAR_UI_DESIGN.md`「自绘控件与命中坐标」表。

---

## 7. 故障速查

| 症状 | 原因 | 处理 |
|------|------|------|
| DHU 秒退无报错 | stdin EOF | `sleep 100000 \| ./desktop-head-unit -a` 或 fifo + writer |
| DHU `-24 auth failure` | GAL 证书过期 | `LD_PRELOAD=build/dhu-faketime.so` |
| `X connection to :0 broken` | 宿主过载 / X 挂了 | `xdpyinfo` 确认 X 活着 → 等 load<11 → 整链重建 |
| `Ping timeout` 后退出 | 模拟器负载高 | 同上；手机端输完立即回桌面 |
| 所有进程 `DeadSystemException` | system_server 被压死 | 等框架软重启，重做 forward+server |
| 开机 boot loop | AA17 特权权限未白名单 | 推 §4.4 的 xml（或先 `adb shell rm` 掉 AA 目录救砖） |
| AA 会话启动即崩 | priv-app 原生库缺失 | 重推 §4.2 全部 `.so` |
| tap 全静默但 TLS/VD 活着 | 投影输入楔死 | 整链重建（force-stop gearhead+amapilot → 重拉 server → 重 forward → 重启 DHU） |
| 车机屏灰底/无图 | 引擎重建中或 Surface 未派发 | 别在离屏调 `onDestroy()`；确认首个模板是 NavigationTemplate |
| 地图/定位静默失败 | keystore SHA1 与高德 key 不匹配 | §2.2 |
| `desktop-head-unit` 启动秒退报 `libc++.so.1`/`libunwind.so.1` not found | 宿主缺 DHU 依赖库 | `apt-get install libc++1-18 libc++abi1-18 libunwind-18` |
| DHU 握手 ok 后秒断 / 首连必败二连才活 | :car `libresampling_jni.so not found` 崩溃循环 = priv-app ABI 未注册（packages.xml 缓存 null） | §4.7 install-multiple 恢复 |
| keystore 丢失但 Git 历史曾入库 | 早期 commit 含 keystore 后被移除 | `git show <remove-commit>^:keystores/release.keystore` 提取并 keytool 验 SHA1 |
| `UnsatisfiedLinkError`（高德） | ABI workaround 被移除 | 恢复 `AGENTS.md`「ABI 说明」的三处配置 |

**假阴性教训**（曾三次误判"控件不可点"）：①给动作入口层加日志，别只加在深层
②注意屏态污染（面板打开后同坐标 tap 落在新屏上）③重复 tap 排除 DHU 偶发 miss
④AA17 夜间主题会把顶栏图标调暗（亮度检测失灵），用功能探测定位。

---

## 8. 模拟器上验不了的（需真机或注入）

- **到达终点自动退出/转巡航**：EMULATOR 模拟车在终点前最后一个机动动作处停滞，
  `onArriveDestination` 永不触发（`AGENTS.md` 已知问题 #11）→ 真机实跑，或
  `amapilot_force_gps_navi 1` + `adb emu geo fix` 沿路线注入
- **导航中切换路线生效**：`selectRouteId` 在模拟器/真机导航中均被引擎拒绝
  （导航中换路走 `recalculate`，见 AGENTS.md）
- **AMap 定位/巡航引擎**：模拟器 fake 基站是海外 MCC → `INSUFFICIENT_ABROAD_PRIVILEGES`，
  已用系统 GPS 源绕开；电子眼回调仅真机有效
- 旋钮/dpad 本身可在 DHU 用 `-i hybrid` 验证（见 AGENTS.md「旋钮/焦点」）；真车旋钮
  只驱动宿主渲染元素 → 旋钮通道 = 顶栏功能钮/右竖排/宿主列表行
