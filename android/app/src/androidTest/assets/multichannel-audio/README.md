# 自有多声道音频夹具

由 `python3 android/tools/prepare_multichannel_audio_fixture.py` 使用本机 FFmpeg 合成；没有下载或读取影视音频。加 `--verify` 可只检查矩阵、文件大小和 SHA-256。

实际矩阵为 AC-3 / E-AC-3 / DTS 5.1 @ 48 kHz、TrueHD 5.1 @ 48 / 96 kHz、FLAC 7.1 @ 48 / 96 kHz。每段两秒，各声道分别使用 220 / 330 / 440 / 55 / 550 / 660 / 770 / 880 Hz；LFE 固定为 55 Hz。生成过程核对编码后的真实声道数、采样率，以及解码后每声道的峰值、均方根和独有频率分量。

本机 FFmpeg TrueHD 编码器最高支持 5.1(side)，因此没有 TrueHD 7.1 自有夹具。本矩阵不声称覆盖该格式，也没有通过改标签伪造声道。

`.packets` 每条为小端 `uint32 size + int64 ptsUs + bytes`，用于真实 JNI 解码；FLAC 的 `.init` 保存 `fLaC` 标记和 STREAMINFO 初始化数据。`.mka` 用于真实 ExoPlayer → 默认 AudioSink → AudioTrack 实播。`manifest.json` 记录宿主完整解码帧数、声道信号、文件大小和哈希；宿主校验不代替 Android 真机结果。

`ExoFfmpegAudioTest` 对七组夹具分别验证 16-bit / float PCM、每个声道非静音及频率归属、完整 EOS、flush 后重解和 PCM 一致性。实播使用设备当前路由测试七组，并额外请求内置扬声器测试 TrueHD 5.1 @ 48 kHz 与 FLAC 7.1 @ 96 kHz；只静音当前测试播放器，不更改全局音量、蓝牙或路由。

实播结果写入目标测试应用的 `cacheDir/audio-output-audit.json`，记录实际 `AudioTrack.routedDevice.type`、输出声道、采样率、播放头推进和 EOS，不记录设备名称或地址。无法读取实际路由、请求扬声器未被采用、声道/采样率故障均不算验证成功。USB、蓝牙的覆盖以报告中实际观察到的设备类型为准。
