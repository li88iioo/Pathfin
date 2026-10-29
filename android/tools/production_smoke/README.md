# 正式 ARM64 APK 的独立 smoke

这个平台 Java Instrumentation 检查实际安装、R8 压缩并签名的 `dev.pathfin.android`，不链接 AndroidX、应用类或混淆名称，不更改应用 Gradle/Manifest。工具 APK 必须与正式 APK 使用相同证书，系统仍执行正常 Instrumentation 身份验证。

工具绑定构建时 `--target-apk` 明确指定的非 debuggable 正式包；运行前核对版本与完整 APK 的 SHA-256，同版本号的另一个构建也会被拒绝。仅支持独立 APK、ARM64 设备、固定 `127.0.0.1:8101/jellyfin` 合成服务和 `pathfin/pathfin` 合成账号。没有自定义 target、地址、账号或令牌参数。维护者必须确认正式包是本次新装；启动后若不是空账号页，工具拒绝继续。它不会调用 `pm clear`、卸载目标、访问 `.debug`/`.verification`，也不会读取应用会话、偏好或数据库。

每次正式 APK 内容变化都需要重新构建工具；设备 smoke 由维护者单独执行，离线检查不代表已通过真机验收。

工具通过中文可访问文本定位控件，测试设备的系统或 Pathfin 应用语言必须为中文。启动后会在任何界面输入前检查实际 Activity 语言；非中文会明确失败，不会自动改语言。此限制仅针对验收工具，不是应用的语言支持范围。

测试仅让当前目标 Activity 的窗口临时显示在锁屏上，结束恢复窗口参数；不解锁、不请求解除 Keyguard、不改系统安全设置。这可验收锁屏上的实际视频和系统 BACK，不能代替解锁设备上的 PiP、预测性边缘手势或完整日常交互验收。

## 构建

需要 JDK17、SDK API37 和 build-tools37.0.0；使用 `javac → d8 → aapt2 → zipalign`，没有 Gradle 依赖或网络下载。仓库根目录运行：

```bash
python3 android/tools/production_smoke/build.py \
  --sdk "$ANDROID_SDK_ROOT" \
  --build-tools "$ANDROID_SDK_ROOT/build-tools/37.0.0" \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-37.0/android.jar" \
  --target-apk /absolute/path/to/Pathfin-0.1.0.apk
```

默认只构建 unsigned/aligned APK，输出在 `.superpowers/tmp/production-smoke/`。构建报告记录目标版本、SHA-256 与证书，并明确 `deviceRun: not run by build.py`。正式签名由维护者显式执行；密码仅从环境变量获取，不在命令参数或源码内保存：

```bash
# 通过本地安全方式设置 PATHFIN_SMOKE_STORE_PASSWORD 和 PATHFIN_SMOKE_KEY_PASSWORD。
# 不开启 shell xtrace，不在日志里输出这些变量。
python3 android/tools/production_smoke/build.py \
  --sdk "$ANDROID_SDK_ROOT" \
  --build-tools "$ANDROID_SDK_ROOT/build-tools/37.0.0" \
  --target-apk /absolute/path/to/Pathfin-0.1.0.apk \
  --keystore /outside/repository/production.jks \
  --key-alias YOUR_PRODUCTION_ALIAS
```

工具不读取 `release.properties`。签名前检查目标包名、版本、release 标记和 ARM64 音频/字幕扩展，签名后验证工具和目标证书完全一致。也可由已有签名流水线对 aligned APK 签名；安装前仍应自行核对两包证书。

## 由维护者执行设备验收

只在确认没有历史正式包的测试设备上安装本轮正式 APK。若设备已有 `dev.pathfin.android`，先停止并确认归属；不要覆盖、卸载或清理用户已存在的正式包来运行此工具。安装工具不需要、更不能重新签名正式 APK。

先在主机启动独立合成服务（不要和其它回归同时操作同一夹具）：

```bash
python3 android/tools/prepare_multitrack_fixture.py \
  --api-port 8101 --media-port 8102
```

另一个终端，明确选择测试设备。以下命令由负责设备的维护者执行：

```bash
adb -s DEVICE_SERIAL shell pm path dev.pathfin.android
# 首次检查应没有任何 package 路径；否则停止，不要自动卸载/清理。
adb -s DEVICE_SERIAL install /absolute/path/to/Pathfin-0.1.0.apk
adb -s DEVICE_SERIAL reverse tcp:8101 tcp:8101
adb -s DEVICE_SERIAL reverse tcp:8102 tcp:8102
adb -s DEVICE_SERIAL install -t .superpowers/tmp/production-smoke/production-smoke-signed.apk
adb -s DEVICE_SERIAL shell am instrument -w \
  -e acknowledgeFreshProductionInstall true \
  -e runId production_smoke_20261024_A \
  dev.pathfin.android.production.smoke/dev.pathfin.smoke.ProductionSmokeInstrumentation
```

成功必须同时包含 `RESULT: PASS`、`passed=true` 和 `syntheticAccountRemoved=true`，不能只按命令进程退出码判断。运行大约需要 30–90 秒，失败不会被标记跳过。请保留完整 Instrumentation 输出和夹具 `requests.jsonl` 作为证据，不要向公开报告添加个人服务器信息。

同一设备上不要并行运行其它 Instrumentation、`uiautomator dump` 或依赖 UiAutomation 的设备工具；系统只有一条 UI 自动化服务。初始连接失败时，工具会保留平台原因链与连接状态，过滤 URL/凭据。API37 的平台实现可能在注册异常后永久停留在 `CONNECTING`，连正常 `Instrumentation.finish()` 都再次抛错；工具仅对这一个已失败状态先输出明确 FAIL 再结束本次自身 PID，由系统回收 Binder，不循环重连或更换 flags。这仍属于工具初始化失败，没有进行登录，不能算生产播放失败或成功验收。重新运行前应先根据初始原因排除冲突并重新签名修改后的工具，不能沿用旧 signed APK。

## 已有自动化服务时：外部真实输入模式

若设备已有用户自动化服务占用 UiAutomation，不应停止该服务。`externalInput=true` 分支完全不连接 UiAutomation：它通过自己的 `WindowInspector` 焦点窗口和公开 `View.onProvideVirtualStructure` 读取实际 Compose 节点，由维护者运行的 `external_runner.py` 对节点真实 bounds 执行 `adb shell input tap`、实际系统 BACK 和 `screencap`。文本先由真实 tap 聚焦，再经该编辑器公开的 `View.onCreateInputConnection` / `InputConnection.commitText` 输入固定合成值，独立完整回读必须一致；不使用会受中文 IME 合成改写的 `adb input text`，不改用户键盘或剪贴板。只读取当前新装正式包的窗口，不使用 `uiautomator dump`、应用混淆类名或 SessionViewModel 替身。

可以先运行仅节点探测，必须收到空账号页和添加按钮的真实 bounds；它不会登录，并明确输出 `RESULT: SNAPSHOT_ONLY`、`snapshotCaptured=true`、`passed=false`：

```bash
adb -s DEVICE_SERIAL shell am instrument -w \
  -e acknowledgeFreshProductionInstall true \
  -e runId production_probe_20261024_A \
  -e externalInput true -e snapshotOnly true \
  dev.pathfin.android.production.smoke/dev.pathfin.smoke.ProductionSmokeInstrumentation
```

完整模式使用已经重新签名安装的当前工具 APK，继续复用固定的 8101/8102 reverse 与合成夹具：

```bash
python3 android/tools/production_smoke/external_runner.py \
  --serial DEVICE_SERIAL \
  --acknowledge-fresh-install \
  --run-id production_smoke_20261024_external_A \
  --output .superpowers/tmp/production-smoke-external
```

Runner 不安装、签名、清理应用数据或停止用户服务。它显式选择设备，随机生成只在本次内存中使用的 256bit token；动态 ack 接收器限制到目标 applicationId、发送者 DUMP 权限、当前阶段和一次性 nonce。系统提供 sender UID 时进一步限定 shell/root，未知 sender identity 时仍由系统发送权限与秘密 token 验证。文本命令仅限 LOGIN 阶段四个固定字段，实际文本由工具内的合成常量与本次唯一显示名称决定；不接受任意地址、文本或控件。窗口及具体字段均必须已聚焦、启用，密码原文不输出。ack 只允许测试继续等待真实视频/界面，不执行播放器回调或构造播放状态。

每次输入前检查本包真实窗口焦点。字段切换短暂失焦时，最多等待 8 秒同一目标恢复焦点，期间只请求快照，绝不发送触摸或文本；超时保存自身窗口的公开 class/尺寸/焦点诊断。本轮合成测试窗口临时使用 `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`，结束恢复原值，避免用户密码管理器夺取焦点；不修改系统 Autofill 服务配置。截图、初次空账号页、合成详情、播放器返回后的详情及清理后空账号页保存在输出目录。`runner-result.json` 的 `errors` 保存失败阶段、异常类型、原因和最后动作的节点 ID/启用状态/实际 bounds，便于定位工具失败。工具独立检查 Surface PixelCopy 的实际非黑、变帧、真实 fixture 进度、同 Activity/task 返回和每个会话单次 stop。清理同样经过唯一合成名称的真实删除对话框。最终必须同时满足完整 PASS、`passed=true` 和 `syntheticAccountRemoved=true`；外部 runner 结束码不能取代日志证据。

启动 ExoPlayer 后还必须先读到播放器独有的“画中画”节点并保存实际系统截图，再允许视频校验继续。视频校验每阶段最多输出四份周期诊断及一份失败末态，包含实际 Surface 类/尺寸/可见性/有效性、PixelCopy 返回码、非黑/彩色样本比例与量化颜色数。原始图像阈值不因诊断而改变。失败时最多导出一张 320×180 的实际合成视频 PNG（上限 256KiB），runner 从 `sendStatus` 验证尺寸与格式后保存 `video-frame-failure-exo.png`；没有可读 Surface/PixelCopy 失败时不伪造图片。此处只允许本工具的自制固定夹具，不用于采集用户媒体。

采样使用 `PixelCopy.request(SurfaceView, Rect, …)`：Rect 由公开 `SurfaceHolder.getSurfaceFrame()` 的实际 buffer 尺寸及固定夹具的 320×180、方形像素、无旋转、默认居中 FIT 合同计算。例如竖屏 1280×2772 的 source Rect 为 `[0, 1026, 1280, 1746]`；横屏 2772×1280 为 `[248, 0, 2523, 1280]`，完整 1920×1080 则不裁剪。该范围与像素亮度无关，没有搜索非黑区域，也不依据是否通过阈值调整裁剪。诊断同时保存 `surfaceFrame`、`surfaceBufferSize`、`sourceRect`、`sourceRectBasis`。原有非黑 >40%、彩色 >10%、量化颜色数 >16 及移动差 >2 的阈值不变。工具不支持任意媒体比例、旋转或手动改过 FIT 的播放状态。

几何 helper 可使用纯 JDK 离线验证，覆盖横竖屏、正方形、完整 16:9、奇数尺寸和一万组有界随机尺寸：

```bash
mkdir -p .superpowers/tmp/production-smoke-geometry
javac --release 8 -encoding UTF-8 -d .superpowers/tmp/production-smoke-geometry \
  android/tools/production_smoke/src/dev/pathfin/smoke/FixtureVideoGeometry.java \
  android/tools/production_smoke/tests/FixtureVideoGeometryTest.java
java -cp .superpowers/tmp/production-smoke-geometry dev.pathfin.smoke.FixtureVideoGeometryTest
```

公开 ViewStructure 回调适配器针对 API29+ 编译，API37 设备的空账号节点导出已实测；其它系统版本尚未宣称全部兼容。输入流程失败、进程被系统杀死或 runner 超时时仍需检查本次合成账号是否残留，不能以清数据掩盖。普通 UiAutomation 模式继续保留，外部模式不表示全局自动化服务的冲突已被修复。

## 检查内容与清理

1. 校验固定夹具身份及唯一合成令牌；通过真实字段输入连接服务器，不直接写入 SessionStore。
2. 打开合成媒体详情，明确选择“从头播放”。PixelCopy 从实际 SurfaceView 采样，检查非黑、多色、自制测试图及前后帧变化；要求真实进度上报前进。
3. 注入实际系统 `KEYCODE_BACK DOWN/UP`，要求同一 Activity/task 留在合成详情，每个会话仅 `start=1 / stop=1`。
4. 登录完成以头像“切换服务器”入口出现为依据；头像只用于切服。清理在 `finally` 中连续执行真实系统返回，直到外层服务器列表出现本次唯一 `Production smoke <runId>` 账号的操作入口，再仅删除该账号。确认文字必须包含该唯一名称，最后必须回到空账号页。任意清理失败都会使整次 FAIL，并明确提示维护者从界面移除该合成账号；不会以清数据方式掩盖失败。

工具失败后仍应核对 `CLEANUP PASS`；若进程被系统终止，`finally` 无法保证运行，维护者必须从正式应用首页系统返回至外层服务器列表，删除本次合成账号。测试结束可单独卸载工具 APK `dev.pathfin.android.production.smoke`；目标正式应用保留且应无测试登录。这里不提供自动卸载命令，避免误操作目标。

该 smoke 不替代生产签名、许可/对应源码、16KiB、正式包资产隔离门禁，也不验证实体电视、所有系统版本、所有字幕/DRM 或任意远程用户服务器。首次落盘只完成工具编译，实际设备结果需单独记录。
