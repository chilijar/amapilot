#!/usr/bin/env bash
# Android Auto DHU 调试链路一键连接（配套说明见 AGENTS.md「Android Auto DHU 调试」节）
#
# 用法:
#   scripts/dhu-connect.sh          # 完整流程：环境自检 + 手机端拉起 + DHU 连接（前台窗口）
#   scripts/dhu-connect.sh --prep   # 一次性环境准备（模拟器改 /product 装 AA 8.1，需重启模拟器后执行）
#
# 前置依赖:
#   - 模拟器已用 -writable-system 启动（本脚本检测并提示）
#   - AA 8.1 APK: ~/Downloads/com.google.android.projection.gearhead_8.1.626424-*.apk
#   - DHU 2.0:    $ANDROID_HOME/extras/google/auto/desktop-head-unit
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
ADB="$SDK/platform-tools/adb"
DHU_DIR="$SDK/extras/google/auto"
DHU="$DHU_DIR/desktop-head-unit"
FAKETIME_SO="$(dirname "$0")/../build/dhu-faketime.so"
AA_APK="$(ls "$HOME"/Downloads/com.google.android.projection.gearhead_8.1.626424-*.apk 2>/dev/null | head -1)"
GH=com.google.android.projection.gearhead

[ -x "$ADB" ] || { echo "缺 adb: $ADB"; exit 1; }
[ -x "$DHU" ] || { echo "缺 DHU 2.0: $DHU（sdkmanager 'extras;google;auto'）"; exit 1; }

# ---------- 一次性环境准备（模拟器需以 -writable-system 启动） ----------
prep() {
    [ -n "$AA_APK" ] || { echo "缺 AA 8.1 APK（$AA_APK）"; exit 1; }
    "$ADB" root >/dev/null; sleep 2
    "$ADB" remount >/dev/null 2>&1 || { echo "remount 失败：请用 -writable-system 重启模拟器"; exit 1; }
    # 删掉 google_apis 镜像自带的 stub（只是 Play Store 跳转壳，无 head unit server）
    "$ADB" shell pm uninstall --user 0 "$GH" >/dev/null 2>&1 || true
    "$ADB" shell "rm -rf /product/priv-app/AndroidAutoStubPrebuilt" || true
    # 真 AA 8.1 以 priv-app 身份安装（signature|privileged 的 CDM 权限靠这个）
    "$ADB" shell "mkdir -p /product/priv-app/AndroidAuto/lib/arm64"
    "$ADB" push "$AA_APK" /product/priv-app/AndroidAuto/AndroidAuto.apk >/dev/null
    # 手动推送未提取的原生库（手动放入的 priv-app 不走 installd 提取流程）
    for so in libresampling_jni.so libgmm-jni.so libhwrword.so libmappedcountercacheversionjni.so; do
        unzip -p "$AA_APK" "lib/arm64-v8a/$so" > "/tmp/$so"
        "$ADB" push "/tmp/$so" /product/priv-app/AndroidAuto/lib/arm64/ >/dev/null
    done
    "$ADB" shell "chmod 644 /product/priv-app/AndroidAuto/AndroidAuto.apk /product/priv-app/AndroidAuto/lib/arm64/*.so; \
        chmod 755 /product/priv-app/AndroidAuto/lib/arm64 /product/priv-app/AndroidAuto/lib; \
        chown -R root:root /product/priv-app/AndroidAuto; \
        chcon -R u:object_r:system_file:s0 /product/priv-app/AndroidAuto"
    echo "prep 完成：重启模拟器（保持 -writable-system）后运行本脚本完成剩余配置"
}

# ---------- 每次连接流程 ----------
"$ADB" devices | grep -q "emulator.*device" || { echo "模拟器未运行"; exit 1; }

# 1) CDM 角色（SYSTEM_AUTOMOTIVE_PROJECTION：internal|role 权限的唯一来源，重启即丢）
"$ADB" shell cmd role set-bypassing-role-qualification true
"$ADB" shell cmd role add-role-holder android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION "$GH" || true

# 2) AA 关键组件（manifest 默认 enabled=false，靠 GMS ComponentInitReceiver 在 Play 安装时启用）
"$ADB" shell pm enable "$GH/com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl" >/dev/null || true
"$ADB" shell pm enable "$GH/com.google.android.gearhead.vanagon.VnLaunchPadActivity" >/dev/null || true

# 3) 运行时权限（8.1 的模板/导航/通知依赖；换包重装后会丢）
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION; do
    "$ADB" shell pm grant "$GH" "$p" 2>/dev/null || true
    "$ADB" shell pm grant com.amapilot "$p" 2>/dev/null || true
done
"$ADB" shell "cmd notification allow_listener '$GH/com.google.android.gearhead.notifications.SharedNotificationListenerManager\$ListenerService'" 2>/dev/null || true

# 4) 重启 head unit server（失败会话后服务会楔死，必须 force-stop 重拉）
"$ADB" shell am force-stop "$GH"
sleep 3
"$ADB" shell am start-foreground-service \
    -n "$GH/com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"
for i in $(seq 1 30); do
    "$ADB" shell "netstat -tln" 2>/dev/null | grep -q 5277 && break
    sleep 1
done
"$ADB" shell "netstat -tln" | grep -q 5277 || { echo "head unit server 未监听 5277"; exit 1; }

# 5) 端口转发（模拟器重启即丢）
"$ADB" forward tcp:5277 tcp:5277

# 6) AA 设置页置于前台（Android 14 BAL 豁免：首连 DUPLEX 设置页需 AA 有可见窗口）
"$ADB" shell am start -a "$GH.SETTINGS" >/dev/null 2>&1 || true
sleep 2

# 7) DHU 启动。两个关键坑（缺一即静默退出）:
#    a. stdin 必须保持打开 —— 后台运行 stdin EOF 会触发 Console 线程 requestQuit 秒退
#    b. faketime.so 把 DHU 进程时间拨回 2022 —— 手机端 GAL 开发证书 2023-03-22 过期
#    c. -i hybrid 开 rotary/dpad 控制器通道 —— 默认 touch 模式静默丢弃 dpad 命令
#       （旋钮模拟失效根因，见 AGENTS.md「旋钮/dpad 注入」）
if [ -f "$FAKETIME_SO" ]; then
    echo "启动 DHU（faketime + stdin 保持 + hybrid 输入模式）……窗口将出现在当前 X 会话"
    sleep 100000 | LD_PRELOAD="$FAKETIME_SO" "$DHU" -a -i hybrid
else
    echo "提示: 未找到 $FAKETIME_SO（gcc -shared -fPIC -o build/dhu-faketime.so scripts/dhu-faketime.c）"
    echo "      无 faketime 时 DHU 将在 TLS 握手处报 auth failure (-24)"
    sleep 100000 | "$DHU" -a -i hybrid
fi
