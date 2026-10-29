# ExoPlayer ASS / libass 扩展

本模块使用真实 libass 将 ASS 每一时刻的图像绘制到独立 Canvas，按实际播放时钟更新，不使用 event 起点的静态截图冒充动画。默认开关和播放器入口由 app 层管理；关闭时由 app 构造原基础 SSA 管线。

## 接线与线程

`dev.pathfin.ass.ExoAssExtension` 构造、`supportedOnDevice()`、`mediaSourceFactory(...)`、`renderersFactory(...)` 都不加载 JNI、不访问缓存、不枚举字体。仅 ARM64 原生可用；其他 ABI 应明确采用基础 SSA。`bind(ExoPlayer)`、`attach(PlayerView)`、`detach(PlayerView)`、`close()` 在 Main 调用，解绑/关闭幂等且按 view 身份隔离。

所有 Exo 分支（扩展开、关及失败回退）先对其受控 DataSource 使用 `ExoAssExtension.boundedSubtitleDataSource(delegate, exactSubtitleUris, onRejected)`。集合必须来自明确解析的外挂 SRT、VTT、ASS 配置，不能按 URL 后缀猜测。已有鉴权、TLS、重定向和请求参数原样传递。开启扩展再把其中 MIME 为 TEXT_SSA 的精确子集和同一字幕索引回读工厂传入 `extension.mediaSourceFactory(boundedDataSource, exactAssUris, wrapExtractors)`。超大输入只报拒绝加载，不触发重新打开无界基础字幕。

`onStatus(String?)` 总在 Main。`failed` 是纯原子读：不可恢复的 JNI/字体/渲染故障先置位再通知，app 必须一次性按实际当前位置重建基础 SSA，不能只改文案后继续吞字幕。正常无 ASS 为 null；实际建好 native 轨后才报告 libass 启用。源文件超限有独立固定错误，`failed` 不置位。

## 实现与输入边界

- Media3 固定 1.11.1。MKV 使用公开 `FLAG_EMIT_RAW_SUBTITLE_DATA`、`TrackOutput`、`SubtitleTranscodingExtractorOutput` 获取原始样本。附件识别来自固定 MIT 上游的少量代码，经重写移除私有字段反射。无 Media3 私有字段 keep 规则。
- 在 `NoSampleRenderer.onRendererOffsetChanged` 捕获真实偏移，每个 playback tick 按 `positionUs-offsetUs` 渲染；seek/选轨/视口变化使用代次清屏。外部完整 ASS 由同一受控 DataSource 读入；Matroska 数据用其 metadata 开始时间和 duration 前缀送入 libass。
- seek 清屏仅由 renderer 的真实位置重置及 extractor 失效触发，不使用 Main 上的通知重复清屏：Media3 同位置 seek 也通知 Main，但内部会直接跳过。诊断区分 native 已渲染与当前代次已送达 Main，暂停取样应等待后者。
- 只有真实选中的 ASS 才在单独 worker 初始化 native 和 Android 字体。未选 ASS 的 header 全 owner 合计最多 8MiB；所有轨事件/外部内容合计最多 8MiB、50,000 个唯一 ReadOrder。后切选回放已缓冲事件。超过处理预算明确回退基础字幕，不按轨分别分配 32MiB。
- 显式外部 SRT、VTT、ASS 在 DataSource.open 已知长度超过 8MiB 时、SubtitleExtractor 分配之前拒绝；未知长度读入最多 8MiB，另有 1 字节 EOF 探测。range 不能绕过文件上限。
- MKV font 单个最大 8MiB、owner 合计 32MiB，文件名禁止路径/控制符，只接受 ttf/ttc/otf/otc；读取后留在内存，没有附件解压路径。全文件 ASS 内联 `[Fonts]` 由 libass 内存提供器处理。Android 回退字体仅在 worker 读固定只读系统字体目录。不会按 ASS 字体名、图片名或 URL 请求网络。
- 仅一个 worker + 一个待提交最新 bitmap + 至多一个 Main 投递 runnable。渲染最多约 30fps，seek 等代次变化立即处理；每帧不超过 2,073,600 像素。未提交帧可 GC，已提交 bitmap 不提前 recycle。旧代次结果不能覆盖关闭字幕的 pending clear。
- Canvas 用公开 View 矩阵把视频 Surface 映射到自身坐标，监听实际布局；显示比例改变时在 Main 调用 `updateViewport()`。libass 仍在未变换的物理 Surface 尺寸渲染，Canvas 只应用一次显示变换，FILL 的拉伸及 ZOOM 的裁切与视频一致。显示比例改变不 seek、不恢复暂停、不重建字幕轨。非 ASS 字幕继续 Media3 原解析。
- 关闭不等待 Main 上的 native/文件操作。worker 依次销毁 renderer、其拥有的 tracks、library；旧 owner 不操作新 owner 的画布。

### 外挂字幕媒体源

基础字幕与 libass 使用同一个 `SubtitleMediaSourceFactory`。已知单轨格式可以立即用于选轨；只有实际选中才创建 `MaskingMediaSource` 管理的真实 Progressive period，未选中的404或慢字幕不建立连接。`SubtitleExtractor` 接收完整 Format 并发布真实 cue 结束时间，避免长字幕在随机跳转后被错误认为已经结束。视频、字幕先合并再统一裁剪，短字幕不裁掉视频时长；关闭字幕释放在途 period。

选轨或 seek 取消旧加载时，等待 worker 的中断以 `InterruptedIOException` 返回 Media3 Loader，并保留线程中断标记；取消的输入不发布基础 SSA cue、不把整个扩展标记为故障。真正的解析或 native 故障仍按原失败协议处理。

## 原生构建与来源

`python3 android/ass/build_native.py --sdk /path/to/Android/Sdk --jobs 2`，NDK 30.0.16248370、CMake 3.22.1、Android API26、ARM64。需要 Python 3.12+、make、pkg-config、autoconf、automake 与 libtool；锁定的 Meson / Ninja 安装在隔离构建环境。`--cache` 默认 `.superpowers/tmp/ass-native`，源码下载后先校验固定 SHA-256。

脚本从固定原始源码独立编译 libass、FreeType、FriBidi、HarfBuzz 和 libunibreak，将其静态链接入 `libpathfin_ass_jni.so`。字体通过内存和固定 Android 系统字体目录提供，不引入 fontconfig、网络或媒体引擎。ASS 模块唯一打包同 NDK 的 `libc++_shared.so`；音频 JNI 不依赖 C++ 运行时，不会重复打包。JNI 仅依赖该运行时及 Android 系统库，所有 ELF LOAD 对齐 >=16KiB，实际静态输入和最终库哈希记录在 `native-build.json`。

`sources.lock.json` 固定五份上游源码、版本、压缩包哈希、公开头和桥接实现。libass 为 ISC，FreeType 采用 FTL，FriBidi 为 LGPL-2.1-or-later，HarfBuzz 和 libunibreak 保留各自许可；不引入 GPL 版本的 FreeType。`vendor/libass-android` 保存附件适配源和补丁，MIT 全文在 `LICENSES/`。本模块没有替 Pathfin 项目选择许可证。

```sh
python3 android/tools/package_source.py --native ass --output .superpowers/tmp/ass-native/release/pathfin-ass-corresponding-source.tar
```

统一打包工具先核对真实源码和原生库，再生成 `open-source-ass` 许可全文、编译输入版权声明和对应源码 tar。归档包含五份原始上游压缩包、整个模块、构建配置和桥接 object；`SOURCE-INVENTORY.json` 记录全量哈希，`source-distribution.json` 配对最终二进制。重建时将 `module/` 放入应用源码的 `android/ass/`，将 `tools/package_source.py` 放入 `android/tools/`，将 `upstream/` 的压缩包放入 `--cache/downloads/`，再执行同一构建命令。分发 APK 时附上 tar 和 `.sha256`，使用 `verify_native_pairing.py --ffmpeg-source ... --ass-source ...` 核对配对。

## 验证

自有 24 秒测试视频、几何字体与 ASS 生成器：`tools/prepare_ass_fixtures.py`，输出 app `androidTest/assets/ass-extension`。不含第三方电影/音频/字体轮廓。离线许可统一由 `android/tools/package_source.py --native ass` 重建。

`ExoAssExtensionFlowTest` 验真实 MKV 字体、双位置/动画像素、seek、clipped period offset、外部内联字体、普通视频截图、未选 ASS 惰性后切选、Main 阻塞时队列有界，以及精确 late-frame/pending-clear barrier。`AssSubtitleInputLimitTest` 用 2GiB 声明和无尽未知长度输入验解析前限流、请求保留与 range 边界。另有 VM/Screen 所有权/失败恢复集成测试和模块 packet 单测。

本模块开发代理只运行独立 NDK 构建、ffprobe、源/许可 hash 核验；Gradle/R8、手机与平板实测由主线统一执行。尚未取得设备结果时，不应把源码就绪写成 GPU/渲染验证通过。
