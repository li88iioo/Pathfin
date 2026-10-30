<p align="center"><img src="assets/brand/pathfin-logo.png" width="300" alt="Pathfin"></p>

# Pathfin

[English](README.md) | 简体中文

[![Android APK](https://img.shields.io/badge/Android-下载%20APK%20%28arm64--v8a%29-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/li88iioo/Pathfin/releases/latest/download/Pathfin-arm64-v8a.apk)

面向 Android 手机和平板的原生 Jellyfin 客户端，使用 Kotlin 和 Jetpack Compose，支持播放服务器媒体源提供的 STRM 原始 HTTP(S) 地址。

## 功能

- 多服务器、多账号与备用地址管理。
- 首页、媒体库、搜索、收藏、筛选排序、字母导航和自动分页；媒体库长按封面多选，批量标记播放状态、刷新元数据或删除。
- 影片详情、续播、选集、字幕、音轨、章节与画中画。
- ExoPlayer 播放器，ARM64 FFmpeg 音视频补解、GPU 色彩处理、可选 ASS 渲染及四档播放质量切换；支持 [MediaFlux](https://github.com/li88iioo/MediaFlux) STRM 云盘画质直连。
- 字幕本地导入、服务器上传与在线搜索；下载网络规则和默认保存目录。
- 媒体下载、元数据与图片管理、直播和 DLNA。
- 深浅色主题、封面缓存、隐藏系统状态栏及设置导入导出。
- 简体中文与英文界面，跟随系统或应用语言设置。

最低 Android 8.0 / API 26。libass 和 FFmpeg 原生扩展目前仅支持 ARM64；在线字幕需要服务器提供商与相应权限。MediaFlux STRM 的画质来自网盘已有资源，媒体数据直接传到手机，不自动改走 Jellyfin 转码。普通服务器媒体仍可按服务器能力协商播放。服务器磁盘上的本地路径不能直接作为手机可访问的播放地址。

> **注：** STRM 直连播放与 STRM 画质切换目前仅在 [MediaFlux](https://github.com/li88iioo/MediaFlux) 生成的 STRM 和光鸭云盘上测试通过，其他来源的 STRM 未经验证。

使用中遇到问题请先查看[帮助与常见问题](docs/help-faq.zh-CN.md)。

版本变更见[更新日志](CHANGELOG.md)，正式安装包见 [GitHub Releases](https://github.com/li88iioo/Pathfin/releases)。

## STRM 云盘画质

开启“STRM 原始地址”后，支持画质协议的 [MediaFlux](https://github.com/li88iioo/MediaFlux) STRM 会复用播放器的“原画 / 高质量 / 标准 / 流畅”菜单，显示云盘实际分辨率和编码；不可用的档位不能点击。切换保留播放位置、暂停状态和倍速，获取失败保留当前播放，过期直链可重新解析。

原画能力判断保留 Jellyfin 的完整视频轨信息，包括 Dolby Vision Profile、位深和动态范围；云端压缩档按自身格式判断。默认原画已知无法解码时，优先选择最高兼容云档，不改写默认画质偏好；没有兼容云档时明确提示本机不支持，不发起 Jellyfin 转码。手动切换画质失败仍优先恢复原来可播放的资源，网络错误只对原 STRM 地址续签一次。

视频默认优先使用系统 MediaCodec。ARM64 的 H.264/HEVC 软件补解覆盖 Hi10P 和 Dolby Vision Profile 5：FFmpeg 解码视频，libplacebo 在 GPU 上按逐帧色彩及 RPU 元数据映射为 SDR；不会把 Profile 5 简单改写成普通 HEVC。明确的系统音频或视频解码错误，在确认本地扩展支持该格式后，可按音频、视频各同源回退一次；保留播放位置、倍速、暂停和轨道选择，不改变持久偏好。

原有硬解 HDR10/HLG 路径保留。渐进式 Profile 7 和 Media3 支持的 Profile 8 可使用 HEVC 基础层，不重建 Profile 7 FEL 增强层。软件路径当前限制单边 4096、总像素不超过 4096×2304，是否能实时播放仍取决于设备、编码复杂度与帧率；不承诺任意 4K 文件或设备都能流畅软解。网络、容器、音频、字幕与播放时钟继续由同一个 ExoPlayer 管理。

“STRM 默认画质”取代手写“首选版本”，默认原画，仅影响支持该协议的 STRM 下次起播。本地文件和直播不使用云盘画质；多个文件版本仍可手选，旧版本名称偏好不会被猜测成画质。

电影、剧集或视频明确开启原始路径并选择原画时，普通 HTTP STRM 也保持直连，不要求服务端实现云盘画质协议。已知本机无法处理或实际解码失败时不自动替换成 Jellyfin 转码；关闭原始路径或主动选择服务器画质后，仍按服务器能力协商。原地址网络重试继续遵守直连选择。

服务端需提供 `mediaflux.playback.v1` 的 `variants` / `resolve` 接口。连接 MediaFlux 媒体反代时，支持读取 `MediaSource.MediaFluxPlayback` 附带的原始签名 STRM 地址，继续使用云盘画质；MF 与 Pathfin 两端均需支持此扩展，手机需能访问该地址。只保存原始签名 STRM 地址用于查询和续期，不把临时 CDN URL 当作新媒体版本，也不向云盘发送 Jellyfin 账号凭据。

## 构建

需要 JDK 17、Android SDK 37，以及项目锁定的 NDK 30.0.16248370 和 CMake 3.22.1。使用 `ANDROID_HOME` 或本地 `android/local.properties` 指定 SDK。

原生库不入库；首次构建先生成 ASS 与 FFmpeg 音视频模块及其许可资产和对应源码归档：

```bash
python3 android/ffmpeg/build_native.py --jobs 2
python3 android/ass/build_native.py --jobs 2
python3 android/tools/package_source.py --native ffmpeg --output .superpowers/tmp/ffmpeg-native/release/pathfin-ffmpeg-corresponding-source.tar
python3 android/tools/package_source.py --native ass --output .superpowers/tmp/ass-native/release/pathfin-ass-corresponding-source.tar
```

原生构建前置条件、锁定输入与对应源码说明见 [ASS 模块](android/ass/README.md)和 [FFmpeg 音视频模块](android/ffmpeg/README.md)。FFmpeg 构建音频解码、重采样及 H.264/HEVC 软件解码，视频色彩映射由 libplacebo GLES 输出承担；网络、容器和播放时钟由 Media3 管理。libass、字体解析和整形依赖静态链接入 ASS JNI。两个模块无相互运行时依赖，只有 ASS 模块打包 NDK 的共享 C++ 运行时。

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

正式包使用 `:app:assembleRelease`，启用 R8 与资源裁剪。签名配置默认读取仓库外的 `~/.local/share/pathfin/signing/release.properties`，也可通过 `-Ppathfin.signingFile=/绝对路径/release.properties` 指定；文件提供 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。

开发版包名为 `dev.pathfin.android.debug`，正式版为 `dev.pathfin.android`，数据独立。

发布前对最终 APK 运行 `android/tools/verify_release.py`，通过 `--build-tools` 指定工具目录、`--expected-certificate-sha256` 指定已确认的生产签名证书摘要、`--output` 保存报告。检查包含正式包身份、预期签名、16 KiB 对齐、原生依赖、离线许可及测试资源隔离；不要从待验 APK 自身推定“预期证书”。完整安装与播放回归使用[独立正式包 smoke 工具](android/tools/production_smoke/README.md)，只能在没有个人账号的新装测试环境运行。

## 更新日志与发布

`CHANGELOG.md` 是版本变更内容的唯一维护入口。GitHub Release 使用对应版本的正文，应用内更新弹窗直接展示同一份内容。日志使用简短列表和绝对链接，避免表格、图片和复杂 Markdown。

准备正式版本时，核对 `android/app/build.gradle.kts` 的 `versionName` 与递增的 `versionCode`，按 `git log --reverse --no-merges <上一个正式标签>..<目标提交>` 整理新增、优化、修复及必要的升级说明；首版核对截至目标提交的全部历史。日常开发提交不逐次追加日志，也不把本地 build 当作正式版本。

版本标题格式为 `## [X.Y.Z] - YYYY-MM-DD`，正文至少包含一条 `- 内容` 列表。当前首版的“待发布”需在确认发版时替换为实际日期。对需要追溯的条目补充真实提交链接；已有上一正式标签时维护对应版本的比较链接，若保留 `[Unreleased]` 比较链接则更新到新标签与 `HEAD`。

提交发版改动前可在仓库根目录验证并预览正文：

```bash
python3 -m unittest discover -s android/tools -p 'test_extract_release_notes.py'
mkdir -p .superpowers/tmp
python3 android/tools/extract_release_notes.py CHANGELOG.md 0.1.0 --output .superpowers/tmp/release-notes.md
```

将命令中的版本号替换为待发版本；“待发布”、无效日期、缺失或重复的版本章节、空变更列表都会导致检查失败。完成发布验收并提交后，再为最终提交创建并推送匹配的 `vX.Y.Z` 标签。流水线先提取日志，再构建和发布 APK；不再从标签说明或自动提交摘要生成另一份正文。

## 第三方组件

应用保留离线开源许可与上游证据。分发含 FFmpeg 音视频和 libass 扩展的 APK 时，一并提供与二进制匹配的两份对应源码归档。`android/tools/verify_native_pairing.py` 使用 `--ffmpeg-source` 和 `--ass-source` 核对最终 APK、原生库及归档；详见原生模块说明。
