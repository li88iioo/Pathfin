# 通用播放器静止图回归素材

`still.mp4` 完全由同目录 `generate_fixture.py` 的自有几何图案生成，未包含外部图像、用户媒体或参考 APK 资源。
320×180、12 fps、120 秒、无损 H.264 8-bit SDR、无音轨。连续帧解码像素一致，用于排除“播放时间不同”造成的假截图差异。

重新生成：`python3 generate_fixture.py`。需要可编码 H.264 的本机 FFmpeg；工具仅生成测试资产，不进入 release APK。
设备测试自行绑定回环临时端口，无需外部 fixture、账号、令牌或 ADB reverse。
