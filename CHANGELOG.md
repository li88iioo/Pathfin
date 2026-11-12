# 更新日志

记录 Pathfin 各版本面向用户的变更，最新版本在前。GitHub Release 和应用内更新说明共用对应版本的正文。

首版内容涵盖从[初始源码](https://github.com/li88iioo/Pathfin/commit/ed01b3e91565d31fcf736ed4d010eda69132050d)到 `v0.1.0` 标签的全部提交。本地测试 build 不单独列为正式版本。

## [0.1.5] - 2026-11-12

### 新增

- [同一服务器支持局域网与公网地址自动切换](https://github.com/li88iioo/Pathfin/commit/a1e3ff1ac11d6e1b57e1ef9578bbe60c3840ed92)：网络变化时优先选择可用的局域网地址，保留页面与缓存，也可手动固定地址；起播准备或选源期间换址时，按原片源、起播位置与画质在新地址重新准备。
- [媒体库文件夹支持批量操作](https://github.com/li88iioo/Pathfin/commit/d542a1cf6e77a4d167dfed6b1cefe58be9ff1cb4)：混合库中的文件夹可参与多选，删除确认明确包含内部内容，标记已播放或未播放后同步更新子项缓存。

### 优化

- [重新设计直播电视页](https://github.com/li88iioo/Pathfin/commit/8529647f06a383e8eaf21860bbbc15c239914943)：采用频道宫格，提供上次观看、收藏置顶、节目分类筛选与时间轴节目单。
- [控制台、识别媒体和下载任务页适配毛玻璃标题栏](https://github.com/li88iioo/Pathfin/commit/efc92e9118c1909083140b1cf61e97eb238b808e)，滚动内容与标题栏衔接保持一致。
- [服务器列表统一显示 Jellyfin 账号头像](https://github.com/li88iioo/Pathfin/commit/61c6b8eed62d3af11c7a4bbf6b1c9064f3fc9982)，移除自定义图标；[修改密码说明](https://github.com/li88iioo/Pathfin/commit/7bc4327350beb807f8241089ca7808c9f9d79ec4)明确作用于 Jellyfin 服务器上的账号。

### 修复

- [修复直播分类筛选后无法继续加载频道](https://github.com/li88iioo/Pathfin/commit/a4bdc56bef3ed69f81f469782cbe29da236f9116)：体育、收藏等筛选可跨过没有匹配结果的分页继续读取，加载失败后等待手动刷新重试。
- [成人封面模糊排除普通年龄分级](https://github.com/li88iioo/Pathfin/commit/c4eac0677f245e8e06d1a7ca7ec6e21706a596ab)：TV-MA、18+、NC-17 等分级不再触发模糊，减少普通电影和剧集被误判，设置说明同步更新。
- [修复控制台操作提示常驻](https://github.com/li88iioo/Pathfin/commit/a1b2da771ebb00419928b2969b5bd047d1b9d222)：提示短暂显示后收起，离开页面时清除；服务器重启等仍需确认的状态继续保留说明。

## [0.1.4] - 2026-11-09

### 优化

- [底部抽屉改为在主窗口内绘制](https://github.com/li88iioo/Pathfin/commit/2daf78be9c8606b0fc3d5c4b7016672beec01ae8)：打开与关闭不再新建系统窗口，选集、排序、筛选等抽屉开关时的停顿明显减少，外观与手势保持不变。
- [浮层毛玻璃改为降采样模糊](https://github.com/li88iioo/Pathfin/commit/28592a8a0eb446f309872dbfba701a3dcfb68a85)，抽屉、菜单与底栏滑动时的 GPU 开销更低。
- [识别媒体与编辑元数据页](https://github.com/li88iioo/Pathfin/commit/c199b06f1573c4530d17bcee29b793ae1bbd98e4)的表单等页面[滑入完成](https://github.com/li88iioo/Pathfin/commit/43cf251ed7c0aed90748961aa3177c387fbf8d66)后再铺开，进入时不再在动画中途卡顿。
- [识别媒体页](https://github.com/li88iioo/Pathfin/commit/b62e2b77409370e3b0f456d4c4adb70960afe7e8)的搜索按钮固定在底部，条件表单改为双列。
- [选集绘制更轻](https://github.com/li88iioo/Pathfin/commit/813db57dd9d8f5eb0a83d4378fd8b93f607475ed)，集数较多时抽屉打开更快，数字与列表布局切换更顺畅。
- 详情页[滚过头图时只更新标题栏](https://github.com/li88iioo/Pathfin/commit/ebaea478be0d55e02ce42f9198ef9585914480e1)，不再带动整页重组。
- [封面换图过渡统一](https://github.com/li88iioo/Pathfin/commit/dd790122b110ec859db7bde68667210cea201205)，并减少列表卡片重复构建图片请求。

### 修复

- [浮出通知](https://github.com/li88iioo/Pathfin/commit/d278566ba4fdeb63309f61d1bc986e4e08921653)显示时间缩短为 4 秒，离开页面时自动收起，不再跟到新页面。

## [0.1.3] - 2026-11-08

### 优化

- [详情页优先显示标题 Logo](https://github.com/li88iioo/Pathfin/commit/35d4a9ef075b7687ea3da952cec8df57d6e0c885)：进入详情时直接读取并淡入 Logo，不再先显示文字再切换；没有 Logo 的媒体直接显示文字标题。
- 平滑[图片加载](https://github.com/li88iioo/Pathfin/commit/35d4a9ef075b7687ea3da952cec8df57d6e0c885)：封面、剧照与章节缩略图读取中改用随主题的浅色占位并淡入，不再闪出深色方块；缺图或[读取失败](https://github.com/li88iioo/Pathfin/commit/1bcee5330bb128886e9a535573230f2d9b0f9933)时显示缺图图标，换图后正常恢复。
- [详情简介读取完成前留空](https://github.com/li88iioo/Pathfin/commit/35d4a9ef075b7687ea3da952cec8df57d6e0c885)，不再先闪出“暂无简介”再替换为正文。
- [选集抽屉的集数格适配毛玻璃](https://github.com/li88iioo/Pathfin/commit/dc56f4128917a464f941fc8a7e5543b2b7e2204c)，与抽屉材质保持一致。

## [0.1.2] - 2026-11-06

### 优化

- [统一浮层毛玻璃](https://github.com/li88iioo/Pathfin/commit/cb78f0ddbae2b661a118631e7e16773ee6ef417b)：底部导航、菜单与抽屉改用更通透的毛玻璃并配细描边；分隔线、拖动手柄与筛选胶囊随玻璃自动调整，浅色模式下导航图标和手柄更清晰。
- [首页标题栏改用深色玻璃](https://github.com/li88iioo/Pathfin/commit/cb78f0ddbae2b661a118631e7e16773ee6ef417b)：压在展示区剧照上时不再出现浅色灰带和分界线。
- [重做下拉刷新指示器](https://github.com/li88iioo/Pathfin/commit/cb78f0ddbae2b661a118631e7e16773ee6ef417b)：玻璃圆标随下拉渐显、放大，从标题栏下方自然滑出，不再被裁切。
- [刷新失败改为浮出通知](https://github.com/li88iioo/Pathfin/commit/ffb37afc8f740d689a6f1d8add3dfb5321455808)：页面已有内容时保留内容，在底部提示并可直接重试；整页没有内容时显示带重试按钮的失败状态，表单和分区内的错误改为轻量提示条。

### 修复

- [修复直播节目单读取失败时同时显示“暂无节目信息”](https://github.com/li88iioo/Pathfin/commit/ffb37afc8f740d689a6f1d8add3dfb5321455808)，失败后可在节目单内直接重试。

## [0.1.1] - 2026-11-06

### 本次重发

- 本次基于最新代码重新发布 `0.1.1`，Android 版本码提升至 `36`，支持从正式版 `0.1.0` 或旧 `0.1.1` 覆盖升级。已安装旧 `0.1.1` 的用户请[重新下载 APK](https://github.com/li88iioo/Pathfin/releases/download/v0.1.1/Pathfin-arm64-v8a.apk)覆盖安装；同名版本不会触发应用内更新提示。

### 新增

- [首页氛围展示区](https://github.com/li88iioo/Pathfin/commit/9141f7f3702df4a704de085dda1ec5d4b91fe8b1)：展示媒体剧照、标题、观看进度和播放入口，可切换继续观看与下一集；没有观看记录时展示有剧照的最近入库媒体。

### 优化

- [调整首页媒体库入口与最近入库栏目](https://github.com/li88iioo/Pathfin/commit/ab22b5a1523bb15e02ca6de6e25d5c60856fd4f5)，强化图标辨识；[移除标题栏服务器名称](https://github.com/li88iioo/Pathfin/commit/16c91b866d5029565740598f98e9e5b4f283d4f9)，剧集和混合库统一使用竖版海报，电影库保留横版首卡。
- [连播沿用手动选择的画质](https://github.com/li88iioo/Pathfin/commit/706abf02860165f11400b3e2bec0dea37064f910)：选集或自动切集时保留当前选择，目标云盘档位不可用时向下选择可播放档位，切换失败则恢复此前选择。
- 统一毛玻璃显示，并[复用渲染样式](https://github.com/li88iioo/Pathfin/commit/151bb0df036cc8fa43593b1fa68c8f6b865a3f3b)，减少无关界面重组造成的刷新。

### 修复

- [修复标题栏毛玻璃下沿的横向接缝](https://github.com/li88iioo/Pathfin/commit/db5385a769489391aa41ecd5a1176f8a4073d9c0)：模糊遮罩和底色渐变统一平缓收尾，改善首页及列表滚动时与背景的衔接。
- [修复首页与媒体详情页标题的文字闪现和 Logo 叠加](https://github.com/li88iioo/Pathfin/commit/e983f24c1ed3bbd4f52eb6303a6ba0ca9f7c9665)：复用同一剧集的 Logo 资源，并缩短首页背景切换过渡。
- [修复首页起播时章节等详情不完整](https://github.com/li88iioo/Pathfin/commit/e679afa32291ea61d74a499bf06f1dd97fdd0805)：先解析实际播放的单集，再补齐详情，并保留续播位置。
- [稳定首页加载与大字体布局](https://github.com/li88iioo/Pathfin/commit/48392c3796c7206c66ced6a1b500f1345ae7d28e)：首轮加载保留展示区空间，空内容刷新不再反复展开占位，并修复放大字体时的文字裁切。
- [修复向 Jellyfin 报告的客户端版本](https://github.com/li88iioo/Pathfin/commit/64e883667c857a5a7ee662a8914794ae833d87ff)，随当前应用版本更新。

### 构建与依赖

- 更新 [Android Gradle 插件至 9.5.0、Haze 至 2.1.0](https://github.com/li88iioo/Pathfin/commit/fb0ce0ccb243a94fcd818fc3dca7bedfa4ce2ff9)，以及 [HarfBuzz 15.1.0、FriBidi 1.0.18、libplacebo 7.387.0](https://github.com/li88iioo/Pathfin/commit/cf473616ed797943cfef182427df30de9f5e17a2)，同步开源许可与对应源码。
- [修复 ASS 原生构建输入循环包含生成资产的问题](https://github.com/li88iioo/Pathfin/commit/0f8ee9438d844dff2687e8bb285ada706efe471e)，保持构建输入和源码归档稳定。
- 清理应用与测试编译警告，迁移[局域网发现接口](https://github.com/li88iioo/Pathfin/commit/a140f283c633c475a83eb19c38c7ab72ffafa71e)，并移除[未使用的旧字幕转码开关](https://github.com/li88iioo/Pathfin/commit/aefb4724ecc018b6ca94620b2dd450dede48ce3a)。
- [同步 ASS 实现源码锁与构建来源记录](https://github.com/li88iioo/Pathfin/commit/b0dfa7fbd5863ce7b8ddaf1f8aca41ec156e7c1d)，修复 CI 原生构建的哈希校验失败。

## [0.1.0] - 2026-10-30

### 首次发布

- Pathfin 首个公开版本：面向 Android 的原生 Jellyfin 客户端，提供媒体浏览与播放、STRM 直连和 MediaFlux 云盘画质切换。

### 本次重发

- 本次基于最新代码重新发布 `0.1.0`，Android 版本码提升至 `33`。已安装旧 `0.1.0` 的用户请[重新下载 APK](https://github.com/li88iioo/Pathfin/releases/download/v0.1.0/Pathfin-arm64-v8a.apk)覆盖安装；同名版本不会触发应用内更新提示。
- [优化直播页重复进入](https://github.com/li88iioo/Pathfin/commit/71c25b60cc36373fdc912d4679ac22403202bd6a)：同一会话内保留频道、搜索、分页和滚动位置，过期时保留已有内容刷新。
- [优化媒体库控制台进入与返回](https://github.com/li88iioo/Pathfin/commit/ba7d963b5cf102f39ce20b7b27b2f196b561c8df)：保留子页、列表和滚动位置，读取期间可切换子页，避免旧日志响应覆盖新选择。
- [单集与季详情可直达所属剧集](https://github.com/li88iioo/Pathfin/commit/33430c6e46d7f5f308842a0be2a61cbb06f12a76)：单集详情点击剧名、季详情点击标题即可打开整部剧；返回栈里已有这部剧时直接退回，不重复叠加页面。

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

[0.1.5]: https://github.com/li88iioo/Pathfin/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/li88iioo/Pathfin/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/li88iioo/Pathfin/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/li88iioo/Pathfin/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/li88iioo/Pathfin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/li88iioo/Pathfin/releases/tag/v0.1.0
