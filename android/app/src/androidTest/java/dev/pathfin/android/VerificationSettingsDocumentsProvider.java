package dev.pathfin.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试 APK 自有的慢 SAF，纯平台 Java；从不打开磁盘上的用户文件。 */
public final class VerificationSettingsDocumentsProvider extends DocumentsProvider {
    private static final String TARGET = "dev.pathfin.android.verification";
    private static final String PREFIX = "pathfin.settings.";
    public static final String BEHAVIOR_EXTRA = "pathfin.settings.behavior";
    public static final String PAYLOAD_EXTRA = "pathfin.settings.payload";
    public static final String MIME_EXTRA = "pathfin.settings.mime";
    public static final String IMAGE_BYTES_EXTRA = "pathfin.settings.imageBytes";
    private static final int LIMIT = 65_537;
    private final ConcurrentHashMap<String, Entry> documents = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pipes = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(6), runnable -> {
            Thread thread = new Thread(runnable, "PathfinSettingsFixture"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private static final class Entry {
        final String id;
        final String behavior;
        final String mime;
        final CountDownLatch release = new CountDownLatch(1);
        volatile byte[] payload;
        volatile ParcelFileDescriptor peer;
        volatile boolean openStarted, signalReceived, openCancelled, signalCancelled, opened, writerFailed, writeComplete;
        volatile int bytesWritten;
        volatile String clientLink;
        final AtomicInteger opens = new AtomicInteger();
        Entry(String behavior, byte[] payload, String mime) {
            this.id = UUID.randomUUID() + (mime.equals("image/png") ? ".png" : ".json");
            this.behavior = behavior; this.payload = payload; this.mime = mime;
        }
    }

    @Override public boolean onCreate() { return true; }

    public static String authority(Context testContext) {
        try {
            return testContext.getPackageManager().getProviderInfo(
                new ComponentName(testContext, VerificationSettingsDocumentsProvider.class), 0).authority;
        } catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
    }

    private void requireCaller() {
        Context context = getContext();
        if (context == null || !context.getPackageName().equals(TARGET + ".test")) throw new SecurityException("仅限验证包夹具");
        int uid = Binder.getCallingUid();
        if (uid == context.getApplicationInfo().uid) return;
        try { if (uid == context.getPackageManager().getApplicationInfo(TARGET, 0).uid) return; }
        catch (PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("仅限 verification UID");
    }

    private Entry entry(String id) throws FileNotFoundException {
        if (id == null || !id.matches("[a-f0-9-]{36}\\.(json|png)")) throw new FileNotFoundException("非法夹具文档");
        Entry entry = documents.get(id);
        if (entry == null) throw new FileNotFoundException("未知夹具文档");
        return entry;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle platform = super.call(method, arg, extras);
        if (platform != null || method.startsWith("android:")) return platform;
        requireCaller();
        if (!method.startsWith(PREFIX)) return null;
        try {
            if (method.equals(PREFIX + "create")) {
                // 文档仅由 test APK 的授权 Activity 创建。target 必须先拿到真实 result URI grant。
                if (Binder.getCallingUid() != getContext().getApplicationInfo().uid)
                    throw new SecurityException("请通过夹具授权 Activity 获取文档");
                if (documents.size() >= 6) throw new IllegalStateException("夹具文档上限");
                String behavior = extras.getString("behavior", "NORMAL");
                boolean ignoreCancellation = behavior.equals("DELAY_OPEN_IGNORE_CANCEL");
                if (!behavior.equals("NORMAL") && !behavior.equals("DELAY_OPEN") && !ignoreCancellation
                    && !behavior.equals("DELAY_WRITE_OPEN_IGNORE_CANCEL") && !behavior.equals("BLOCK_READ") && !behavior.equals("BLOCK_WRITE"))
                    throw new IllegalArgumentException("未知夹具行为");
                String mime = extras.getString("mime", "application/json");
                if (!mime.equals("application/json") && !mime.equals("image/png")) throw new IllegalArgumentException("未知夹具类型");
                byte[] payload = extras.getByteArray("payload");
                if (ignoreCancellation && (!mime.equals("image/png") || payload != null || extras.getInt("imageBytes", 0) != 0))
                    throw new IllegalArgumentException("迟到 open 夹具仅允许默认有效 PNG");
                if (payload == null) payload = mime.equals("image/png")
                    ? Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMQMgn7DwAClAGcHVtGXwAAAABJRU5ErkJggg==")
                    : "{\"format\":\"pathfin-settings-v1\",\"theme\":\"DARK\",\"settings\":{\"seekSeconds\":25}}".getBytes(StandardCharsets.UTF_8);
                if (payload.length > LIMIT) throw new IllegalArgumentException("夹具数据超限");
                int imageBytes = extras.getInt("imageBytes", 0);
                if (imageBytes != 0) {
                    if (!mime.equals("image/png") || imageBytes < payload.length || imageBytes > 10 * 1024 * 1024 + 1)
                        throw new IllegalArgumentException("夹具图片大小无效");
                    // 超限样本在 Provider 内生成，不能通过 Binder 传输大字节数组。
                    payload = java.util.Arrays.copyOf(payload, imageBytes);
                }
                Entry entry = new Entry(behavior, payload, mime);
                documents.put(entry.id, entry);
                Uri uri = DocumentsContract.buildDocumentUri(authority(getContext()), entry.id);
                Bundle result = new Bundle(); result.putString("uri", uri.toString()); result.putString("mime", mime); return result;
            }
            Entry entry = entry(arg);
            requireDocumentGrant(entry, method.equals(PREFIX + "status")
                ? Intent.FLAG_GRANT_READ_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (method.equals(PREFIX + "release")) entry.release.countDown();
            if (method.equals(PREFIX + "delete")) { deleteEntry(entry); return Bundle.EMPTY; }
            Bundle status = new Bundle();
            status.putBoolean("openStarted", entry.openStarted); status.putBoolean("signalReceived", entry.signalReceived);
            status.putBoolean("signalCancelled", entry.signalCancelled);
            status.putBoolean("openCancelled", entry.openCancelled); status.putBoolean("opened", entry.opened);
            status.putBoolean("writerFailed", entry.writerFailed); status.putBoolean("writeComplete", entry.writeComplete);
            status.putInt("bytesWritten", entry.bytesWritten);
            status.putInt("opens", entry.opens.get());
            status.putString("clientLink", entry.clientLink);
            return status;
        } catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
    }

    private void requireDocumentGrant(Entry entry, int flags) {
        if (Binder.getCallingUid() == getContext().getApplicationInfo().uid) return;
        Uri uri = DocumentsContract.buildDocumentUri(authority(getContext()), entry.id);
        if (getContext().checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(), flags)
            != PackageManager.PERMISSION_GRANTED) throw new SecurityException("缺少本夹具文档授权");
    }

    /** 跨 APK 的实际 ActivityResult + ClipData 授权，不提升或移除 MANAGE_DOCUMENTS。 */
    public static final class GrantActivity extends Activity {
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            if (!TARGET.equals(getCallingPackage()) || !getPackageName().equals(TARGET + ".test")) {
                setResult(RESULT_CANCELED); finish(); return;
            }
            try {
                Bundle arguments = new Bundle();
                arguments.putString("behavior", getIntent().getStringExtra(BEHAVIOR_EXTRA));
                arguments.putString("mime", getIntent().getStringExtra(MIME_EXTRA) == null ? "application/json" : getIntent().getStringExtra(MIME_EXTRA));
                arguments.putInt("imageBytes", getIntent().getIntExtra(IMAGE_BYTES_EXTRA, 0));
                byte[] payload = getIntent().getByteArrayExtra(PAYLOAD_EXTRA);
                if (payload != null) arguments.putByteArray("payload", payload);
                Bundle created = getContentResolver().call(authority(this), PREFIX + "create", null, arguments);
                if (created == null || created.getString("uri") == null) throw new IllegalStateException("无法创建测试文档");
                Uri uri = Uri.parse(created.getString("uri"));
                Intent result = new Intent().setDataAndType(uri, created.getString("mime", "application/json"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                result.setClipData(ClipData.newRawUri("Pathfin-verification-settings", uri));
                setResult(RESULT_OK, result);
            } catch (RuntimeException error) { setResult(RESULT_CANCELED); }
            finish();
        }
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        requireCaller();
        Entry entry = entry(id);
        boolean writing = mode.contains("w");
        boolean ignoreReadCancellation = entry.behavior.equals("DELAY_OPEN_IGNORE_CANCEL");
        boolean ignoreCancellation = ignoreReadCancellation || (writing && entry.behavior.equals("DELAY_WRITE_OPEN_IGNORE_CANCEL"));
        if (ignoreReadCancellation && (!mode.equals("r") || signal == null))
            throw new FileNotFoundException("迟到 open 夹具仅允许带取消信号的图片读取");
        entry.opens.incrementAndGet();
        entry.signalReceived = signal != null;
        entry.openStarted = true;
        if (signal != null) signal.setOnCancelListener(() -> {
            entry.signalCancelled = true;
            if (entry.behavior.equals("DELAY_OPEN")) entry.release.countDown();
        });
        if (entry.behavior.equals("DELAY_OPEN") || ignoreCancellation) {
            awaitRelease(entry);
            if (ignoreReadCancellation) {
                // 必须由测试先取消再显式放行，故意返回取消后的真实 PNG FD。
                if (!signal.isCanceled()) throw new FileNotFoundException("迟到 open 必须在取消后放行");
            } else if (!ignoreCancellation && signal != null && signal.isCanceled()) { entry.openCancelled = true; signal.throwIfCanceled(); }
        }
        if (signal != null && !ignoreCancellation) signal.throwIfCanceled();
        try {
            ParcelFileDescriptor[] pair = writing && entry.behavior.equals("BLOCK_WRITE")
                ? ParcelFileDescriptor.createReliableSocketPair() : ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor client = writing ? pair[1] : pair[0];
            ParcelFileDescriptor peer = writing ? pair[0] : pair[1];
            entry.peer = peer;
            try {
                entry.clientLink = Os.readlink("/proc/self/fd/" + client.getFd());
                if (writing && entry.behavior.equals("BLOCK_WRITE")) Os.setsockoptInt(client.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 1024);
                pipes.execute(() -> {
                    if (writing) readExport(entry, peer); else writeImport(entry, peer);
                });
                entry.opened = true;
                return client;
            } catch (Exception error) {
                try { client.close(); } finally { peer.close(); }
                throw error;
            }
        } catch (Exception error) { throw new FileNotFoundException("夹具管道打开失败: " + error.getClass().getSimpleName()); }
    }

    private static void awaitRelease(Entry entry) throws FileNotFoundException {
        try { if (!entry.release.await(12, TimeUnit.SECONDS)) throw new FileNotFoundException("夹具门闩超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new FileNotFoundException("夹具等待被中断"); }
    }

    private static void writeImport(Entry entry, ParcelFileDescriptor peer) {
        try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(peer)) {
            if (entry.behavior.equals("BLOCK_READ")) awaitRelease(entry);
            output.write(entry.payload); output.flush();
        } catch (IOException error) { entry.writerFailed = true; }
    }

    private static void readExport(Entry entry, ParcelFileDescriptor peer) {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(peer)) {
            if (entry.behavior.equals("BLOCK_WRITE")) awaitRelease(entry);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read); entry.bytesWritten = bytes.size();
                if (bytes.size() > LIMIT) throw new IOException("夹具导出超限");
            }
            entry.payload = bytes.toByteArray(); entry.writeComplete = true;
        } catch (IOException error) { entry.writerFailed = true; }
    }

    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        requireCaller(); Entry entry = entry(id);
        String[] columns = projection != null ? projection : new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE};
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            if (column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID) || column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = id;
            else if (column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = entry.mime;
            else if (column.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            else if (column.equals(DocumentsContract.Document.COLUMN_SIZE)) value = entry.payload.length;
            row.add(column, value);
        }
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) { requireCaller(); return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}); }
    @Override public void deleteDocument(String id) throws FileNotFoundException { requireCaller(); deleteEntry(entry(id)); }
    private void deleteEntry(Entry entry) {
        entry.release.countDown();
        if (entry.peer != null) try { entry.peer.close(); } catch (IOException ignored) { }
        documents.remove(entry.id, entry);
        long token = Binder.clearCallingIdentity();
        try { getContext().revokeUriPermission(DocumentsContract.buildDocumentUri(authority(getContext()), entry.id), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        finally { Binder.restoreCallingIdentity(token); }
    }
}
