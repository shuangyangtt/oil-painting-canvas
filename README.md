# 油图布儿歌（安卓电视 / 投影仪版）

给两岁孩子用遥控器自己选儿歌的播放器，基于电脑版[油图布](../../Downloads/youtubu-app)的思路，视频全部经 Invidious 播放。

## 孩子怎么用
- 打开就是一排排大封面：最上面「最喜欢」，下面每排一个儿歌频道（默认 18 个英文和德文频道，按最受欢迎排序）
- 上下左右选歌，OK 播放；一首放完自动放同一排的下一首
- 播放中：OK 暂停/继续，◀ ▶ 后退/快进 10 秒，↑↓ 显示进度，返回键回到选歌
- 选歌页按返回键不会退出程序（防止孩子误按）；退出用遥控器的主页键

## 家长
- **短按 ≡**：把当前这首歌加入/移出「最喜欢」
- **长按 ≡（约 1 秒）**：家长设置。屏幕上有二维码和网址，用同一 WiFi 下的手机或电脑打开，可以粘贴频道链接、按名字搜频道、删除、排序、管理最喜欢、调清晰度、换 Invidious 服务器

## 技术
Kotlin + Jetpack Compose（电视遥控器焦点）、Media3 ExoPlayer（Invidious DASH 清单，失败时退回 360p MP4）、Coil、OkHttp、ZXing。
数据全部存在投影仪本地（频道、最喜欢、每排的缓存 12 小时）。
注意：Invidious 服务器会拒绝没有浏览器 User-Agent 的请求，所以所有请求都带浏览器标识。

## 打包和安装
```
./gradlew assembleRelease          # 生成 app/build/outputs/apk/release/app-release.apk
./install.sh 192.168.x.x           # 通过 WiFi 装到投影仪
```
需要 ~/Android/Sdk（local.properties 里的 sdk.dir）。

## 关于
家长设置里的「关于 · 免责声明」（手机管理页底部也有）：软件来源（电脑版基于 FreeTube；电视版视频经 Invidious；Media3、Compose、OkHttp、Coil、ZXing）、视频版权归 YouTube 原作者、仅限个人非商业使用、隐私、免责声明。内容在 About.kt 的 ABOUT_SECTIONS。
