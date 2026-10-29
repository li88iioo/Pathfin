package dev.pathfin.smoke;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStructure;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.inspector.WindowInspector;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

/** 读取自身窗口的公开 Assist/ViewStructure；不注册服务，不调用隐藏 API 或应用类。 */
final class TargetViewStructure {
    private static final int LIMIT = 1600;
    private static final Map<View, Integer> originalAutofill = new IdentityHashMap<>();

    static JSONObject snapshot(Activity activity) throws Exception {
        if (activity == null) throw new IllegalArgumentException("需要已验证的目标 Activity");
        String targetPackage = activity.getPackageName();
        JSONObject result = new JSONObject();
        JSONArray nodes = new JSONArray();
        JSONArray windows = new JSONArray();
        boolean focused = false;
        int windowIndex = 0;
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (!targetPackage.equals(root.getContext().getPackageName()) || !root.isShown()) continue;
            // 仅本轮合成测试窗口临时禁用 Autofill，避免用户密码管理器夺取焦点；finally 恢复。
            if (!originalAutofill.containsKey(root)) originalAutofill.put(root, root.getImportantForAutofill());
            root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            windows.put(new JSONObject().put("class", root.getClass().getName())
                    .put("focused", root.hasWindowFocus()).put("width", root.getWidth()).put("height", root.getHeight()));
            if (!root.hasWindowFocus()) continue;
            focused = true;
            readView(root, nodes, windowIndex++);
        }
        result.put("kind", "SNAPSHOT");
        result.put("targetPackage", targetPackage);
        result.put("focused", focused);
        result.put("activityAlive", activity != null && !activity.isFinishing() && !activity.isDestroyed());
        result.put("taskId", activity == null ? -1 : activity.getTaskId());
        result.put("nodes", nodes);
        result.put("ownWindowStates", windows);
        return result;
    }

    static void restoreAutofill() {
        for (Map.Entry<View, Integer> entry : originalAutofill.entrySet())
            entry.getKey().setImportantForAutofill(entry.getValue());
        originalAutofill.clear();
    }

    private static void readView(View view, JSONArray output, int window) throws Exception {
        if (output.length() >= LIMIT || !view.isShown()) return;
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            Collector structure = new Collector(null);
            // View.java 的公开入口在平台内部遍历虚拟 child ID；本工具不读取隐藏 source ID。
            view.onProvideVirtualStructure(structure);
            appendVirtual(structure, provider, output, window, -1);
        } else if (!(view instanceof ViewGroup)) {
            AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
            if (node != null) append(node, output, window, -1);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            readView(((ViewGroup) view).getChildAt(i), output, window);
    }

    private static void appendVirtual(Collector structure, AccessibilityNodeProvider provider,
                                      JSONArray output, int window, int parent) throws Exception {
        if (output.length() >= LIMIT) return;
        int current = parent;
        if (structure.hasId) {
            AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(structure.id);
            if (node != null) current = append(node, output, window, parent);
        }
        for (Collector child : structure.children) if (child != null)
            appendVirtual(child, provider, output, window, current);
    }

    private static int append(AccessibilityNodeInfo node, JSONArray output, int window, int parent) throws Exception {
        if (!node.isVisibleToUser()) return parent;
        Rect rect = new Rect(); node.getBoundsInScreen(rect);
        if (rect.isEmpty()) return parent;
        int index = output.length();
        JSONObject row = new JSONObject();
        row.put("id", index); row.put("parent", parent); row.put("window", window);
        row.put("text", string(node.getText())); row.put("description", string(node.getContentDescription()));
        row.put("hint", string(node.getHintText())); row.put("class", string(node.getClassName()));
        row.put("clickable", node.isClickable()); row.put("enabled", node.isEnabled());
        row.put("focused", node.isFocused());
        row.put("editable", node.isEditable()); row.put("checkable", node.isCheckable());
        row.put("checked", node.isChecked()); row.put("scrollable", node.isScrollable());
        row.put("bounds", new JSONArray(new int[]{rect.left, rect.top, rect.right, rect.bottom}));
        output.put(row);
        return index;
    }

    /** 只对真实 tap 后已聚焦的编辑器走公开 IME 协议；不调用 Compose/应用回调。 */
    static JSONObject input(Activity activity, String label, String expected, boolean commit) throws Exception {
        JSONObject state = snapshot(activity);
        if (!state.optBoolean("focused") || !state.optBoolean("activityAlive"))
            throw new IllegalStateException("正式包窗口没有真实焦点");
        JSONArray rows = state.getJSONArray("nodes");
        JSONObject field = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!label.equals(row.optString("text")) && !label.equals(row.optString("hint"))
                    && !label.equals(row.optString("description"))) continue;
            JSONObject ancestor = row;
            for (int depth = 0; depth < 24 && ancestor != null; depth++) {
                if (ancestor.optBoolean("editable")) { field = ancestor; break; }
                int parent = ancestor.optInt("parent", -1);
                ancestor = parent >= 0 && parent < rows.length() ? rows.getJSONObject(parent) : null;
            }
            if (field != null) break;
        }
        if (field == null || !field.optBoolean("enabled") || !field.optBoolean("focused"))
            throw new IllegalStateException("指定真实编辑字段未聚焦或被禁用");
        View editor = null;
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (!activity.getPackageName().equals(root.getContext().getPackageName()) || !root.isShown()
                    || !root.hasWindowFocus()) continue;
            View focused = root.findFocus();
            if (focused == null || !focused.isEnabled() || !focused.isFocused() || !focused.onCheckIsTextEditor()) continue;
            if (editor != null && editor != focused) throw new IllegalStateException("存在多个焦点编辑器");
            editor = focused;
        }
        if (editor == null) throw new IllegalStateException("真实窗口没有就绪的 InputConnection 编辑器");
        InputConnection connection = editor.onCreateInputConnection(new EditorInfo());
        if (connection == null) throw new IllegalStateException("真实编辑器没有 InputConnection");
        boolean matches;
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = 512; request.hintMaxLines = 8;
            ExtractedText before = connection.getExtractedText(request, 0);
            if (before == null || before.text == null || before.startOffset != 0 || before.text.length() > 512
                    || before.partialStartOffset >= 0)
                throw new IllegalStateException("不能完整回读自己的合成字段");
            if (commit) {
                connection.beginBatchEdit();
                try {
                    if (!connection.finishComposingText() || !connection.setSelection(0, before.text.length())
                            || !connection.commitText(expected, 1))
                        throw new IllegalStateException("标准 InputConnection 拒绝合成文本");
                } finally { connection.endBatchEdit(); }
            }
            // 旧 RecordingInputConnection 可到下一帧才同步状态，runner 会独立重新连接并严格回读。
            ExtractedText after = connection.getExtractedText(request, 0);
            matches = after != null && expected.contentEquals(after.text == null ? "" : after.text);
        } finally { connection.closeConnection(); }
        JSONObject response = new JSONObject();
        response.put("accepted", true); response.put("matchesExpected", matches);
        response.put("fieldLabel", label); response.put("focused", true);
        return response; // 密码/字段原文不写 stdout。
    }

    static boolean has(JSONObject snapshot, String label) throws Exception {
        JSONArray rows = snapshot.getJSONArray("nodes");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (label.equals(row.optString("text")) || label.equals(row.optString("description"))
                    || label.equals(row.optString("hint"))) return true;
        }
        return false;
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }

    /**
     * 平台把公开 ViewStructure 当回调容器；只收集其虚拟 ID，动作仍由外部真实 ADB 输入。
     * getTempRect/setAssistBlocked 是平台基类的兼容抽象槽，仅由平台回调此实现；
     * 不调用这些非 SDK 方法，不反射平台或应用字段。外部模式限定已验的 API29+。
     */
    private static final class Collector extends ViewStructure {
        final List<Collector> children = new ArrayList<>();
        final Bundle extras = new Bundle();
        final Rect temporaryRect = new Rect();
        int id;
        boolean hasId;
        Collector(Collector parent) { }
        @Override public void setId(int id, String pkg, String type, String entry) { this.id = id; hasId = true; }
        @Override public void setChildCount(int count) { if (count < 0 || count > LIMIT) throw new IllegalStateException("ViewStructure node limit"); while (children.size() < count) children.add(null); }
        @Override public int addChildCount(int count) { int previous = children.size(); setChildCount(previous + count); return previous; }
        @Override public int getChildCount() { return children.size(); }
        @Override public ViewStructure newChild(int index) { if (index >= children.size()) setChildCount(index + 1); Collector child = new Collector(this); children.set(index, child); return child; }
        @Override public ViewStructure asyncNewChild(int index) { return newChild(index); }
        @Override public void asyncCommit() { }
        @Override public Bundle getExtras() { return extras; }
        @Override public boolean hasExtras() { return !extras.isEmpty(); }
        public Rect getTempRect() { return temporaryRect; }
        public void setAssistBlocked(boolean value) { }
        @Override public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) { }
        @Override public void setVisibility(int value) { }
        @Override public void setClassName(String value) { }
        @Override public void setContentDescription(CharSequence value) { }
        @Override public void setText(CharSequence value) { }
        @Override public void setText(CharSequence value, int start, int end) { }
        @Override public CharSequence getText() { return null; }
        @Override public int getTextSelectionStart() { return -1; }
        @Override public int getTextSelectionEnd() { return -1; }
        @Override public void setHint(CharSequence value) { }
        @Override public CharSequence getHint() { return null; }
        @Override public void setEnabled(boolean value) { }
        @Override public void setFocusable(boolean value) { }
        @Override public void setFocused(boolean value) { }
        @Override public void setAccessibilityFocused(boolean value) { }
        @Override public void setClickable(boolean value) { }
        @Override public void setLongClickable(boolean value) { }
        @Override public void setContextClickable(boolean value) { }
        @Override public void setCheckable(boolean value) { }
        @Override public void setChecked(boolean value) { }
        @Override public void setSelected(boolean value) { }
        @Override public void setActivated(boolean value) { }
        @Override public void setOpaque(boolean value) { }
        @Override public void setAlpha(float value) { }
        @Override public void setElevation(float value) { }
        @Override public void setTransformation(Matrix value) { }
        @Override public void setTextStyle(float size, int fg, int bg, int style) { }
        @Override public void setTextLines(int[] offsets, int[] baselines) { }
        @Override public void setAutofillId(AutofillId value) { }
        @Override public void setAutofillId(AutofillId parent, int child) { }
        @Override public AutofillId getAutofillId() { return null; }
        @Override public void setAutofillType(int value) { }
        @Override public void setAutofillValue(AutofillValue value) { }
        @Override public void setAutofillHints(String[] value) { }
        @Override public void setAutofillOptions(CharSequence[] value) { }
        @Override public void setInputType(int value) { }
        @Override public void setDataIsSensitive(boolean value) { }
        @Override public void setLocaleList(LocaleList value) { }
        @Override public void setWebDomain(String value) { }
        @Override public void setHtmlInfo(HtmlInfo value) { }
        @Override public HtmlInfo.Builder newHtmlInfoBuilder(String tag) { return null; }
    }
}
