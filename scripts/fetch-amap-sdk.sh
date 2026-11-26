#!/usr/bin/env bash
# 高德一体包 SDK 拉取脚本（SDK 二进制不入库：高德服务协议 4.12.8 禁止使他人可获得开发包，
# 见 docs/OPEN_SOURCE_COMPLIANCE.md P0-1）
#
# 用法:
#   scripts/fetch-amap-sdk.sh            # 幂等：文件齐且 md5 匹配 → 直接退出（无网络访问）
#   scripts/fetch-amap-sdk.sh --force    # 忽略本地缓存，重新下载官网最新 zip 并解包落位
#
# SDK 升级流程:
#   1. ./scripts/fetch-amap-sdk.sh --force     # 拉官网最新 zip（URL 无版本号，永远指向最新）
#   2. 核对版本矩阵: curl 'https://lbs.amap.com/service/api/getSdkJson?pkgname=AMap3DMap_AMapNavi_AMapSearch_AMapLocation'
#   3. 用脚本输出提示的新 md5 值更新本文件 EXPECTED_ZIP_MD5 与 EXPECTED_FILES 清单
#   4. 同步更新 .github/actions/setup-sdk/action.yml 中 cache key 的 md5 后缀
#   5. 提交（版本变更 = 显式 commit，供 review）
#
# 前置依赖: bash / curl / unzip / md5sum（GitHub ubuntu-latest 与 WSL 均自带）
# 也可不用本脚本，手动从官网下载一体包 zip：jar 放 app/libs/，arm64 .so 放 app/src/main/jniLibs/arm64-v8a/
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT/app/libs"
JNILIBS_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---- 版本锁定 pin（SDK 升级时更新，见头部说明）----
# zip 整包 md5：官方元数据接口 getSdkJson 同值，下载前后双重校验依据
EXPECTED_ZIP_MD5="66bb15c096b1527609a8d1580d8ec760"
ZIP_URL="https://a.amap.com/lbs/static/zip/AMap3DMap_AMapNavi_AMapSearch_AMapLocation.zip"

# 文件级 md5 清单（Navi/3DMap 11.3.300 + Search 9.8.1 + Location 11.3.300；
# 官网同 URL 会无声换包，文件级 md5 pin 是版本变更的唯一防线）
EXPECTED_FILES_MD5="39f2f324c607ee2bcd42851fd8e2f0d3  app/libs/AMap3DMap_11.3.300_AMapNavi_11.3.300_11.3.300.230_AMapSearch_9.8.1_AMapLocation_11.3.300_20261120.jar
dd4142b3ded9f096ef68c3519fb391a4  app/src/main/jniLibs/arm64-v8a/libAMapSDK_NAVI_v11_3_300.so
21c8e461397e23c79700460ff3eab89e  app/src/main/jniLibs/arm64-v8a/libapssdk.so
11f27a7bf6b0b17a2650cfe50b6bc292  app/src/main/jniLibs/arm64-v8a/libc++_shared.so
60427f0ad9d320538b52de6f2219c81d  app/src/main/jniLibs/arm64-v8a/libneonui_shared.so
ebaea845e2c1da944cdaea8fe4ad406f  app/src/main/jniLibs/arm64-v8a/libneonuijni_public.so
79298ae6c96d56a457a83fa643788469  app/src/main/jniLibs/arm64-v8a/libnui.so
0e634b910d24022beec883e83560e720  app/src/main/jniLibs/arm64-v8a/libopenssl.so"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

# 校验仓库内 SDK 文件 md5（成功返回 0，缺失或不匹配返回 1）
verify_in_place() {
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    md5="${line%% *}"
    rel="${line#*  }"
    f="$ROOT/$rel"
    [ -f "$f" ] || return 1
    [ "$(md5sum "$f" | awk '{print $1}')" = "$md5" ] || return 1
  done <<< "$EXPECTED_FILES_MD5"
  return 0
}

if [ "$FORCE" -eq 0 ] && verify_in_place; then
  echo "AMap SDK: 已就绪（md5 校验通过，跳过下载）"
  exit 0
fi

echo "AMap SDK: 下载 $ZIP_URL"
ZIP="$TMP/amap_sdk.zip"
curl -fsSL --retry 3 -o "$ZIP" "$ZIP_URL"

ACTUAL_ZIP_MD5="$(md5sum "$ZIP" | awk '{print $1}')"
if [ "$ACTUAL_ZIP_MD5" != "$EXPECTED_ZIP_MD5" ]; then
  echo "ERROR: zip md5 不匹配" >&2
  echo "  期望: $EXPECTED_ZIP_MD5" >&2
  echo "  实际: $ACTUAL_ZIP_MD5" >&2
  if [ "$FORCE" -eq 1 ]; then
    echo "  （--force 模式）官网 zip 内容已变化——若为 SDK 升级，请按脚本头部流程更新 pin 值" >&2
  else
    echo "  下载损坏或官网已更换内容——可重试；若持续失败，官网可能已发新版，需更新本脚本 pin 值" >&2
  fi
  exit 1
fi

# 只解出 arm64 需要的部分（zip 内另有 armeabi-v7a/x86_64，abiFilters=arm64-v8a 用不到）
unzip -qo "$ZIP" -d "$TMP/unpacked" "AMap3DMap*.jar" "arm64-v8a/*" -x "arm64-v8a/" || true

mkdir -p "$LIBS_DIR" "$JNILIBS_DIR"
# jar：先清旧版本（防多版本并存导致重复类）
rm -f "$LIBS_DIR"/AMap3DMap*.jar
cp "$TMP/unpacked"/AMap3DMap*.jar "$LIBS_DIR/"
# .so：整目录替换
rm -f "$JNILIBS_DIR"/*.so
cp "$TMP/unpacked/arm64-v8a/"*.so "$JNILIBS_DIR/"

if ! verify_in_place; then
  echo "ERROR: 落位后文件 md5 校验失败，请检查 pin 清单是否与 zip 内容一致" >&2
  exit 1
fi

JAR_NAME="$(basename "$LIBS_DIR"/AMap3DMap*.jar)"
echo "AMap SDK: 就绪"
echo "  jar: app/libs/$JAR_NAME"
echo "  so : $(ls "$JNILIBS_DIR"/*.so | wc -l) 个 arm64-v8a 库"
