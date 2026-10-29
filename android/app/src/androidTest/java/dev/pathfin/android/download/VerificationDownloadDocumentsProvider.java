package dev.pathfin.android.download;

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
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真正的跨 APK SAF 文档与持久授权，只访问 test APK 私有的 Pathfin-verification 目录。
 * 使用纯 Java/平台 API，独立 provider 进程不依赖 target APK 的 Kotlin/应用类。
 */
public final class VerificationDownloadDocumentsProvider extends DocumentsProvider {
    public static final String TARGET_PACKAGE = "dev.pathfin.android.verification";
    public static final String DOCUMENT_ID_EXTRA = "pathfin.fixture.documentId";
    public static final String TREE_GRANT_EXTRA = "pathfin.fixture.treeGrant";
    public static final String CANCEL_RESULT_EXTRA = "pathfin.fixture.cancelResult";
    public static final String CREATE_CONTROL = "pathfin.fixture.download.create.";
    private static final String ROOT = "Pathfin-verification";
    private final ConcurrentHashMap<String, CreateGate> createGates = new ConcurrentHashMap<>();
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    @Override public boolean onCreate() { return true; }

    public static String authority(Context testContext) {
        try {
            return testContext.getPackageManager().getProviderInfo(
                new ComponentName(testContext, VerificationDownloadDocumentsProvider.class), 0).authority;
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("必须安装包含下载文档夹具的 instrumentation APK", error);
        }
    }

    private void requireVerificationCaller() {
        Context context = getContext();
        if (context == null) throw new SecurityException("Missing test context");
        int caller = Binder.getCallingUid();
        if (caller == context.getApplicationInfo().uid) return;
        try {
            if (caller == context.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid) return;
        } catch (PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("下载夹具仅允许 verification UID");
    }

    private static File root(Context context) {
        if (!context.getPackageName().equals(TARGET_PACKAGE + ".test"))
            throw new SecurityException("夹具只能在 verification test APK 中写入");
        File root = new File(context.getFilesDir(), ROOT);
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建自有测试目录");
        return root;
    }

    private static File file(Context context, String documentId) throws FileNotFoundException {
        if (documentId == null || !documentId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}(?: \\([1-9][0-9]*\\))?\\.mp4"))
            throw new FileNotFoundException("只能访问本夹具生成的 UUID 文档");
        return new File(root(context), documentId);
    }

    /** verification 须先持有真实测试树授权；控制调用本身不授予任何文档权限。 */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith(CREATE_CONTROL)) return super.call(method, arg, extras);
        requireVerificationCaller();
        if (Binder.getCallingUid() != getContext().getApplicationInfo().uid &&
            getContext().checkUriPermission(DocumentsContract.buildTreeDocumentUri(authority(getContext()), ROOT),
                Binder.getCallingPid(), Binder.getCallingUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("慢创建门闩需要真实测试树授权");
        try { file(getContext(), arg); }
        catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
        if (arg.contains(" (")) throw new IllegalArgumentException("门闩必须绑定原始 UUID 文件名");
        if (method.equals(CREATE_CONTROL + "register")) {
            if (createGates.size() >= 2 || createGates.putIfAbsent(arg, new CreateGate()) != null)
                throw new IllegalStateException("创建门闩已存在或达到夹具上限");
        }
        CreateGate gate = createGates.get(arg);
        if (gate == null) throw new IllegalArgumentException("未知创建门闩");
        if (method.equals(CREATE_CONTROL + "release")) gate.release.countDown();
        if (method.equals(CREATE_CONTROL + "remove")) {
            gate.release.countDown(); createGates.remove(arg, gate);
        }
        Bundle result = new Bundle();
        result.putInt("entered", gate.entered.get()); result.putInt("created", gate.created.get());
        return result;
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            if (column.equals(DocumentsContract.Root.COLUMN_ROOT_ID) || column.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID)) value = ROOT;
            else if (column.equals(DocumentsContract.Root.COLUMN_TITLE)) value = ROOT;
            else if (column.equals(DocumentsContract.Root.COLUMN_FLAGS)) value = DocumentsContract.Root.FLAG_LOCAL_ONLY;
            else if (column.equals(DocumentsContract.Root.COLUMN_MIME_TYPES)) value = "video/mp4";
            row.add(column, value);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        requireVerificationCaller();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        if (ROOT.equals(documentId)) addDocument(cursor, documentId, root(getContext()), true);
        else {
            File file = file(getContext(), documentId);
            if (!file.isFile()) throw new FileNotFoundException("测试文档不存在");
            addDocument(cursor, documentId, file, false);
        }
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        requireVerificationCaller();
        if (!ROOT.equals(parentId)) throw new FileNotFoundException("未知测试根目录");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] files = root(getContext()).listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile() && file.getName().matches("[a-f0-9-]{36}(?: \\([1-9][0-9]*\\))?\\.mp4")) addDocument(cursor, file.getName(), file, false);
        }
        return cursor;
    }

    private static void addDocument(MatrixCursor cursor, String id, File file, boolean directory) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) value = id;
            else if (column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = id;
            else if (column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = directory ? DocumentsContract.Document.MIME_TYPE_DIR : "video/mp4";
            else if (column.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            else if (column.equals(DocumentsContract.Document.COLUMN_SIZE)) value = directory ? 0L : file.length();
            else if (column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) value = file.lastModified();
            row.add(column, value);
        }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        requireVerificationCaller();
        File file = file(getContext(), documentId);
        if (!file.isFile()) throw new FileNotFoundException("目标必须已由授权 Activity 创建");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        requireVerificationCaller();
        File file = file(getContext(), documentId);
        if (file.exists() && !file.delete()) throw new FileNotFoundException("无法删除自有测试文档");
    }

    @Override public String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        requireVerificationCaller();
        if (!ROOT.equals(parentId) || !"video/mp4".equals(mimeType)) throw new FileNotFoundException("未知测试目录或类型");
        File target = file(getContext(), displayName);
        String requested = displayName.replaceFirst(" \\([1-9][0-9]*\\)\\.mp4$", ".mp4");
        CreateGate gate = createGates.get(requested);
        try {
            if (gate != null && gate.entered.incrementAndGet() == 1 && !gate.release.await(20, TimeUnit.SECONDS))
                throw new FileNotFoundException("慢创建夹具未在预算内释放");
            if (!target.createNewFile()) throw new FileNotFoundException("测试文档已存在，不可覆盖");
            if (gate != null) gate.created.incrementAndGet();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new FileNotFoundException("慢创建被中断");
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return displayName;
    }

    private static final class CreateGate {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger created = new AtomicInteger();
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        requireVerificationCaller();
        if (!ROOT.equals(parentId)) return false;
        try { return file(getContext(), documentId).isFile(); }
        catch (FileNotFoundException ignored) { return false; }
    }

    /** Android 的真实 result/ClipData grant，target 随后必须实际 takePersistableUriPermission。 */
    public static final class GrantActivity extends Activity {
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            if (!TARGET_PACKAGE.equals(getCallingPackage()) || !getPackageName().equals(TARGET_PACKAGE + ".test")) {
                setResult(RESULT_CANCELED); finish(); return;
            }
            if (getIntent().getBooleanExtra(CANCEL_RESULT_EXTRA, false)) {
                setResult(RESULT_CANCELED); finish(); return;
            }
            try {
                Uri uri;
                boolean tree = getIntent().getBooleanExtra(TREE_GRANT_EXTRA, false);
                if (tree) uri = DocumentsContract.buildTreeDocumentUri(authority(this), ROOT);
                else {
                    String id = getIntent().getStringExtra(DOCUMENT_ID_EXTRA);
                    File target = file(this, id);
                    if (!target.createNewFile()) throw new IOException("测试 UUID 文档已存在");
                    uri = DocumentsContract.buildDocumentUri(authority(this), id);
                }
                Intent result = new Intent().setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                if (tree) result.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                result.setClipData(ClipData.newRawUri("Pathfin-verification", uri));
                setResult(RESULT_OK, result);
            } catch (IOException | RuntimeException ignored) { setResult(RESULT_CANCELED); }
            finish();
        }
    }
}
