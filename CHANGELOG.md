# 更新日志

记录 Pathfin 各版本面向用户的变更，最新版本在前。GitHub Release 和应用内更新说明共用对应版本的正文。

首版内容涵盖从[初始源码](https://github.com/li88iioo/Pathfin/commit/ed01b3e91565d31fcf736ed4d010eda69132050d)到 `v0.1.0` 标签的全部提交。本地测试 build 不单独列为正式版本。

## [0.1.9] - 2026-11-27

### 优化与修复

- 修复横屏时首页、直播等页面在摄像头一侧出现空白条的问题。背景和毛玻璃延伸至屏幕边缘，频道、表单和操作按钮避开摄像头。[首页](https://github.com/li88iioo/Pathfin/commit/72142ba45961e738cb9848adc9ba648f856eb238) · [直播及其他页面](https://github.com/li88iioo/Pathfin/commit/ff047757548785e06caf8ad965c96ebcc7a09b5c)
- 更新 ASS 字幕使用的 HarfBuzz 至 15.2.0、libunibreak 至 8.0.1，改善播放含异常内嵌字体的影片时的稳定性。[字幕组件](https://github.com/li88iioo/Pathfin/commit/a4e6e765de94114304312cdb8e7cf4003df5ffc3)
- ARM64 音视频软解使用的 FFmpeg 升级至 9.1，同步更新应用内开源许可和随版本提供的对应源码。[播放组件](https://github.com/li88iioo/Pathfin/commit/c54d1cf754128424538d0ff4d21b14ba474fcc78)
- 媒体库搜索清空后会取消旧查询，直播搜索取消后回到原滚动位置。编辑媒体元数据时，后台刷新会保留尚未保存的输入。[搜索](https://github.com/li88iioo/Pathfin/commit/1fbe2aa6000607a784cad5ea926e0ea81646b25a) · [表单编辑](https://github.com/li88iioo/Pathfin/commit/a4b393d29b287f6b482bbe514ccd344e101902dc)

## [0.1.8] - 2026-11-23

### 新增

- ARM64 设备可在手机上软解 MP4、M4V 和 MOV 中的 VVC（H.266）原画。MKV 和 MPEG-TS 中的 VVC 仍可通过 Jellyfin 转码播放。[VVC 播放支持](https://github.com/li88iioo/Pathfin/commit/295deb9f279cd66447abb252b616c56746188385)
- 播放器的「更多 → 媒体信息」显示当前使用硬解、系统软解或 FFmpeg 软解。VVC 软解在高于 1× 的倍速下出现持续掉帧时，播放器会短暂提示调回 1×。[解码信息与倍速提示](https://github.com/li88iioo/Pathfin/commit/6fa43f1c1ee4557b9a281ab04cd5cc10f7446d64)

### 优化与修复

- 快进和快退箭头内显示设置的跳转秒数（5–60 秒）。控制按钮和双击画面共用这一设置，双击提示也显示相同秒数。[跳转图标](https://github.com/li88iioo/Pathfin/commit/38e59f89625bd013f854fa5514173ce112296acc)
- 修正播放器进度圆点与轨道的垂直对齐，拖动放大时圆点也保持居中。[进度条](https://github.com/li88iioo/Pathfin/commit/69e8ad27508c19695fa8dca261ea2ee87f64002f)
- 播放设置补充了默认、硬件优先、软件优先三种音频解码模式的尝试顺序，并注明下次播放生效。[音频解码设置](https://github.com/li88iioo/Pathfin/commit/bffe72c7de93d57d275f0c9216853980fc255f9c)
- 项目主页新增 8 张可点开查看原图的实机截图，涵盖首页、剧集详情、横屏选集、直播、搜索、收藏和设置。[界面预览](https://github.com/li88iioo/Pathfin/blob/e99ebd7d73938d914692948912489d7cac3e4895/README.zh-CN.md#界面预览)

## [0.1.7] - 2026-11-18

### 新增

- 播放器「更多」中的音量增益已并入画面右侧的音量手势。系统音量达到 100% 后继续上滑进入琥珀色增益区，最高可调至 200%。增益随手势实时变化，松手保存，取消手势可恢复原值。音频直通时，手势只调系统媒体音量。[音量手势](https://github.com/li88iioo/Pathfin/commit/402e0f99dcdce6bdae1740f273b08532840fb9b9)
- ARM64 设备新增 MPEG-4 Part 2（含 Xvid／DivX）、微软 MPEG-4 v2／v3、H.263 以及 MKV 中标记为 WVC1 的 VC-1 Advanced Profile 软件补解。能否直接播放取决于文件封装与轨道格式。[旧视频格式支持](https://github.com/li88iioo/Pathfin/commit/df5a2bf04a71720c161f4ab9145d1a8b376094e0)
- Jellyfin 提供独立地址时可选用外挂 PGS（.sup）字幕，跳转时同步更新字幕。MKV 内嵌 VobSub 字幕可由手机直接渲染，减少对服务器烧录的依赖。[位图字幕](https://github.com/li88iioo/Pathfin/commit/2ee773c85d4e1fcaf16abd0e5c03b1382389ccf2)

### 优化

- 手机扬声器播放多声道片源时混合为立体声，改善对白偏轻的问题。软件增益按等分贝间隔调整并限制峰值，实际响度提升取决于片源。支持直通的外接设备可继续接收原始编码音频。[扬声器混音](https://github.com/li88iioo/Pathfin/commit/bd8fa87c421bedc8e16370357fd540cda206fedf) · [增益曲线](https://github.com/li88iioo/Pathfin/commit/1df9f5f8dc602264c4f9cf384e76b39db7eab266)
- 确认识别结果后立即返回，服务器在后台应用元数据。成功后自动刷新详情，失败时提示原因。同一条目应用完成后可再次提交结果。[后台应用识别结果](https://github.com/li88iioo/Pathfin/commit/cc4234925477519ee74861c7336624ab95bd3795)

### 修复

- 扩大杜比视界原画兼容范围。兼容的渐进式 P7 片源可播放 HDR10 基础层，P7 FEL 的软解回退只使用基础层。P4／P8、P9 和基础层色彩兼容的 P10 可分别尝试 HEVC、H.264 和 AV1 基础层。[P8 起播修复](https://github.com/li88iioo/Pathfin/commit/8bfff89f24b716039630e57bbb8d2452a6e439b2) · [基础层兼容](https://github.com/li88iioo/Pathfin/commit/df5a2bf04a71720c161f4ab9145d1a8b376094e0)
- 通过 Jellyfin 播放蓝光 M2TS 或 ASF／WMV 时请求兼容流。明确选择 STRM 原始地址时，遇到无法直读的封装会提示改用服务器播放。[封装兼容](https://github.com/li88iioo/Pathfin/commit/8de95feb4e2aef8fc71cf12eecec57ef79ce2f95)

## [0.1.6] - 2026-11-14

### 新增

- [直播电视支持分组与全局搜索](https://github.com/li88iioo/Pathfin/commit/44526cebda46fe32edd06c958e145a2f458e685e)：从调谐器 M3U 读取分组，默认“全部”标签，可左右滑动切换；直播电视进入底栏第二位（可在设置中隐藏），全局搜索与收藏可找到频道和节目；播放返回后保留分组与频道位置。

### 优化

- [直播频道改回自适应列数的台标网格](https://github.com/li88iioo/Pathfin/commit/44526cebda46fe32edd06c958e145a2f458e685e)：搜索结果从顶部显示，取消搜索立即回到原位；没有上次观看时不再留空页头，搜索入口并入分组行。
- [直播固定 1× 播放](https://github.com/li88iioo/Pathfin/commit/0f9487d6eb25da2fd95aed6f06dd3bd5bc9777d5)：点播记住的倍速不再带入直播，直播播放器不再提供倍速与画质调节；[流内没有字幕轨时隐藏字幕入口](https://github.com/li88iioo/Pathfin/commit/a044e14fb14cd235a7f27b79ed9c3d5fff975694)，也不提供本机导入与在线搜索。
- 更流畅：[首页滚动时页头不再整体重绘](https://github.com/li88iioo/Pathfin/commit/b8a05802ae5dbd99af08e3d1a8f871ef8ad43757)；[批量标记已播放或未播放](https://github.com/li88iioo/Pathfin/commit/ab16c9136c6f2f09b77b3b48911920b61c458852)整批只保存与刷新一次；播放器[停止无效的位置轮询](https://github.com/li88iioo/Pathfin/commit/389cca65eabec5c82251f8efbe86276b419b33c1)；[下载进度](https://github.com/li88iioo/Pathfin/commit/4d1666a27c6013528a5f31aab85a421748b03e50)与[投屏](https://github.com/li88iioo/Pathfin/commit/6770363bf63737991110fdfb82a9aaef97a420ec)不再反复解密账号列表。

### 修复

- [修复直连直播部分源无法播放](https://github.com/li88iioo/Pathfin/commit/d891fa8b5b32485ebcc02cb4193c9946968f22aa)：支持省略 .m3u8 后缀的 HLS 地址与 HTTPS 跳转到 HTTP 的源；经公网连接服务器时，内网直播源改由服务器代拉。
- [修复从直播、搜索或收藏返回首页时顶部闪白](https://github.com/li88iioo/Pathfin/commit/44526cebda46fe32edd06c958e145a2f458e685e)。
- [单条服务器资料损坏不再清空整个服务器列表](https://github.com/li88iioo/Pathfin/commit/4e3cc83fd955721dbac9ab54232bab170d2f413d)，多处同时保存账号时也不再互相覆盖。
- [关闭播放器后立即打开另一部详情](https://github.com/li88iioo/Pathfin/commit/549b83b807ce105e4fe0928c45537f71b15f521d)，详情不再缺少演职员、片源等内容，观看进度也能正常写回列表。
- [字幕回退、解码回退与切换画质后保留播放现场](https://github.com/li88iioo/Pathfin/commit/63b405d945d08653b68f577a2e3bfad276193ac5)：锁屏、仅音频模式与音量增益不再丢失，片尾跳过状态不再重置。
- 直播分组[读到空分组时清除旧标签](https://github.com/li88iioo/Pathfin/commit/08edd133dbb142dc7bab5c4e8d06c7b9a3ad4370)；删除账号时[清理不再使用的直播缓存](https://github.com/li88iioo/Pathfin/commit/03279af40677c113a7a2e3c90a3e2f3b20131a4f)，并[及时取消该账号的下载](https://github.com/li88iioo/Pathfin/commit/5963021f9fac46ecc6c6953b725ad105fdd6f056)。
- [下载重试任务不会被迟到的删除误删](https://github.com/li88iioo/Pathfin/commit/bc8f956e4934ce33317ccedfa2c5f88088567f82)。
- 投屏[停止电视失败时仍结束服务器会话](https://github.com/li88iioo/Pathfin/commit/710e987c755ec80fe7066ff1858723f8ea5a6252)，[重复停止返回一致结果](https://github.com/li88iioo/Pathfin/commit/7845d041defd96d913edf336787afd72978afd99)。
- [已停用的账号不再能管理字幕](https://github.com/li88iioo/Pathfin/commit/0e18151cce8303f10de9c8e509e99197ff1e0718)。
- [管理页切换服务器地址时保留编辑中的元数据与识别内容](https://github.com/li88iioo/Pathfin/commit/21c5a212a42068fff8ac77695f5751eae921b7b2)。
- [系统单色主题下壁纸取色改用黑白](https://github.com/li88iioo/Pathfin/commit/7878f7c14cb1a3a3100a40123c4bda81946a259d)：与系统及其他应用一致，不再退回默认蓝。
- [播放器标题只在文字范围内打开选集](https://github.com/li88iioo/Pathfin/commit/7698381c65161fc5ce5cb00c7e14b5f8da3679b1)，右侧空白不再误触。

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

[0.1.9]: https://github.com/li88iioo/Pathfin/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/li88iioo/Pathfin/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/li88iioo/Pathfin/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/li88iioo/Pathfin/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/li88iioo/Pathfin/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/li88iioo/Pathfin/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/li88iioo/Pathfin/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/li88iioo/Pathfin/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/li88iioo/Pathfin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/li88iioo/Pathfin/releases/tag/v0.1.0
