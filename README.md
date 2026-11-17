# 牛排火锅 FM 网络收音机(RadioFM) - 开源车载网络电台广播 App

基于现代化 Android 技术栈构建的高性能车载网络收音机与电台广播流媒体播放器，专为汽车中控大屏与车机系统深度定制。

## 🌟 核心特性

- 🚗 **汽车方向盘切台与车载中控按键深度适配**：
  - 参考官方 VLC 架构设计了 `PlaylistManager` 与 `PlayerKeyListenerDelegate` 按键分发委托；
  - 深度集成经典与现代 Android `MediaSession` 车载媒体会话协议；
  - 无论车机方控发出的是电台切台键 `KEYCODE_CHANNEL_UP/DOWN` (166/167)、媒体上一曲/下一曲 `KEYCODE_MEDIA_NEXT/PREVIOUS` (87/88)，还是车载私有切歌广播（如 `com.android.music.musicservicecommand`），均可精准拦截并流畅换台；
  - 方向键 `DPAD_UP/DOWN` 特意放行，不参与换台，保证列表焦点与遥控器导航正常；
  - 完善的音频焦点管理（AudioFocus），在导航播报、倒车雷达或来电时自动压低/暂停，播毕自动平滑恢复。

- 🔊 **单一播放内核：全部流统一由 VideoLAN LibVLC 播放**：
  - 不再按格式分流引擎，网络电台、HLS (`.m3u8`)、AAC/MP3、Tvheadend MPEG-TS 一律交由 LibVLC 解码，行为一致、不会因换源换引擎而出现「有的台能响、有的台不响」；
  - **默认不向 LibVLC 声明容器格式**，解封装方式由 libvlc 自带的 demux probe 现场判定（TS 校验 `0x47` 同步字节与 188 字节包对齐、HLS 校验 `#EXTM3U` 与 `application/vnd.apple.mpegurl`、MP4 校验 box 结构）。这正是 VLC 官方客户端能同时直接播放 TS 与 HLS 的机制——**它从不猜格式**，我们也照此实现；
  - 硬解开关交给 `Media.setHWDecoderEnabled()`（官方 3.0.23 源码：开硬解拼 `:codec=<Mediacodec模块>,all`，关硬解拼 `:codec=all`；`all` 是有效伪模块名），不在全局用 `--codec` 指定解码器列表；
  - 播放内核采用 `libvlc-all 3.7.1`（内核 LibVLC 3.0.23 正式版，与官方 VLC 客户端同款），TS/HLS 的解封装统一由内核 demux probe 自动判定；
  - **三级容灾**：自动探测失败 → 强制 `:demux=ts` 重试 → 强制 TS 并转 libavcodec 软解 → 仍失败则延时 3 秒回到自动探测。TS 专用参数只在兜底轮次出现，绝不参与首轮播放；
  - 网络缓冲取 1500 ms（与官方客户端同量级），避免直播流延迟持续堆积；
  - Media3 仅保留 `MediaSession` 车载会话所需的 Player 载体作用，**不参与任何解码**，因此不引入 HLS/RTSP 解封装器等冗余依赖。

- 📻 **全套标准电台流媒体播放**：
  - 原生支持 HTTP / HTTPS 音频直播流、AAC、MP3 及 HLS (`.m3u8`) 广播流；
  - 默认电台播放列表源支持云端动态更新与本地持久化缓存；
  - 播放列表文本解析兼容三种写法：两行制（名称在上、URL 在下）、单行 `名称,URL`、以及 `#genre#` / `[分组]` 分类标签。

- 📍 **车机专用实时定位与天气（高德地图版）**：
  - 5 秒高频双轨定位，优先硬件 GPS，隧道地库无信号时平滑回落网络基站，再以 IP 定位兜底；
  - **逆地理编码改用高德地图 Web 服务**（`restapi.amap.com/v3/geocode/regeo`）：位移超过 150 米或跨区自动重新解析县市名称，兼容无 GMS 车机；
  - **天气取自高德地图天气查询 API**（`restapi.amap.com/v3/weather/weatherInfo`），按逆地理得到的 adcode 查询实时天气，15 分钟刷新一次；
  - 高德 Key 位于 `WeatherLocationHelper.kt` 的 `AMAP_KEY` 常量，可在高德开放平台控制台自行替换。

- 🌙 **深色 / 夜间模式自动跟随系统**：
  - 内置 DayNight 双套主题与色彩规范；
  - 汽车大灯开启或系统进入夜间模式时，界面毫秒级自动切换为深色护眼模式，夜间行车不刺眼。

- 🔔 **车载标准媒体通知栏与后台前台保活**：
  - 适配 Android 8.0 至 Android 14+ 前台媒体播放服务（`mediaPlayback` 类型）；
  - 提供标准车载大卡片通知栏控制（上一台、暂停/播放、下一台一键操作）。

- 🚀 **云端全自动构建与发布（CI/CD）**：
  - 配置 GitHub Actions 工作流，代码提交自动编译 Debug APK 并发布至 GitHub Releases 与 Artifacts。

---

## 📥 下载与安装

您可以直接前往仓库的 [Releases 页面](https://github.com/zkkm2580/RadioFM-AMap/releases) 下载最新编译好的 APK 安装包：

- **最新版本下载**：[FM网络收音机 高德天气版.apk](https://github.com/zkkm2580/RadioFM-AMap/releases/latest)

> ℹ️ 本版本 `applicationId` 为 `io.nbhg.fma`，可与原版 `io.nbhg.fm` 同时安装、互不覆盖。

> ℹ️ 安装包内含 VideoLAN LibVLC 的 `armeabi-v7a` 与 `arm64-v8a` 两套 `.so` 动态库，体积较早期纯 Kotlin 版本明显增大，具体大小请以 Releases 页面显示为准。

---

## 🔧 Fork 后改成自己的 App（关键配置位置）

本项目把**高德 Key**与**节目单地址**都集中成了常量，fork 之后只需改下面两处，再重新提交代码（GitHub Actions 会自动编译并把 APK 发布到你自己的 Releases）。

### 1. 高德地图 API Key

- **文件**：`app/src/main/java/io/microshow/fm/util/WeatherLocationHelper.kt`
- **位置**：文件顶部 `companion object` 中的 `AMAP_KEY` 常量（约第 72 行）

```kotlin
/** 高德开放平台 Web 服务 Key（可在高德控制台随时重置） */
private const val AMAP_KEY = "31f95c634777d3cf154bae9748c40d86"
```

- 替换成你自己的 Key：前往 [高德开放平台控制台](https://console.amap.com/) 注册 → 创建应用 → 添加 Key，**服务平台务必选「Web 服务」**（本项目调用的是 Web 服务 REST API，不是 Android 地图 SDK）。
- 两个接口地址也在同一文件的常量中，一般无需改动：
  - `AMAP_REGEO_URL` = `https://restapi.amap.com/v3/geocode/regeo`（逆地理：经纬度 → 区县名称 + adcode）
  - `AMAP_WEATHER_URL` = `https://restapi.amap.com/v3/weather/weatherInfo`（按 adcode 查实时天气）
- 安全建议：Key 明文写在客户端有被他人盗用的风险，可在高德控制台给这个 Key 设置**日调用量上限 / IP 白名单**。

### 2. 节目单 radio.txt 地址

- **文件**：`app/src/main/java/io/microshow/fm/data/RadioRepository.kt`
- **位置**：`companion object` 中的 `RADIO_SOURCE_URL` 常量（第 12 行）

```kotlin
const val RADIO_SOURCE_URL = "http://zj.537224.xyz/radio.txt"
```

- 换成你自己的节目单 URL 即可。App 启动会异步拉取并做本地缓存，网络失败时回退到内置的 4 个预设频道（同样写在 `RadioRepository.kt` 的 `getPresetChannels()`）。
- `radio.txt` 支持三种写法（解析逻辑见 `RadioRepository.parseChannels()`）：
  - **两行制**：第一行频道名称，第二行播放 URL；
  - **单行制**：`频道名称,播放URL`；
  - **分组标签**：`#genre#` 或 `[分组名]`。
- 播放源支持 HTTP/HTTPS 音频直链、AAC、MP3 与 HLS `.m3u8`，统一由 LibVLC 解码。

### 3.（可选）改成你自己的品牌

| 要改什么 | 文件位置 | 当前值 |
| --- | --- | --- |
| 包名（安装标识） | `app/build.gradle.kts` → `applicationId` | `io.nbhg.fma`（带 `a` 以便与原版 `io.nbhg.fm` 共存） |
| 应用名称 | `app/src/main/res/values/strings.xml` → `app_name` | 牛排火锅 FM 网络收音机 |
| 应用图标 | `app/src/main/res/drawable/ic_radio.xml` | 内置电台图标 |

> 💡 `namespace` 请保持 `io.microshow.fm` 不变（它与源码包路径、R 类引用绑定，擅自修改会导致编译失败）；只改 `applicationId` 即可安全地改包名。

---

## 🛠️ 技术架构

- **语言与工具链**：Kotlin 1.9.22、Java 17、AGP 8.2.2、Gradle 8.5
- **编译目标**：compileSdk 34 / targetSdk 34 / minSdk 21（Android 5.0+），仅打包 `armeabi-v7a`、`arm64-v8a`
- **核心组件**：
  - `org.videolan.android:libvlc-all`（**唯一播放引擎**：全格式解封装、软硬解容灾）
  - `androidx.media3:media3-session`（车载媒体会话协议）
  - `androidx.media3:media3-exoplayer`（仅提供 MediaSession 所需的 Player 载体，不参与解码）
  - `androidx.media:media`（经典车载 Media 兼容）
  - `androidx.appcompat`（DayNight 夜间模式支持）
  - `androidx.recyclerview`（电台列表高效渲染）

## 📁 代码结构

```
app/src/main/java/io/microshow/fm/
├── data/RadioRepository.kt            电台源拉取、文本解析、URL 兼容处理
├── model/RadioChannel.kt              频道数据模型
├── player/PlaylistManager.kt          VLC 风格播放列表与循环切台
├── player/RadioPlayerService.kt       前台服务：LibVLC 播放、MediaSession、AudioFocus、通知栏
├── ui/MainActivity.kt                 主界面：时钟、定位天气、频道列表
├── ui/ChannelAdapter.kt               频道列表适配器
├── ui/PlayerKeyListenerDelegate.kt    车机按键拦截与防抖
└── util/WeatherLocationHelper.kt      GPS / 网络 / IP 三级定位 + 高德逆地理与天气
```

---

- 'QQ群:887679093'
