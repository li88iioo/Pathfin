package dev.pathfin.smoke;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅测试期间的 shell/DUMP 权限 + 随机 token + 每阶段 nonce 握手；没有播放器回调。 */
final class ExternalInputControl implements AutoCloseable {
    static final String ACTION = "dev.pathfin.android.production.smoke.ACK";
    private final Context context;
    private final String token;
    private final Snapshot snapshot;
    private final Output output;
    private final TextInput textInput;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger acknowledged = new AtomicInteger();
    private volatile String phase;
    private volatile String nonce;
    private volatile String displayName;
    private volatile boolean closed;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || !ACTION.equals(intent.getAction()) || !context.getPackageName().equals(intent.getPackage())
                    || !token.equals(intent.getStringExtra("controlToken"))
                    || !equal(phase, intent.getStringExtra("phase")) || !equal(nonce, intent.getStringExtra("nonce"))) return;
            // am broadcast 默认不共享 sender identity；DUMP 接收权限由系统验证，普通应用无权 ack。
            // 若系统提供确切 UID，则再限定 shell/root。token 和 nonce 不持久化。
            if (Build.VERSION.SDK_INT >= 34 && getSentFromUid() != -1
                    && getSentFromUid() != 2000 && getSentFromUid() != 0) return;
            String command = intent.getStringExtra("command");
            try {
                if ("snapshot".equals(command)) emit("SNAPSHOT");
                else if ("ack".equals(command)) acknowledged.compareAndSet(0, 1);
                else if ("fail".equals(command)) acknowledged.compareAndSet(0, -1);
                else if (("commitText".equals(command) || "verifyText".equals(command)) && "LOGIN".equals(phase)) {
                    String label = intent.getStringExtra("fieldLabel");
                    String value = fieldValue(label);
                    JSONObject response = message("TEXT_INPUT");
                    response.put("fieldLabel", label); response.put("command", command);
                    try { response.put("input", textInput.apply(label, value, "commitText".equals(command))); }
                    catch (Exception failure) {
                        response.put("input", new JSONObject().put("accepted", false)
                                .put("reason", failure.getClass().getSimpleName() + ": " + failure.getMessage()));
                    }
                    output.send(response);
                }
            } catch (Exception error) { acknowledged.compareAndSet(0, -1); }
        }
    };

    ExternalInputControl(Context context, String token, Snapshot snapshot, TextInput textInput, Output output) {
        this.context = context; this.token = token; this.snapshot = snapshot; this.textInput = textInput; this.output = output;
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, Manifest.permission.DUMP,
                    new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, Manifest.permission.DUMP, new Handler(Looper.getMainLooper()));
    }

    void awaitInput(String requestedPhase, String name) throws Exception {
        phase = requestedPhase;
        displayName = name;
        byte[] bytes = new byte[16]; random.nextBytes(bytes);
        StringBuilder encoded = new StringBuilder();
        for (byte value : bytes) encoded.append(String.format(java.util.Locale.US, "%02x", value & 255));
        nonce = encoded.toString(); acknowledged.set(0);
        JSONObject message = message("PHASE");
        message.put("displayName", name); message.put("snapshot", snapshot.get()); output.send(message);
        long deadline = SystemClock.uptimeMillis() + 60000;
        while (acknowledged.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        if (acknowledged.get() != 1) throw new AssertionError("外部真实输入阶段失败或超时：" + requestedPhase);
    }

    private void emit(String kind) throws Exception {
        JSONObject message = message(kind); message.put("snapshot", snapshot.get()); output.send(message);
    }

    private JSONObject message(String kind) throws Exception {
        JSONObject message = new JSONObject(); message.put("kind", kind); message.put("phase", phase); message.put("nonce", nonce);
        return message;
    }

    @Override public void close() { closed = true; context.unregisterReceiver(receiver); }
    private static boolean equal(String a, String b) { return a != null && a.equals(b); }
    private String fieldValue(String label) {
        if ("服务器地址".equals(label)) return "http://127.0.0.1:8101/jellyfin";
        if ("用户名".equals(label) || "密码".equals(label)) return "pathfin";
        if ("在本机显示为".equals(label)) return displayName;
        throw new IllegalArgumentException("只允许本轮固定合成登录字段");
    }
    interface Snapshot { JSONObject get() throws Exception; }
    interface TextInput { JSONObject apply(String label, String expected, boolean commit) throws Exception; }
    interface Output { void send(JSONObject message); }
}
