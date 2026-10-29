package dev.pathfin.smoke;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 无业务钩子的公开窗口采样；只接收 shell/DUMP、随机 token 与 nonce 匹配的请求。 */
public final class LibraryBenchmarkInstrumentation extends Instrumentation {
    private static final String TARGET = "dev.pathfin.android.verification";
    private static final String ACTION = TARGET + ".BENCHMARK_CONTROL";
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile Activity activity;
    private String token;
    private String nonce;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!ACTION.equals(intent.getAction()) || !TARGET.equals(intent.getPackage())
                    || !token.equals(intent.getStringExtra("controlToken"))
                    || !nonce.equals(intent.getStringExtra("nonce"))) return;
            if (Build.VERSION.SDK_INT >= 34 && getSentFromUid() != -1
                    && getSentFromUid() != 2000 && getSentFromUid() != 0) return;
            String request = intent.getStringExtra("requestId");
            if (request == null || !request.matches("[a-f0-9]{32}")) return;
            try {
                String command = intent.getStringExtra("command");
                if ("snapshot".equals(command)) {
                    JSONObject snapshot = TargetViewStructure.snapshot(activity);
                    emit(snapshot.put("requestId", request));
                } else if ("input".equals(command) || "verify".equals(command)) {
                    String field = intent.getStringExtra("field");
                    String value = intent.getStringExtra("value");
                    if (field == null || !(field.equals("服务器地址") || field.equals("端口") || field.equals("路径")
                            || field.equals("用户名") || field.equals("密码"))
                            || value == null || value.length() > 512)
                        throw new IllegalArgumentException("仅允许合成登录字段");
                    JSONObject input = TargetViewStructure.input(activity, field, value, "input".equals(command));
                    emit(new JSONObject().put("kind", "INPUT").put("requestId", request)
                            .put("accepted", input.getBoolean("accepted"))
                            .put("matchesExpected", input.getBoolean("matchesExpected")));
                } else if ("stop".equals(command)) stopped.countDown();
            } catch (Exception error) {
                try { emit(new JSONObject().put("kind", "ERROR").put("requestId", request)
                        .put("error", error.getClass().getSimpleName())); }
                catch (Exception ignoredError) { stopped.countDown(); }
            }
        }
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments == null || arguments.size() != 1) throw new IllegalArgumentException("仅接受 controlToken");
        token = arguments.getString("controlToken", "");
        if (!token.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("需要随机 256 位 controlToken");
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format("%02x", item & 255));
        nonce = value.toString();
        start();
    }

    @Override public void callActivityOnResume(Activity current) {
        if (TARGET.equals(current.getPackageName())) activity = current;
        super.callActivityOnResume(current);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!TARGET.equals(getTargetContext().getPackageName())
                    || !(TARGET + ".test").equals(getContext().getPackageName())
                    || Build.VERSION.SDK_INT < 29
                    || (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                throw new IllegalStateException("只允许非调试 verification release");
            Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(TARGET);
            if (launch == null || launch.getComponent() == null || !TARGET.equals(launch.getComponent().getPackageName()))
                throw new IllegalStateException("缺少 verification 启动入口");
            activity = startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            if (Build.VERSION.SDK_INT >= 33)
                getTargetContext().registerReceiver(receiver, new IntentFilter(ACTION), Manifest.permission.DUMP,
                        new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(receiver, new IntentFilter(ACTION), Manifest.permission.DUMP,
                    new Handler(Looper.getMainLooper()));
            registered = true;
            emit(new JSONObject().put("kind", "READY").put("targetPackage", TARGET).put("nonce", nonce));
            if (!stopped.await(20, TimeUnit.MINUTES)) throw new IllegalStateException("采样握手超时");
            result.putBoolean("stopped", true);
        } catch (Exception error) {
            result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (registered) getTargetContext().unregisterReceiver(receiver);
            runOnMainSync(TargetViewStructure::restoreAutofill);
            finish(result.getBoolean("stopped") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private void emit(JSONObject message) {
        Bundle status = new Bundle();
        status.putString("pathfin.benchmark", message.toString());
        sendStatus(2, status);
    }
}
