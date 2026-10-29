# 音频扩展测试素材

`generate_fixture.py` 用本机 FFmpeg 生成两秒、48 kHz、双声道的 440 Hz 正弦波，分别编码为 AC-3、E-AC-3、DTS、TrueHD；没有下载或复制任何影视音频。

`.packets` 为原始解码访问单元序列，每条是小端 `uint32 size + int64 ptsUs + bytes`，用于验证真实 JNI 输出非静音 PCM 和 seek 后的 decoder reset。`.mka` 用于验证实际 ExoPlayer 音轨选择与 AudioTrack 输出。`manifest.json` 记录哈希和宿主解码结果；宿主结果只证明测试媒体有效，不代替 Android 真机测试。

运行 `ExoFfmpegAudioTest` 验证系统没有对应 decoder 时仍能实播；额外验证软件优先设置和不支持音轨提示。
