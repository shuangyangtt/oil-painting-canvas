#!/bin/bash
# 安装油图布儿歌
#   投影仪 / 电视（WiFi 调试）：./install.sh 投影仪的IP地址      例如 ./install.sh 192.168.1.50
#   平板（数据线，打开 USB 调试）：./install.sh pad
set -e
cd "$(dirname "$0")"
if [ "$1" = "pad" ]; then
  APK=app/build/outputs/apk/pad/release/app-pad-release.apk
  [ -f "$APK" ] || { echo "正在打包平板版……"; ./gradlew assemblePadRelease -q; }
  adb install -r "$APK"
  echo "装好了：在平板上打开「油图布儿歌」，它会自动开始下载儿歌（最多 16 GB）"
  exit 0
fi
APK=app/build/outputs/apk/tv/release/app-tv-release.apk
if [ ! -f "$APK" ]; then
  echo "正在打包……"
  ./gradlew assembleTvRelease -q
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
