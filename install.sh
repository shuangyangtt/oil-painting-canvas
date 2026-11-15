#!/bin/bash
# 把油图布儿歌装到投影仪上（投影仪和电脑要连同一个 WiFi，投影仪上要打开「网络调试 / ADB 调试」）
# 用法：./install.sh 投影仪的IP地址      例如 ./install.sh 192.168.1.50
set -e
cd "$(dirname "$0")"
APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "正在打包……"
  ./gradlew assembleRelease -q
fi
if [ -n "$1" ]; then
  adb connect "$1:5555"
  echo "如果投影仪上弹出「允许 USB 调试吗？」，用遥控器选「允许」（可勾选「始终允许」）"
  sleep 3
  adb -s "$1:5555" install -r "$APK"
else
  adb install -r "$APK"
fi
echo "装好了：在投影仪的应用列表里找「油图布儿歌」"
