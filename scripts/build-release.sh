#!/usr/bin/env bash
# 打一个可交付的 release 包，并打印验收需要的信息。
# 用法：./scripts/build-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# 必须锁定 JDK 21：Gradle 8.11.1 不支持 JDK 25，且失败信息只会显示一个 "25"，极难排查
export JAVA_HOME="${GITPOCKET_JAVA_HOME:-$HOME/bin/jdk-21.0.2.jdk/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "找不到 JDK：$JAVA_HOME（可用 GITPOCKET_JAVA_HOME 指定）" >&2
  exit 1
fi
JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "$JAVA_VERSION" != "21" ]; then
  echo "需要 JDK 21，当前是 $JAVA_VERSION（$JAVA_HOME）" >&2
  exit 1
fi
SDK="${ANDROID_SDK_ROOT:-$PWD/.tooling/android-sdk}"

if [ ! -f keystore.properties ]; then
  echo "缺少 keystore.properties，无法打 release 包" >&2
  echo "参考 keystore.properties.example，或使用 CI 的 GITPOCKET_* 环境变量" >&2
  exit 1
fi

echo "== 构建 release =="
./gradlew :app:assembleRelease --console=plain "$@"

APK=$(ls app/build/outputs/apk/release/GitPocket-*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  echo "构建结束但没有找到 GitPocket-*.apk" >&2
  exit 1
fi
MAPPING=app/build/outputs/mapping/release/mapping.txt
META=app/build/outputs/apk/release/output-metadata.json

echo
echo "== 验收信息 =="
python3 - "$META" "$APK" <<'PY'
import json, sys, hashlib, os
meta_path, apk_path = sys.argv[1], sys.argv[2]
meta = json.load(open(meta_path))["elements"][0]
print(f"  版本      : {meta['versionName']} (versionCode {meta['versionCode']})")
print(f"  文件      : {os.path.abspath(apk_path)}")
print(f"  大小      : {os.path.getsize(apk_path)/1024/1024:.2f} MB")
digest = hashlib.sha256(open(apk_path, 'rb').read()).hexdigest()
print(f"  SHA-256   : {digest}")
PY

APKSIGNER=$(find "$SDK/build-tools" -name apksigner 2>/dev/null | sort | tail -1)
if [ -n "$APKSIGNER" ]; then
  CERT=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep "SHA-256 digest" | head -1 | sed 's/.*: //')
  echo "  签名      : $CERT"
fi

echo "  mapping   : ${MAPPING}（还原混淆堆栈用）"
echo
echo "安装：adb install -r $APK"
