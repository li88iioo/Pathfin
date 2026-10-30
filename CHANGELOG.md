# 更新日志

记录 Pathfin 各版本面向用户的变更，最新版本在前。GitHub Release 和应用内更新说明共用对应版本的正文。

首版内容涵盖从[初始源码](https://github.com/li88iioo/Pathfin/commit/ed01b3e91565d31fcf736ed4d010eda69132050d)到 `v0.1.0` 标签的全部提交。本地测试 build 不单独列为正式版本。

## [0.1.0] - 2026-10-30

### 首次发布

- Pathfin 首个公开版本：面向 Android 的原生 Jellyfin 客户端，提供媒体浏览与播放、STRM 直连和 MediaFlux 云盘画质切换。

### 本次重发

- 本次基于最新代码重新发布 `0.1.0`，Android 版本码提升至 `32`。已安装旧 `0.1.0` 的用户请[重新下载 APK](https://github.com/li88iioo/Pathfin/releases/download/v0.1.0/Pathfin-arm64-v8a.apk)覆盖安装；同名版本不会触发应用内更新提示。
- [优化直播页重复进入](https://github.com/li88iioo/Pathfin/commit/71c25b60cc36373fdc912d4679ac22403202bd6a)：同一会话内保留频道、搜索、分页和滚动位置，过期时保留已有内容刷新。
- [优化媒体库控制台进入与返回](https://github.com/li88iioo/Pathfin/commit/ba7d963b5cf102f39ce20b7b27b2f196b561c8df)：保留子页、列表和滚动位置，读取期间可切换子页，避免旧日志响应覆盖新选择。

### 主要功能

- 支持多服务器、多账号与备用地址，以及媒体库浏览、搜索、收藏、筛选排序和批量管理。
- 支持视频播放、续播、选集、音轨与字幕切换、章节和画中画。
- 支持 STRM 原始地址直连，以及 MediaFlux 云盘原画、高质量、标准、流畅四档画质；支持通过媒体反代获取画质，并[保留当前服务器地址和端口](https://github.com/li88iioo/Pathfin/commit/da039c40bbde657a5a5b1ae9de52b66d85e60921)。
- 支持 ARM64 FFmpeg 音视频补解，包括 H.264、HEVC 和 [MPEG-2 视频](https://github.com/li88iioo/Pathfin/commit/d4771e767dd1f47d16a6581e46bea59c2055484e)，提供 GPU 色彩处理与可选 ASS 字幕渲染。
- 支持媒体后台下载、网络规则和默认保存目录，以及下载恢复、取消与重试。
- 支持字幕本地导入、服务器上传与在线搜索、元数据与图片管理，以及直播和 DLNA。
- 提供深浅色主题、简体中文与英文界面、封面缓存和设置导入导出。
- 支持检查 GitHub 正式版本、查看更新说明、下载并校验安装包，以及调用系统安装器升级。

### 使用要求与限制

- 最低支持 Android 8.0；FFmpeg 和 libass 原生扩展目前仅支持 ARM64。
- STRM 直连和画质切换目前仅在 MediaFlux 生成的 STRM 与光鸭云盘上验证，其他来源尚未验证。
- 在线字幕需要服务器配置提供商并授予相应权限；实际解码性能取决于设备和媒体，不能保证所有设备都能流畅软解 4K。
- 正式版与开发版使用独立包名，账号和设置不互通；覆盖升级需保持相同包名和签名。

[0.1.0]: https://github.com/li88iioo/Pathfin/releases/tag/v0.1.0
