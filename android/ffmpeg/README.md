# FFmpeg 音视频解码与 GLES 输出

模块保留 AndroidX Media3 **1.11.1** 的原始音频 renderer 和 Java decoder，修复音频 JNI 的 PCM 扩容追加、实际样本计数与初始化数据零填充，并增加 Pathfin 视频 decoder/JNI 及独立 libplacebo GLES 输出。固定提交、上游文件和本地实现文件的 SHA-256 见 `sources.lock.json`。默认仍优先 MediaCodec；软件视频输出由应用的 renderer 选择策略接入。

同一份固定 FFmpeg 9.0.2 构建 `libavcodec`、`libavutil`、`libswresample`：保留 AAC、AC3、E-AC3、TrueHD、DTS、Vorbis、Opus、AMR-NB/WB、FLAC、ALAC、G.711 A/μ-law 和 MPEG 音频层 1/2/3 共 16 个音频 decoder，增加 H.264、HEVC 与 MPEG-2 软件 decoder。HEVC 保留 Dolby Vision RPU 解析。网络、容器、滤镜、设备、缩放、外部 codec 库、GPL、nonfree 与 version3 均关闭，FFmpeg 保持 LGPL-2.1-or-later 配置。Media3 继续负责网络、解封装和播放时钟。

视频输出从固定 libplacebo、glad、fast_float 原始归档独立构建。仅启用 OpenGL/GLES 和 Dolby Vision reshaping；Vulkan、D3D11、libdovi、glslang、shaderc 和 lcms 关闭。解码后逐帧携带色彩与 Dolby Vision 元数据交给 GPU 渲染，不把 Profile 5 简单重标为普通 HEVC。完整的 Profile 7 FEL 增强层重建不在此路径支持范围，软件解码的实际分辨率和帧率上限取决于设备性能。

## 构建

需要 Python 3.12+、make、pkg-config、Android SDK、锁定 NDK 30.0.16248370 和 CMake 3.22.1。Meson、Ninja、Jinja2、MarkupSafe 的版本在锁文件中固定，由构建脚本安装到缓存内的独立 Python 环境。当前 ABI 为 arm64-v8a、最低 API 26，所有共享库支持 16 KiB 内存页。

```sh
python3 android/ffmpeg/build_native.py --sdk /path/to/Android/Sdk --jobs 2
python3 android/tools/package_source.py --native ffmpeg --output .superpowers/tmp/ffmpeg-native/release/pathfin-ffmpeg-corresponding-source.tar
```

`--sdk` 默认读取 `ANDROID_SDK_ROOT` / `ANDROID_HOME`，否则使用 `~/Android/Sdk`。`--cache` 指定源码、构建和安装缓存，默认 `.superpowers/tmp/ffmpeg-native`。可用 `--archive-cache /existing/cache/downloads` 复用已下载的源码压缩包；仍须核对固定字节数和 SHA-256，再从源码重新编译，不能复用其他播放器的原生二进制。

libplacebo 的 AVFrame 适配头还引用四个 libavformat 公共头。脚本从同一 FFmpeg 固定源码安装这些头并记录哈希，仍不构建或链接 libavformat。桥接使用 `c++_static`，并隐藏静态库导出符号，不额外打包第二份 C++ 共享运行时。

构建写出四个共享库及 `native-build.json`，核对 decoder 集合、Dolby Vision RPU、GPL/nonfree/version3 禁用状态、GLES/DOVI 后端、16 KiB 对齐和动态依赖闭包。报告记录构建源码输入、静态 libplacebo、额外公共头、工具版本和构建配方哈希。`src/main/jniLibs/` 和本机缓存不入库。

## 许可和对应源码

`package_source.py --native ffmpeg` 是许可及对应源码打包入口。先核对四份原始源码归档、锁定上游和本地实现、构建输入、实际暂存库及 NDK，再生成 `open-source-ffmpeg` 离线全文、实编译输入版权声明与唯一组件清单。glad 的生成头和 NDK 静态 C++ 运行时也纳入披露。fast_float 选择其 MIT 许可。

对应源码 tar 包含四份原始上游压缩包、完整模块源码、许可、构建配方、实际配置、glad 生成头和桥接 object。`SOURCE-INVENTORY.json` 记录所有归档文件哈希；`source-distribution.json` 将归档与实际 `native-build.json`、锁文件配对。它不为 Pathfin 项目选择许可证。

从对应源码重建时，将 `module/` 放入应用源码的 `android/ffmpeg/`，将 `tools/package_source.py` 放入 `android/tools/`，将 `upstream/` 的四份压缩包放入 `--cache/downloads/`，再执行同一构建命令。分发 APK 时附上匹配 tar 和 `.sha256`，已有归档不会被静默覆盖。

## 验证

`ExoFfmpegAudioTest` 使用自有 AC3 / E-AC3 / DTS / TrueHD 合成音轨验证实际 PCM 输出。视频路径须通过真实软件帧输出、色彩转换、首帧、暂停、跳转和释放测试，不能仅以 decoder 存在声明可播放。最终 APK 使用 `verify_release.py` 和 `verify_native_pairing.py` 核对二进制及对应源码，后者同时核对 APK 中的全部原生许可资产。
