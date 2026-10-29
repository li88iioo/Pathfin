# Pathfin 随包第三方许可

本目录是应用内「开源许可」页面的离线数据，不决定 Pathfin 项目自身的许可证。许可正文与版权声明保留上游原文；中文组件说明仅用于导航。

当前清单来自实际 `releaseRuntimeClasspath` 的二进制产物，而不是依赖树中的所有平台、约束或元数据节点。实际产物去重后逐一提取许可；组件数量和精确版本以 `manifest.json` 与 `runtime-artifacts.json` 为准。原生组件的来源与许可由各自独立目录记录。

应用内许可页面合并读取 `open-source`、`open-source-ass`、`open-source-ffmpeg` 与 `open-source-artwork`。图形素材目录记录 Jellyfin 标志的固定来源、格式转换说明和 CC BY-SA 4.0 许可全文，不计入 Maven 组件数量。本地 `:ffmpeg` 模块记录固定的 Media3 音频 Java/JNI 源码及独立音频解码库；`:ass` 模块记录 libass、字体整形组件与原生运行时。两个原生模块各自维护构建输入、二进制哈希、许可全文和对应源码归档，不依赖其他播放器。本地模块不是 Maven 坐标，因此不计入本目录的 Maven 组件数量。

## 内容与证据

- `manifest.json`：页面读取的组件、精确版本、许可声明、上游链接、正文引用、证据哈希和待确认项。
- `runtime-artifacts.json`：去重后的实际运行时产物名称、坐标、类型、SHA-256；不含本机绝对路径。
- `texts/`：按内容 SHA-256 去重的完整许可/声明原文。包含实际 AAR/JAR 与其内嵌 `classes.jar`、`libs/*.jar` 中的 LICENSE/NOTICE；同一组件中相同原文只显示一次。
- `evidence/poms/`：每个运行时组件的精确版本原始 POM，以及实际用到的父 POM。Guava、failureaccess、listenablefuture 的许可由各自精确父 POM 继承，未按 group 名称推断。
- `upstream-evidence.json` 与 `evidence/upstream/`：Apache 官方全文与已核对的精确版本上游补充文本。
- `evidence/upstream-checks.json`：补充上游路径的获取结果。404 仅表示该路径不存在，不代表整个项目没有其他声明；未将 Kotlin Compiler 的 NOTICE 错配给运行时 stdlib，也未将 Javadoc 工具的许可纳入运行时清单。
- `unresolved.json`：当前无未识别的 POM 许可或缺失的完整正文。新增或未知许可会使生成器返回失败，不能静默发布。

每个二进制、原始 POM、补充法律文本和正文都记录 SHA-256。运行时正文只从 APK 资产读取，并在展示前核验哈希；页面不联网抓取许可。点击上游链接才会打开浏览器。

## 从依赖重新生成

以下命令从仓库根目录执行，需要项目所用 JDK/Android SDK、Gradle Wrapper，以及已解析的实际 Release 依赖。生成器仅使用 Python 3 标准库。不要用 `debugRuntimeClasspath`、源码包、Javadoc 包或别的版本 POM 代替 Release 证据。

```sh
python3 android/tools/generate_open_source_notices.py --print-gradle-init > /tmp/pathfin-open-source.init.gradle
(cd android && ./gradlew -I /tmp/pathfin-open-source.init.gradle :app:pathfinReleaseArtifactPaths)
python3 android/tools/generate_open_source_notices.py \
  --artifacts android/app/build/reports/open-source/release-artifacts.json --fetch
python3 android/tools/generate_open_source_notices.py \
  --artifacts android/app/build/reports/open-source/release-artifacts.json --check
```

`--fetch` 只从 Google Maven/Maven Central 获取缺失的精确 POM；已核对的上游正文输入不会自动替换。依赖升级时还须复核适用的版权/NOTICE 补充并更新 `upstream-evidence.json`。新组件不在既有补充正文适用范围内时，不会自动套用旧版本上游声明。

不升级依赖、仅验证当前已提交快照时，可在已有相同 Gradle 二进制缓存的环境运行：

```sh
python3 android/tools/generate_open_source_notices.py --check
```

此模式检查快照、二进制和上游许可证据的一致性，不会执行 Gradle，因此不能代替依赖升级后的重新导出。`--gradle-cache` 可指定缓存根目录。`--check` 不修改文件。

## 边界

当前许可识别覆盖上述实际运行时二进制、它们内附的法律文本、精确 POM/继承声明和列明的上游补充证据；不把构建工具、测试依赖或未打入应用的参考程序归为运行时组件。这份可复现清单不声称已审计每个上游源码仓库的所有历史文件。新增直接复制/改编的第三方代码或原生库，需要明确版本、来源、构建选项和许可后另行纳入清单；不能仅靠 Maven 清单覆盖。
