自有 24 秒 ASS 扩展夹具。视频 320×180/12fps/H.264，无音轨；1..7 秒黄色 MOVE 横移、2..22 秒青色顶部文字、2..10 秒 karaoke、12..20 秒绿色 SEEK 反向移动。

`embedded-font.mkv` 含完整 ASS header 和一个 `PathfinAssFixture.ttf` 字体附件；`animated-inline-font.ass` 包含同一个几何字体的标准 `[Fonts]` 内联编码。字体由 FontTools 按本仓库生成器自写的 5×7 方块轮廓构成，未复制上游字体。

`cjk-fallback.ass` 指定一个不存在的脚本字体，1..4 秒显示“中”、5..9 秒显示“国”，用于验证缺字时回退到系统中文字体而非方框。

所有素材从 `android/ass/tools/prepare_ass_fixtures.py` 生成。`manifest.json` 保存尺寸/时长/字节数与 SHA256；不含服务器地址、令牌、用户媒体或第三方图像，也不替项目选择许可证。
