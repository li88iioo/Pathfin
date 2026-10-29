package dev.pathfin.smoke;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 平台 API 驱动实际 R8 正式包；不链接/反射应用类，不改偏好/数据库/会话文件。
 * 只有固定回环合成服务可登录。所有输入、账号删除和返回均经过公开界面。
 */
public final class ProductionSmokeInstrumentation extends Instrumentation {
    private static final String TARGET = "dev.pathfin.android";
    private static final String BASE = "http://127.0.0.1:8101/jellyfin";
    private static final String MOVIE = "Pathfin · 合成双音轨与字幕";
    private static final int WINDOW_TEST_FLAGS = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    private Bundle arguments;
    private UiAutomation ui;
    private int previousAccessibilityFlags;
    private volatile Activity activity;
    private final Map<Activity, Integer> originalFlags = new HashMap<>();
    private final List<String> ownedSessions = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    private String displayName;
    private boolean emptyInstallConfirmed;
    private boolean accountSubmitted;
    private boolean accountRemoved;
    private volatile boolean windowHookEnabled = true;
    private boolean automationConnectionFailed;
    private boolean externalInput;
    private boolean snapshotOnly;
    private ExternalInputControl externalControl;
    private String captureStage = "UNSET";
    private String cleanupStage = "NOT_STARTED";
    private int captureAttempts;
    private int captureReports;
    private long captureStarted;
    private long lastCaptureReportAt;
    private JSONObject lastCaptureDiagnostic;
    private final Set<String> exportedVideoFrames = new HashSet<>();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    // 仅当前测试启动的目标窗口临时显示在锁屏上；不请求解锁、不改 Manifest。
    @Override public void callActivityOnCreate(Activity host, Bundle state) {
        if (windowHookEnabled && TARGET.equals(host.getPackageName())) configureTestWindow(host);
        super.callActivityOnCreate(host, state);
    }

    @Override public void callActivityOnResume(Activity host) {
        if (windowHookEnabled && TARGET.equals(host.getPackageName())) configureTestWindow(host);
        super.callActivityOnResume(host);
    }

    private void configureTestWindow(Activity host) {
        activity = host;
        if (!originalFlags.containsKey(host)) originalFlags.put(host, host.getWindow().getAttributes().flags);
        host.getWindow().addFlags(WINDOW_TEST_FLAGS);
        if (Build.VERSION.SDK_INT >= 27) {
            host.setShowWhenLocked(true);
            host.setTurnScreenOn(true);
        }
    }

    @Override public void onStart() {
        super.onStart();
        Throwable failure = null;
        boolean lockedBefore = false;
        try {
            preflight();
            lockedBefore = getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked();
            line("START target=" + TARGET + " version=" + SmokeTarget.VERSION_NAME
                    + " code=" + SmokeTarget.VERSION_CODE + " sha256=" + SmokeTarget.SHA256 + " locked=" + lockedBefore);
            Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(TARGET);
            require(launch != null && TARGET.equals(launch.getComponent().getPackageName()), "没有正式包启动入口");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity = startActivitySync(launch);
            require("zh".equals(onMain(() -> activity.getResources().getConfiguration().getLocales().get(0).getLanguage())),
                    "本工具使用中文界面定位控件；请先将自有测试设备的系统或应用语言设为中文，再运行验收");
            if (externalInput) {
                AtomicReference<JSONObject> snapshot = new AtomicReference<>();
                await("公开 ViewStructure 必须实际读到空账号页", 15000, () -> {
                    JSONObject current = onMain(() -> TargetViewStructure.snapshot(activity));
                    snapshot.set(current);
                    return current.optBoolean("focused") && isEmptyInstall(current);
                });
                emptyInstallConfirmed = true;
                if (snapshotOnly) {
                    externalMessage(snapshot.get());
                    line("SNAPSHOT_ONLY: 空账号页节点已由自身公开 ViewStructure 读取，未连接 UiAutomation/未登录");
                    return;
                }
                externalControl = new ExternalInputControl(getTargetContext(), arguments.getString("controlToken"),
                        () -> onMain(() -> TargetViewStructure.snapshot(activity)),
                        (label, expected, commit) -> onMain(() -> TargetViewStructure.input(activity, label, expected, commit)),
                        this::externalMessage);
                accountSubmitted = true;
                externalPhase("LOGIN");
                await("外部真实输入完成合成登录", 20000, () -> targetHas("切换服务器"));
                line("PASS synthetic login through external real input");
                externalPhase("OPEN_DETAIL");
                await("外部真实输入进入合成详情", 15000, () -> targetHas("播放选项") && targetHas(MOVIE));
            } else {
            // 不混用连接 flags；先连接再生成任何真实输入事件的时间戳。
            ui = connectAutomation();
            AccessibilityServiceInfo info = ui.getServiceInfo();
            require(info != null, "UiAutomation 未连接");
            previousAccessibilityFlags = info.flags;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            await("首次安装必须是无账号页；拒绝操作任何已有账号", 15000, this::isEmptyInstall);
            emptyInstallConfirmed = true;
            require(find("切换服务器") == null, "当前已有登录账号，拒绝继续");
            loginThroughUi();
            openSyntheticDetail();
            }
            smokePlayback();
            line("PASS actual R8 Exo moving video, real BACK -> detail, one stop per session");
        } catch (Throwable error) {
            failure = error;
            line("FAIL " + error.getClass().getSimpleName() + ": " + safeMessage(error));
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("dev.pathfin.smoke.")) line("  at " + frame);
            }
        } finally {
            if (emptyInstallConfirmed && accountSubmitted) {
                try {
                    markCleanupStage("BEGIN");
                    if (externalInput) removeOnlyOurAccountExternally(); else removeOnlyOurAccount();
                    accountRemoved = true;
                    markCleanupStage("COMPLETE");
                    line("CLEANUP PASS: 本次合成服务器已从真实界面删除");
                } catch (Throwable cleanupError) {
                    line("CLEANUP FAIL: 正式包可能仍有本次合成账号“" + displayName + "”；请从外层服务器列表删除");
                    logCleanupFailure(cleanupError);
                    if (failure == null) failure = cleanupError; else failure.addSuppressed(cleanupError);
                }
            }
            try {
                if (externalControl != null) externalControl.close();
                restoreWindowFlags();
                if (ui != null) {
                    AccessibilityServiceInfo info = ui.getServiceInfo();
                    if (info != null) { info.flags = previousAccessibilityFlags; ui.setServiceInfo(info); }
                }
                if (emptyInstallConfirmed) {
                    boolean lockedAfter = getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked();
                    line("KEYGUARD before=" + lockedBefore + " after=" + lockedAfter + " (never requested unlock)");
                    require(!lockedBefore || lockedAfter, "测试期间锁屏状态意外解除");
                }
            } catch (Throwable restoreError) {
                if (failure == null) failure = restoreError; else failure.addSuppressed(restoreError);
            }
            Bundle result = new Bundle();
            result.putString("stream", report + "\nRESULT: " + (failure == null ? snapshotOnly ? "SNAPSHOT_ONLY" : "PASS" : "FAIL") + "\n");
            result.putBoolean("passed", failure == null && !snapshotOnly);
            result.putBoolean("snapshotCaptured", failure == null && snapshotOnly);
            result.putBoolean("syntheticAccountRemoved", accountRemoved);
            finishWithResult(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private UiAutomation connectAutomation() {
        line("AUTOMATION connect flags=0 sdk=" + Build.VERSION.SDK_INT
                + " callerHasLooper=" + (Looper.myLooper() != null)
                + " callerIsMain=" + (Looper.myLooper() == Looper.getMainLooper())
                + " mainAlive=" + Looper.getMainLooper().getThread().isAlive());
        try {
            UiAutomation connected = super.getUiAutomation();
            require(connected != null, "平台 getUiAutomation 返回 null，尚未执行任何登录");
            return connected;
        } catch (RuntimeException error) {
            automationConnectionFailed = true;
            automationFailure("initial-connect", error);
            throw error;
        }
    }

    private void finishWithResult(int code, Bundle result) {
        try {
            super.finish(code, result);
        } catch (IllegalStateException error) {
            // API37 Instrumentation.java:309 与 UiAutomation.java:373/445：Binder 注册的
            // RuntimeException 不进入 TimeoutException 恢复分支，内部对象留在 CONNECTING；
            // finish 又无条件 disconnect。公共 API 没有重置该状态的方法。
            // 仅这个已失败的连接允许报告后结束自身 PID，让系统清理本次 Binder；不重连、
            // 不 destroy，不反射平台字段，不将工具错误冒充生产播放失败或验收通过。
            String message = error.getMessage();
            if (!automationConnectionFailed || message == null
                    || !message.startsWith("Cannot call disconnect() while connecting UiAutomation@")) throw error;
            automationFailure("finish-after-connect-failure", error);
            line("AUTOMATION FAIL: platform connection remains CONNECTING; ending only this instrumented PID, no login performed");
            result.putBoolean("passed", false);
            result.putString("stream", report + "\nRESULT: FAIL (UiAutomation setup; no login performed)\n");
            sendStatus(Activity.RESULT_CANCELED, result);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private void automationFailure(String stage, Throwable error) {
        // 只在尚未登录的 UiAutomation 建连/同一建连结束故障打印 SDK 因果链。
        // 保留初始 Binder/Looper/连接状态原因；过滤 URL 与凭据行，绝不转储任意 UI。
        Set<Throwable> seen = new HashSet<>();
        for (int causes = 0; error != null && causes < 8 && seen.add(error); causes++, error = error.getCause()) {
            String message = error.getMessage();
            if (message == null) message = "(no message)";
            message = message.replaceAll("(?i)https?://[^\\s\\\"<>]+", "[redacted-address]")
                    .replaceAll("(?im)^.*(?:Authorization|Cookie|password|access[_ -]?token)\\s*[:=].*$", "[redacted-credential-line]");
            if (message.length() > 5000) message = message.substring(0, 5000) + " [truncated]";
            line("AUTOMATION " + stage + " cause[" + causes + "] " + error.getClass().getName() + ": " + message);
            int printed = 0;
            for (StackTraceElement frame : error.getStackTrace()) {
                String owner = frame.getClassName();
                if ((owner.startsWith("android.") || owner.startsWith("com.android.")
                        || owner.startsWith("java.") || owner.startsWith("dev.pathfin.smoke.")) && printed++ < 40)
                    line("  at " + frame);
            }
        }
    }

    private void preflight() throws Exception {
        require("true".equals(arguments.getString("acknowledgeFreshProductionInstall")),
                "必须显式 acknowledgeFreshProductionInstall=true，仅验收新装正式包");
        String runId = arguments.getString("runId", "");
        require(runId.matches("[A-Za-z0-9_-]{8,48}"), "runId 需要 8..48 位 ASCII 字母数字、下划线或连字符");
        externalInput = "true".equals(arguments.getString("externalInput"));
        snapshotOnly = "true".equals(arguments.getString("snapshotOnly"));
        require(!snapshotOnly || externalInput, "snapshotOnly 仅用于 externalInput 模式");
        require(!externalInput || Build.VERSION.SDK_INT >= 29, "外部输入模式需 API29+ 的公开 WindowInspector");
        require(!externalInput || snapshotOnly || arguments.getString("controlToken", "").matches("[a-f0-9]{64}"),
                "externalInput 全流程需要 256 位随机 controlToken");
        for (String key : arguments.keySet()) require(Arrays.asList("acknowledgeFreshProductionInstall", "runId", "externalInput", "snapshotOnly", "controlToken").contains(key),
                "工具不接受自定义 target/server/账号或其它参数");
        displayName = "Production smoke " + runId;
        require(TARGET.equals(getTargetContext().getPackageName()), "禁止目标为开发包或第三方包");
        PackageInfo pkg = getTargetContext().getPackageManager().getPackageInfo(TARGET, 0);
        long code = Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
        require(code == SmokeTarget.VERSION_CODE && SmokeTarget.VERSION_NAME.equals(pkg.versionName),
                "安装包版本与构建此工具时指定的正式 APK 不一致");
        require((pkg.applicationInfo.flags & (ApplicationInfo.FLAG_DEBUGGABLE | ApplicationInfo.FLAG_TEST_ONLY)) == 0,
                "拒绝 debuggable/testOnly 包");
        require(pkg.applicationInfo.splitSourceDirs == null || pkg.applicationInfo.splitSourceDirs.length == 0,
                "仅验收独立 APK，不接受额外 split");
        require(SmokeTarget.SHA256.equals(apkSha256(pkg.applicationInfo.sourceDir)),
                "安装包 SHA-256 与本次待验正式 APK 不一致，禁止继续");
        require(Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"), "音频与字幕扩展验收需 ARM64 设备");
        JSONObject health = status();
        require(health.optBoolean("Fixture") && health.optBoolean("MultiTrackFixture")
                        && "pathfin-fixture-server".equals(health.optString("Id"))
                        && health.optInt("DurationSeconds") == 120,
                "8101 不是固定合成多轨服务；禁止登录");
        JSONObject auth = request("/Users/AuthenticateByName", "{\"Username\":\"pathfin\",\"Pw\":\"pathfin\"}",
                "MediaBrowser Client=\"Pathfin smoke\", Device=\"Synthetic smoke\", DeviceId=\"smoke_" + runId + "\", Version=\"0.1.0\"");
        require("fixture-token".equals(auth.optString("AccessToken"))
                        && "pathfin-fixture-server".equals(auth.optString("ServerId"))
                        && "fixture-user".equals(auth.optJSONObject("User").optString("Id")),
                "合成账号响应不匹配；禁止启动登录界面");
    }

    private static String apkSha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(hex[(value & 255) >>> 4]).append(hex[value & 15]);
        return result.toString();
    }

    private void loginThroughUi() throws Exception {
        setField("服务器地址", BASE);
        await("完整 HTTP 地址已更新表单", 5000, () -> find("允许 HTTP 明文连接与播放") != null);
        AccessibilityNodeInfo toggle = findWithScroll("允许 HTTP 明文连接与播放", 8);
        AccessibilityNodeInfo checkable = nearest(toggle, true);
        require(checkable != null, "HTTP 许可没有可操作的 Switch");
        if (!checkable.isChecked()) require(clickNode(checkable), "不能启用合成 HTTP 连接");
        await("HTTP 许可实际启用", 3000, () -> {
            AccessibilityNodeInfo node = nearest(find("允许 HTTP 明文连接与播放"), true);
            return node != null && node.isChecked();
        });
        click("连接");
        await("地址探测完成后显示账号表单", 20000, () -> find("用户名") != null);
        setField("用户名", "pathfin");
        setField("密码", "pathfin");
        setField("在本机显示为", displayName);
        accountSubmitted = true; // 网络返回超时也必须检查并清理本次账号。
        click("进入媒体库");
        await("真实 Jellyfin 登录完成", 20000, () -> find("切换服务器") != null);
        line("PASS synthetic login through UI");
    }

    private void openSyntheticDetail() throws Exception {
        click(MOVIE);
        await("合成媒体详情", 15000, () -> find("播放选项") != null);
        require(find(MOVIE) != null, "详情不是本次合成媒体");
    }

    private void smokePlayback() throws Exception {
        String player = "Exo";
        Set<String> beforeSessions = starts(status());
        if (externalInput) externalPhase("PLAY_EXO");
        else {
            click("播放选项");
            await("存在从头播放选项", 5000, () -> find("从头播放") != null);
            click("从头播放");
        }
        AtomicReference<String> session = new AtomicReference<>();
        await(player + " 真实 start 上报", 20000, () -> {
            Set<String> current = starts(status()); current.removeAll(beforeSessions);
            require(current.size() <= 1, player + " 不得生成重复播放会话");
            if (current.size() == 1) { session.set(current.iterator().next()); return true; }
            return false;
        });
        ownedSessions.add(session.get());
        AtomicReference<Bitmap> first = new AtomicReference<>();
        AtomicReference<Bitmap> rejectedFrame = new AtomicReference<>();
        beginCaptureStage(player + "_FIRST_FRAME");
        try {
            await(player + " 实际非黑彩色视频帧", 30000, () -> {
                Bitmap frame = capture();
                if (frame == null) return false;
                if (!isTestPattern(frame)) {
                    Bitmap previous = rejectedFrame.getAndSet(frame);
                    if (previous != null) previous.recycle();
                    return false;
                }
                first.set(frame); return true;
            });
        } catch (Exception | AssertionError failure) {
            try { exportFailedVideoFrame(player, rejectedFrame.get()); }
            catch (Exception | AssertionError exportFailure) { failure.addSuppressed(exportFailure); }
            throw failure;
        } finally {
            Bitmap previous = rejectedFrame.getAndSet(null);
            if (previous != null) previous.recycle();
        }
        try {
            AtomicReference<Double> difference = new AtomicReference<>(0.0);
            beginCaptureStage(player + "_MOVING_FRAME");
            try { await(player + " 真实视频必须持续变帧", 7000, () -> {
                SystemClock.sleep(450);
                Bitmap second = capture();
                if (second == null) return false;
                try {
                    double delta = pixelDifference(first.get(), second);
                    difference.set(delta);
                    return isTestPattern(second) && delta > 2.0;
                } finally { second.recycle(); }
            }); } catch (Exception | AssertionError failure) {
                try { exportFailedVideoFrame(player, first.get()); }
                catch (Exception | AssertionError exportFailure) { failure.addSuppressed(exportFailure); }
                throw failure;
            }
            await(player + " 实际进度上报前进", 20000, () -> advanced(session.get(), status()));
            Activity sameActivity = activity;
            int task = sameActivity.getTaskId();
            if (externalInput) externalPhase("BACK_EXO"); else realBack();
            await(player + " 系统 BACK 应回到详情，不能退出桌面", 10000, () ->
                    activity == sameActivity && !sameActivity.isFinishing() && sameActivity.getTaskId() == task
                            && (externalInput ? targetHas("播放选项") && targetHas(MOVIE)
                            : find("播放选项") != null && find(MOVIE) != null));
            await(player + " 单次 stop 上报", 10000, () -> eventCount(status(), session.get(), "stop") == 1);
            SystemClock.sleep(700);
            JSONObject finalStatus = status();
            require(eventCount(finalStatus, session.get(), "start") == 1
                            && eventCount(finalStatus, session.get(), "stop") == 1,
                    player + " 必须每会话仅 start/stop 各一次");
            line("PASS " + player + " session=" + session.get() + " movingFrameMeanDelta="
                    + String.format(java.util.Locale.US, "%.2f", difference.get())
                    + " systemBACK=detail start=1 stop=1");
        } finally { first.get().recycle(); }
    }

    /** 真实 KEYCODE_BACK DOWN/UP；不调用 app 的 dispatcher/finish 或内部回调。 */
    private void realBack() throws Exception {
        Activity expected = onMain(() -> activity);
        int task = onMain(() -> {
            require(expected != null && activity == expected && !expected.isFinishing() && !expected.isDestroyed()
                    && TARGET.equals(expected.getPackageName()), "正式包已结束或目标已变化，拒绝返回");
            return expected.getTaskId();
        });
        // 系统 BACK 回到详情后窗口焦点可短暂交接；只等待原 Activity/task，不转向其它窗口。
        await("正式包未在期限内恢复焦点，拒绝向其它窗口注入返回", 5000, () -> onMain(() -> {
            require(activity == expected && !expected.isFinishing() && !expected.isDestroyed()
                    && expected.getTaskId() == task && TARGET.equals(expected.getPackageName()),
                    "等待返回期间正式包已结束或目标已变化，拒绝返回");
            return expected.hasWindowFocus();
        }));
        require(onMain(() -> activity == expected && !expected.isFinishing() && !expected.isDestroyed()
                && expected.getTaskId() == task && TARGET.equals(expected.getPackageName()) && expected.hasWindowFocus()),
                "注入返回前正式包身份或焦点已变化，拒绝输入");
        long down = SystemClock.uptimeMillis();
        KeyEvent pressed = new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        require(ui.injectInputEvent(pressed, true), "系统 BACK DOWN 注入失败");
        SystemClock.sleep(60);
        KeyEvent released = new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        require(ui.injectInputEvent(released, true), "系统 BACK UP 注入失败");
    }

    private void removeOnlyOurAccount() throws Exception {
        // 初始空账号已由 UI 证明；导航不使用 finish/clearTask，不更改任何用户文件。
        markCleanupStage("RETURN_TO_SERVERS");
        Activity expected = onMain(() -> activity);
        int task = onMain(() -> {
            require(expected != null && activity == expected && !expected.isFinishing() && !expected.isDestroyed()
                    && TARGET.equals(expected.getPackageName()), "清理时正式包已结束或目标已变化");
            return expected.getTaskId();
        });
        String page = awaitCleanupPage(expected, task, "DETAIL", "HOME", "OWNED_SERVERS", "ACCOUNT", "EMPTY");
        if ("DETAIL".equals(page)) {
            line("CLEANUP NAV source=DETAIL action=SYSTEM_BACK target=HOME_OR_OWNED_SERVERS");
            realBack();
            page = awaitCleanupPage(expected, task, "HOME", "OWNED_SERVERS");
            line("CLEANUP NAV arrived=" + page);
        }
        if ("HOME".equals(page)) {
            line("CLEANUP NAV source=HOME action=SYSTEM_BACK target=OWNED_SERVERS");
            realBack();
            page = awaitCleanupPage(expected, task, "OWNED_SERVERS");
            line("CLEANUP NAV arrived=" + page);
        }
        if ("ACCOUNT".equals(page)) {
            // 失败登录仍在本次合成表单时，显式回地址页；不连发 BACK 绕过键盘或退出 Activity。
            line("CLEANUP NAV source=ACCOUNT action=CHANGE_SERVER target=EMPTY");
            click("更换服务器");
            page = awaitCleanupPage(expected, task, "EMPTY");
            line("CLEANUP NAV arrived=" + page);
        }
        if ("EMPTY".equals(page)) return;
        require("OWNED_SERVERS".equals(page), "未知清理页面，拒绝继续导航");
        String ownedMenu = "服务器操作 " + displayName;
        markCleanupStage("OPEN_OWNED_MENU");
        click(ownedMenu);
        markCleanupStage("EXPAND_OWNED_SHEET");
        expandOwnedAccountSheet();
        markCleanupStage("REQUEST_REMOVE");
        click("从本机移除");
        markCleanupStage("VERIFY_DELETE_CONFIRMATION");
        await("真实删除本机服务器确认", 4000, () -> find("删除服务器？") != null);
        // 对话框正文必须明确包含我们生成的唯一名称，不能误删其它 profile。
        require(findContaining("从本机移除“" + displayName + "”的账号与地址") != null, "删除对象不是本次合成账号");
        markCleanupStage("CONFIRM_DELETE");
        click("删除");
        markCleanupStage("WAIT_EMPTY_PAGE");
        await("合成账号删除落盘后回到空账号页", 10000, () -> isEmptyInstall() && find(ownedMenu) == null);
        markCleanupStage("VERIFY_STOP_EVENTS");
        for (String session : ownedSessions) require(eventCount(status(), session, "stop") == 1,
                "清理时会话必须已停止且仅上报一次");
    }

    private String awaitCleanupPage(Activity expected, int task, String... allowed) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        long stableSince = 0;
        String previous = null;
        String lastPage = "UNKNOWN";
        while (SystemClock.uptimeMillis() < deadline) {
            require(onMain(() -> activity == expected && !expected.isFinishing() && !expected.isDestroyed()
                    && expected.getTaskId() == task && TARGET.equals(expected.getPackageName())),
                    "等待清理页面期间正式包已结束或目标已变化，拒绝导航");
            AccessibilityNodeInfo anchor = null;
            String page = "UNKNOWN";
            // 来源优先：退场中的详情/首页仍可见时，不能把下层列表当作导航已完成。
            if (find(MOVIE) != null && (anchor = find("播放选项")) != null) page = "DETAIL";
            else if ((anchor = find("切换服务器")) != null) page = "HOME";
            else if (find(displayName) != null && find("在本机显示为") != null && find("更换服务器") != null
                    && (anchor = find("进入媒体库")) != null) page = "ACCOUNT";
            else if ((anchor = find("服务器操作 " + displayName)) != null) page = "OWNED_SERVERS";
            else if (isEmptyInstall()) { page = "EMPTY"; anchor = find("服务器地址"); }
            lastPage = page;
            if (anchor != null && Arrays.asList(allowed).contains(page)) {
                Rect bounds = new Rect(); anchor.getBoundsInScreen(bounds);
                String identity = page + ":" + anchor.getWindowId() + ":" + bounds.toShortString();
                if (identity.equals(previous)) {
                    if (SystemClock.uptimeMillis() - stableSince >= 350) return page;
                } else { previous = identity; stableSince = SystemClock.uptimeMillis(); }
            } else previous = null;
            SystemClock.sleep(100);
        }
        throw new AssertionError("清理来源/目标未知或未稳定：" + lastPage + "，拒绝盲目返回");
    }

    private void removeOnlyOurAccountExternally() throws Exception {
        markCleanupStage("EXTERNAL_UI_CLEANUP");
        externalPhase("CLEANUP");
        markCleanupStage("WAIT_EMPTY_PAGE");
        await("必须实际删除本次唯一合成服务器并回到空账号页", 10000, () -> isEmptyInstall(onMain(() -> TargetViewStructure.snapshot(activity)))
                && !targetHas("服务器操作 " + displayName));
        markCleanupStage("VERIFY_STOP_EVENTS");
        for (String session : ownedSessions) require(eventCount(status(), session, "stop") == 1,
                "外部清理时会话必须仅 stop 一次");
    }

    private void markCleanupStage(String stage) {
        cleanupStage = stage;
        line("CLEANUP STAGE: " + stage);
    }

    private void logCleanupFailure(Throwable error) {
        Set<Throwable> seen = new HashSet<>();
        for (int depth = 0; error != null && depth < 4 && seen.add(error); depth++, error = error.getCause()) {
            line("CLEANUP ERROR stage=" + cleanupStage + " cause=" + depth + " "
                    + error.getClass().getSimpleName() + ": " + safeMessage(error));
            int frames = 0;
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("dev.pathfin.smoke.") && frames++ < 12)
                    line("  at " + frame);
            }
        }
    }

    private void expandOwnedAccountSheet() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 4000;
        Rect previous = null;
        int previousWindow = -1;
        long stableSince = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            if (find("从本机移除") != null) return;
            AccessibilityNodeInfo sheet = ownedAccountSheet();
            if (sheet != null) {
                Rect bounds = new Rect(); sheet.getBoundsInScreen(bounds);
                long now = SystemClock.uptimeMillis();
                if (bounds.equals(previous) && sheet.getWindowId() == previousWindow) {
                    if (now - stableSince >= 250) {
                        Rect viewport = new Rect(); sheet.getParent().getBoundsInScreen(viewport);
                        float x = bounds.centerX(), startY = bounds.top + bounds.height() * .8f;
                        float endY = viewport.top + viewport.height() / 3f;
                        long down = SystemClock.uptimeMillis();
                        injectTouch(down, MotionEvent.ACTION_DOWN, x, startY);
                        try {
                            for (int step = 1; step <= 10; step++) {
                                SystemClock.sleep(28);
                                injectTouch(down, MotionEvent.ACTION_MOVE, x, startY + (endY-startY)*step/10f);
                            }
                        } finally {
                            injectTouch(down, MotionEvent.ACTION_UP, x, endY);
                        }
                        return;
                    }
                } else {
                    previous = bounds; previousWindow = sheet.getWindowId(); stableSince = now;
                }
            } else previous = null;
            SystemClock.sleep(100);
        }
        throw new AssertionError("本次合成账号弹层未稳定，拒绝盲滑其它窗口");
    }

    private AccessibilityNodeInfo ownedAccountSheet() {
        AccessibilityNodeInfo scrim = find("Close sheet");
        if (scrim == null || !scrim.isClickable() || !scrim.isEnabled()) return null;
        AccessibilityNodeInfo parent = scrim.getParent();
        if (parent == null) return null;
        Rect mask = new Rect(); scrim.getBoundsInScreen(mask);
        AccessibilityNodeInfo result = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null || child.equals(scrim) || !child.isVisibleToUser() || !child.isEnabled()
                    || child.getWindowId() != scrim.getWindowId() || !TARGET.contentEquals(empty(child.getPackageName()))) continue;
            Rect bounds = new Rect(); child.getBoundsInScreen(bounds);
            if (bounds.isEmpty() || bounds.left < 0 || bounds.top < 0 || bounds.left != mask.left
                    || bounds.right != mask.right || Math.abs(bounds.top-mask.bottom) > 2 || bounds.top <= mask.top) continue;
            int[] owners = new int[2];
            collect(child, node -> {
                if (node.isVisibleToUser() && TARGET.contentEquals(empty(node.getPackageName()))) {
                    if (displayName.equals(empty(node.getText()))) owners[0]++;
                    if (BASE.equals(empty(node.getText()))) owners[1]++;
                }
                return false;
            });
            if (owners[0] == 1 && owners[1] == 1) {
                if (result != null) return null;
                result = child;
            }
        }
        return result;
    }

    private void injectTouch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(ui.injectInputEvent(event, true), "本次合成账号弹层真实手势注入失败"); }
        finally { event.recycle(); }
    }

    private boolean isEmptyInstall() {
        return find("Pathfin") != null && find("添加服务器") != null && find("服务器地址") != null
                && find("连接") != null && find("切换服务器") == null && find("返回") == null;
    }

    private static boolean isEmptyInstall(JSONObject snapshot) throws Exception {
        for (String label : new String[]{"Pathfin", "添加服务器", "服务器地址", "连接"})
            if (!TargetViewStructure.has(snapshot, label)) return false;
        return !TargetViewStructure.has(snapshot, "切换服务器") && !TargetViewStructure.has(snapshot, "返回");
    }

    private void externalPhase(String phase) throws Exception {
        require(externalControl != null, "外部真实输入通道未初始化");
        line("EXTERNAL waiting " + phase);
        externalControl.awaitInput(phase, displayName);
    }

    private boolean targetHas(String label) throws Exception {
        JSONObject snapshot = onMain(() -> TargetViewStructure.snapshot(activity));
        return snapshot.optBoolean("focused") && snapshot.optBoolean("activityAlive") && TargetViewStructure.has(snapshot, label);
    }

    private void externalMessage(JSONObject message) {
        // 每次节点快照只流向 stdout，不重复存入最终 Bundle，避免 Binder 单次事务过大。
        Bundle status = new Bundle(); status.putString("stream", "PF_SMOKE:" + message + "\n"); sendStatus(0, status);
    }

    private void restoreWindowFlags() {
        windowHookEnabled = false;
        runOnMainSync(() -> {
            TargetViewStructure.restoreAutofill();
            for (Map.Entry<Activity, Integer> entry : originalFlags.entrySet()) {
                Activity host = entry.getKey();
                if (host.isDestroyed()) continue;
                host.getWindow().setFlags(entry.getValue(), WINDOW_TEST_FLAGS);
                if (Build.VERSION.SDK_INT >= 27) {
                    host.setShowWhenLocked((entry.getValue() & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0);
                    host.setTurnScreenOn((entry.getValue() & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0);
                }
            }
        });
    }

    private void setField(String label, String value) throws Exception {
        findWithScroll(label, 8);
        await("真实编辑器取得焦点：" + label, 5000, () -> {
            AccessibilityNodeInfo field = editableField(label);
            if (field == null || !field.isEnabled()) return false;
            if (field.isFocused()) return true;
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return false;
        });
        AccessibilityNodeInfo field = editableField(label);
        require(field != null && field.isFocused() && field.isEnabled(), "真实编辑器焦点已变化：" + label);
        Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        require(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "真实字段不支持输入：" + label);
        await("真实字段已接收输入：" + label, 5000, () -> {
            AccessibilityNodeInfo current = editableField(label);
            if (current == null) return false;
            String actual = empty(current.getText());
            // 密码无障碍文本由系统遮蔽；只验证长度，正确值仍须经过随后真实服务器认证。
            return current.isPassword() ? actual.length() == value.length() : value.equals(actual);
        });
    }

    private AccessibilityNodeInfo editableField(String label) {
        AccessibilityNodeInfo labelNode = find(label);
        AccessibilityNodeInfo field = null;
        for (int depth = 0; labelNode != null && depth < 4 && field == null; depth++, labelNode = labelNode.getParent()) {
            List<AccessibilityNodeInfo> candidates = new ArrayList<>();
            collect(labelNode, node -> { if (node.isEditable() && node.isVisibleToUser()) candidates.add(node); return false; });
            if (candidates.size() == 1) field = candidates.get(0);
        }
        return field;
    }

    private AccessibilityNodeInfo findWithScroll(String label, int attempts) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        long nextScroll = SystemClock.uptimeMillis() + 400;
        long stableSince = 0;
        Rect previous = null;
        int previousWindow = -1;
        int scrolls = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo found = find(label);
            long now = SystemClock.uptimeMillis();
            if (found != null && found.isEnabled()) {
                Rect bounds = new Rect(); found.getBoundsInScreen(bounds);
                if (bounds.equals(previous) && found.getWindowId() == previousWindow) {
                    if (now - stableSince >= 250) return found;
                } else {
                    previous = bounds; previousWindow = found.getWindowId(); stableSince = now;
                }
                nextScroll = now + 400;
            } else {
                previous = null;
                if (scrolls < attempts && now >= nextScroll) {
                    AccessibilityNodeInfo scroll = first(node -> node.isScrollable() && node.isVisibleToUser() && node.isEnabled());
                    // IME/页面变化期间可能暂时没有目标包滚动节点，不能据单帧直接失败。
                    if (scroll != null && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolls++;
                    nextScroll = now + 350;
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("找不到界面内容：" + label);
    }

    private void click(String label) throws Exception {
        require(clickNode(findWithScroll(label, 8)), "无法点击真实界面内容：" + label);
        SystemClock.sleep(150);
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = nearest(node, false);
        return clickable != null && clickable.isEnabled() && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo nearest(AccessibilityNodeInfo node, boolean checkable) {
        for (int depth = 0; node != null && depth < 6; depth++, node = node.getParent())
            if (TARGET.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                    && (checkable ? node.isCheckable() : node.isClickable())) return node;
        return null;
    }

    private AccessibilityNodeInfo find(String label) {
        return first(node -> label.contentEquals(empty(node.getText())) || label.contentEquals(empty(node.getContentDescription()))
                || label.contentEquals(empty(node.getHintText())));
    }

    private AccessibilityNodeInfo findContaining(String label) {
        return first(node -> empty(node.getText()).contains(label) || empty(node.getContentDescription()).contains(label));
    }

    private AccessibilityNodeInfo first(NodePredicate predicate) {
        if (ui == null) return null;
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = ui.getRootInActiveWindow();
        if (active != null && TARGET.contentEquals(empty(active.getPackageName()))) {
            roots.add(active);
        } else {
            for (AccessibilityWindowInfo window : ui.getWindows()) {
                if (!window.isActive() && !window.isFocused()) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && TARGET.contentEquals(empty(root.getPackageName()))) { roots.add(root); break; }
            }
        }
        for (AccessibilityNodeInfo root : roots) {
            AccessibilityNodeInfo found = collect(root, node -> {
                if (!node.isVisibleToUser() || !TARGET.contentEquals(empty(node.getPackageName()))) return false;
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                return !bounds.isEmpty() && predicate.test(node);
            });
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo collect(AccessibilityNodeInfo root, NodePredicate predicate) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (predicate.test(node)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private Bitmap capture() throws Exception {
        captureAttempts++;
        AtomicReference<SurfaceView> selected = new AtomicReference<>();
        JSONObject diagnostic = onMain(() -> {
            View decor = activity == null ? null : activity.getWindow().getDecorView();
            JSONArray candidates = new JSONArray(); describeSurfaces(decor, candidates, new int[]{0});
            SurfaceView surface = surfaceUnder(decor); selected.set(surface);
            JSONObject value = new JSONObject().put("surfaceCandidates", candidates)
                    .put("activityFocused", activity != null && activity.hasWindowFocus())
                    .put("activityAlive", activity != null && !activity.isFinishing() && !activity.isDestroyed());
            value.put("selectedSurface", surface == null ? JSONObject.NULL : describeSurface(surface));
            return value;
        });
        SurfaceView surface = selected.get();
        if (surface == null) {
            diagnostic.put("pixelCopyResult", "NO_ELIGIBLE_SURFACE"); recordCapture(diagnostic); return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(-1);
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicBoolean recycled = new AtomicBoolean();
        runOnMainSync(() -> {
            try {
                // PixelCopy 的 Rect 使用底层 Surface buffer 坐标，而非屏幕或 View bounds。
                // 此工具已限定自制 320x180 无旋转视频；新播放器默认 FIT，未操作比例设置。
                Rect buffer = surface.getHolder().getSurfaceFrame();
                int[] fitted = FixtureVideoGeometry.centeredFit(buffer.width(), buffer.height());
                Rect sourceRect = new Rect(fitted[0], fitted[1], fitted[2], fitted[3]);
                diagnostic.put("surfaceBufferSize", new JSONArray(new int[]{buffer.width(), buffer.height()}));
                diagnostic.put("sourceRect", new JSONArray(fitted));
                diagnostic.put("sourceRectBasis", "fixed_fixture_320x180_square_pixels_no_rotation_center_FIT");
                diagnostic.put("fixtureVideoSize", new JSONArray(new int[]{320, 180}));
                PixelCopy.request(surface, sourceRect, bitmap, status -> {
                result.set(status); completed.countDown();
                if (abandoned.get() && recycled.compareAndSet(false, true)) bitmap.recycle();
                }, new Handler(Looper.getMainLooper()));
            }
            catch (IllegalArgumentException unavailable) { result.set(-2); completed.countDown(); }
            catch (org.json.JSONException unavailable) { result.set(-3); completed.countDown(); }
        });
        boolean callback = completed.await(5, TimeUnit.SECONDS);
        diagnostic.put("pixelCopyResult", callback ? result.get() : "CALLBACK_TIMEOUT");
        if (!callback) {
            // 超时不能回收仍在被 PixelCopy 写入的目标；晚到回调只负责释放这一张位图。
            abandoned.set(true);
            if (completed.getCount() == 0 && recycled.compareAndSet(false, true)) bitmap.recycle();
            recordCapture(diagnostic);
            throw new AssertionError("PixelCopy 回调未完成");
        }
        if (callback && result.get() == PixelCopy.SUCCESS) {
            diagnostic.put("image", patternStatistics(bitmap)); recordCapture(diagnostic); return bitmap;
        }
        recordCapture(diagnostic);
        bitmap.recycle(); return null;
    }

    private void beginCaptureStage(String stage) {
        captureStage = stage; captureAttempts = 0; captureReports = 0;
        captureStarted = SystemClock.uptimeMillis(); lastCaptureReportAt = 0; lastCaptureDiagnostic = null;
    }

    private void recordCapture(JSONObject diagnostic) throws Exception {
        long now = SystemClock.uptimeMillis();
        diagnostic.put("kind", "VIDEO_DIAGNOSTIC").put("stage", captureStage)
                .put("attempt", captureAttempts).put("elapsedMs", now - captureStarted);
        lastCaptureDiagnostic = diagnostic;
        // 每阶段最多四份周期诊断，失败时另补最终状态，避免 30 秒轮询洪泛。
        if (captureReports < 4 && (captureReports == 0 || now - lastCaptureReportAt >= 5000)) {
            captureReports++; lastCaptureReportAt = now; externalMessage(diagnostic);
        }
    }

    private JSONObject describeSurface(SurfaceView view) throws Exception {
        int[] position = new int[2]; view.getLocationOnScreen(position);
        Rect frame = view.getHolder().getSurfaceFrame();
        return new JSONObject().put("class", view.getClass().getName()).put("width", view.getWidth())
                .put("height", view.getHeight()).put("shown", view.isShown())
                .put("valid", view.getHolder().getSurface().isValid())
                .put("surfaceFrame", new JSONArray(new int[]{frame.left, frame.top, frame.right, frame.bottom}))
                .put("screenX", position[0]).put("screenY", position[1]);
    }

    private void describeSurfaces(View view, JSONArray output, int[] visited) throws Exception {
        if (view == null || visited[0]++ >= 1600 || output.length() >= 12) return;
        if (view instanceof SurfaceView) output.put(describeSurface((SurfaceView) view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            describeSurfaces(((ViewGroup) view).getChildAt(i), output, visited);
    }

    private void exportFailedVideoFrame(String player, Bitmap frame) throws Exception {
        if (lastCaptureDiagnostic != null) externalMessage(lastCaptureDiagnostic);
        if (frame == null || !exportedVideoFrames.add(player)) return;
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        require(frame.getWidth() == 320 && frame.getHeight() == 180 && frame.compress(Bitmap.CompressFormat.PNG, 100, png),
                "合成失败帧导出失败");
        require(png.size() <= 256 * 1024, "合成失败帧超过诊断上限");
        JSONObject message = new JSONObject().put("kind", "VIDEO_FRAME").put("player", player)
                .put("stage", captureStage).put("width", 320).put("height", 180)
                .put("pngBase64", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP));
        externalMessage(message);
    }

    private SurfaceView surfaceUnder(View view) {
        if (view == null || !view.isShown()) return null;
        if (view instanceof SurfaceView && view.getWidth() >= 160 && view.getHeight() >= 90
                && ((SurfaceView) view).getHolder().getSurface().isValid()) return (SurfaceView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            SurfaceView found = surfaceUnder(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private boolean isTestPattern(Bitmap bitmap) {
        try { return patternStatistics(bitmap).getBoolean("matchesTestPattern"); }
        catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    private JSONObject patternStatistics(Bitmap bitmap) throws org.json.JSONException {
        int nonBlack = 0, colorful = 0, count = 0;
        Set<Integer> colors = new HashSet<>();
        for (int y = 8; y < bitmap.getHeight() - 8; y += 4) for (int x = 8; x < bitmap.getWidth() - 8; x += 4) {
            int pixel = bitmap.getPixel(x, y), r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            if (max > 35) nonBlack++;
            if (max - min > 40) colorful++;
            colors.add((r / 16 << 8) | (g / 16 << 4) | b / 16); count++;
        }
        return new JSONObject().put("samples", count).put("nonBlack", nonBlack).put("chromatic", colorful)
                .put("colorCount", colors.size()).put("nonBlackFraction", nonBlack / (double) count)
                .put("chromaticFraction", colorful / (double) count)
                .put("matchesTestPattern", nonBlack > count * .4 && colorful > count * .1 && colors.size() > 16);
    }

    private double pixelDifference(Bitmap a, Bitmap b) {
        long difference = 0, count = 0;
        for (int y = 8; y < a.getHeight() - 8; y += 4) for (int x = 8; x < a.getWidth() - 8; x += 4) {
            int p = a.getPixel(x, y), q = b.getPixel(x, y);
            difference += Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q))
                    + Math.abs(Color.blue(p) - Color.blue(q)); count += 3;
        }
        return difference / (double) count;
    }

    private Set<String> starts(JSONObject status) throws Exception {
        Set<String> sessions = new HashSet<>();
        JSONArray events = status.getJSONArray("Events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if ("start".equals(event.optString("event"))) {
                String session = event.optString("play_session_id");
                require(session.matches("fixture-session-[0-9]+"), "合成 start 上报身份非法");
                sessions.add(session);
            }
        }
        return sessions;
    }

    private int eventCount(JSONObject status, String session, String kind) throws Exception {
        int count = 0;
        JSONArray events = status.getJSONArray("Events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (session.equals(event.optString("play_session_id")) && kind.equals(event.optString("event"))) count++;
        }
        return count;
    }

    private boolean advanced(String session, JSONObject status) throws Exception {
        long initial = -1;
        JSONArray events = status.getJSONArray("Events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!session.equals(event.optString("play_session_id"))) continue;
            if ("start".equals(event.optString("event"))) initial = event.optLong("position_ticks");
            if ("progress".equals(event.optString("event")) && initial >= 0 && !event.optBoolean("is_paused")
                    && event.optLong("position_ticks") > initial + 10_000_000L) return true;
        }
        return false;
    }

    private JSONObject status() throws Exception {
        JSONObject result = request("/health", null, null);
        require(result.optBoolean("MultiTrackFixture") && "pathfin-fixture-server".equals(result.optString("Id")), "合成夹具身份改变");
        return result;
    }

    private JSONObject request(String route, String body, String authorization) throws Exception {
        // 调用点只使用编译期固定 route；禁止所有重定向，没有 CookieHandler/用户凭据。
        require(route.equals("/health") || route.equals("/Users/AuthenticateByName"), "拒绝非合成路由");
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + route).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            require(connection.getResponseCode() == 200, "固定合成服务未返回 HTTP 200");
            require(connection.getContentLengthLong() <= 65536, "合成响应超出 64KiB");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    require(bytes.size() + count <= 65536, "合成响应超出 64KiB"); bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private <T> T onMain(MainCallable<T> call) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.call();
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { result.set(call.call()); } catch (Throwable caught) { error.set(caught); } });
        if (error.get() != null) throw new AssertionError("平台主线程检查失败", error.get());
        return result.get();
    }

    private void await(String description, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do { if (condition.test()) return; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(description + " (timeout " + timeoutMs + "ms)");
    }

    private void line(String message) {
        report.append(message).append('\n'); Bundle status = new Bundle(); status.putString("stream", message + "\n"); sendStatus(0, status);
    }

    private static String empty(CharSequence text) { return text == null ? "" : text.toString(); }
    private static String safeMessage(Throwable error) {
        // 仅本工具的静态断言文本可打印；网络/系统异常可能带设备信息，保留类型即可。
        return error instanceof AssertionError && error.getMessage() != null ? error.getMessage() : "platform/fixture operation failed";
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private interface Condition { boolean test() throws Exception; }
    private interface NodePredicate { boolean test(AccessibilityNodeInfo node); }
    private interface MainCallable<T> { T call() throws Exception; }
}
