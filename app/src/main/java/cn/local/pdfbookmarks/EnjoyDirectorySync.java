package cn.local.pdfbookmarks;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 仅同步已核实的享做主目录；调用方须串行执行，并先请用户保存、完全停止享做。 */
public final class EnjoyDirectorySync {
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final String PENDING = "pending", LAST = "last";
    private static final Gson JSON = new Gson();
    private final Context context;
    private final SharedPreferences prefs;

    public EnjoyDirectorySync(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("enjoy-directory", Context.MODE_PRIVATE);
    }

    public static final class Record {
        public String folder, outputUri, outputName, outputHash, backupUri, backupName;
        public String originalHash, privateBackup, pdfName, pdfHash;
        public boolean discardAfterReplacement;
        public transient String snapshot;
    }

    private static final class Snapshot {
        String name, uri, hash;
        Snapshot(String name, StagedReplacement.Document document, String hash) {
            this.name = name; this.uri = document == null ? null : document.uri; this.hash = hash;
        }
    }

    public static final class Prepared {
        public final EnjoyCatalog.Plan plan;
        private final StagedDocuments documents;
        private final String pdfName;
        private final List<Snapshot> snapshots;
        private final byte[] original;
        private Prepared(StagedDocuments documents, String pdfName, List<Snapshot> snapshots,
                         byte[] original, EnjoyCatalog.Plan plan) {
            this.documents = documents; this.pdfName = pdfName; this.snapshots = snapshots;
            this.original = original; this.plan = plan;
        }
    }

    private static final class Pending {
        String folder, pdfName, pdfHash, privateBackup;
        List<Snapshot> snapshots;
        StagedReplacement.Journal journal;
    }

    /** 仅识别共享存储中同名笔记资源目录里的 PDF，不按宽泛目录名称猜测。 */
    public static boolean recognizes(Uri pdf) {
        if (pdf == null || !"content".equals(pdf.getScheme()) || !AUTHORITY.equals(pdf.getAuthority())) return false;
        try {
            String id = DocumentsContract.getDocumentId(pdf);
            String[] parts = id.split("/", -1);
            if (parts.length != 4 || !"primary:享做笔记".equals(parts[0]) || !"note".equals(parts[1])
                    || !parts[2].endsWith("__res__")) return false;
            String note = parts[2].substring(0, parts[2].length() - 7);
            return note.matches("note_[A-Za-z0-9_-]+")
                    && (parts[3].equals(note + ".pdf") || parts[3].equals("." + note + ".pdf"));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public boolean hasPending() { return prefs.contains(PENDING); }

    /** 成功记录同时保存删除意图；重启不依赖调用方已开始删除。 */
    public String automaticCleanupRecord() {
        String raw = prefs.getString(LAST, null);
        try {
            Record record = JSON.fromJson(raw, Record.class);
            return validRecord(record) && record.discardAfterReplacement ? raw : null;
        } catch (RuntimeException invalid) { return null; }
    }

    public interface ReplacementCleanup {
        void discard(Record record) throws IOException;
    }

    public Prepared prepare(Uri pdf, int actualPages, List<TocParser.Row> rows,
                            String expectedPdfHash) throws IOException {
        return prepare(pdf, actualPages, rows, expectedPdfHash, false);
    }

    public Prepared prepare(Uri pdf, int actualPages, List<TocParser.Row> rows,
                            String expectedPdfHash, boolean replaceExisting) throws IOException {
        if (hasPending() || automaticCleanupRecord() != null) throw new IOException("享做目录同步或旧目录清理尚待完成，请先处理记录。");
        if (!recognizes(pdf)) throw new IOException("当前路径不是已核实的享做笔记资源 PDF，未修改享做目录。");
        StagedDocuments documents = StagedDocuments.forCatalog(context, PathDocuments.folderForDocument(context, pdf));
        String id = DocumentsContract.getDocumentId(pdf), name = id.substring(id.lastIndexOf('/') + 1);
        StagedReplacement.Document selected = checkedPdf(documents, name, validHash(expectedPdfHash));
        if (!sameDocument(pdf, Uri.parse(selected.uri))) throw new IOException("PDF 的名称或标识变化，请重新预览。");
        List<Snapshot> snapshots = new ArrayList<>();
        byte[] original = snapshot(documents, "catalog.json", false, snapshots);
        byte[] doc = snapshot(documents, "doc", false, snapshots);
        byte[] pageids = snapshot(documents, "pageids.json", false, snapshots);
        snapshot(documents, "page_usn.json", true, snapshots);
        snapshot(documents, "catalog.json.temp", true, snapshots);
        EnjoyCatalog.Plan plan = EnjoyCatalog.merge(original, pageids, doc,
                "/storage/emulated/0/" + id.substring(8), actualPages, rows, replaceExisting);
        verifySnapshots(documents, snapshots, false);
        checkedPdf(documents, name, expectedPdfHash);
        return new Prepared(documents, name, snapshots, original, plan);
    }

    /** PDF 已在原路径保存校验后调用；两次同步相同目录时不创建文件、不改计数。 */
    public String apply(Prepared prepared, String expectedFinalPdfHash) throws IOException {
        return apply(prepared, expectedFinalPdfHash, null);
    }

    public String apply(Prepared prepared, String expectedFinalPdfHash,
                        ReplacementCleanup cleanup) throws IOException {
        if (prepared == null || hasPending() || automaticCleanupRecord() != null) throw new IOException("缺少同步预览，或存在待处理的享做目录任务。");
        String pdfHash = validHash(expectedFinalPdfHash);
        StagedDocuments documents = prepared.documents;
        verifySnapshots(documents, prepared.snapshots, false);
        checkedPdf(documents, prepared.pdfName, pdfHash);
        if (prepared.plan.addedCount == 0) {
            verifySnapshots(documents, prepared.snapshots, false);
            return prepared.plan.replacesExisting
                    ? "享做现有目录已与新目录完全相同，无需重写；未新增备份。"
                    : "享做目录已包含本次全部条目，无需重写；原有目录保留。";
        }
        if (prepared.plan.replacesExisting && cleanup == null) throw new IOException("缺少替换后旧目录清理步骤，未改动目录。");
        File folder = new File(context.getFilesDir(), "enjoy-catalog-backups");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法保存享做原目录备份，未同步目录。");
        String token = UUID.randomUUID().toString();
        File backup = new File(folder, token + ".json");
        writeNew(backup, prepared.original);
        File candidate = new File(context.getCacheDir(), "enjoy-catalog-" + token + ".json");
        writeNew(candidate, prepared.plan.json);
        Pending pending = new Pending();
        pending.folder = documents.folder.toString(); pending.pdfName = prepared.pdfName;
        pending.pdfHash = pdfHash; pending.privateBackup = backup.getAbsolutePath();
        pending.snapshots = prepared.snapshots;
        StagedReplacement.Provider guarded = new StagedReplacement.Provider() {
            private boolean checkBeforeRename = true;
            public List<StagedReplacement.Document> find(String name) throws IOException { return documents.find(name); }
            public StagedReplacement.Document create(String name) throws IOException { return documents.create(name); }
            public InputStream openRead(StagedReplacement.Document document) throws IOException { return documents.openRead(document); }
            public OutputStream openWriteNew(StagedReplacement.Document document) throws IOException { return documents.openWriteNew(document); }
            public StagedReplacement.Document rename(StagedReplacement.Document document, String name) throws IOException {
                if (checkBeforeRename && "catalog.json".equals(document.name) && ownBackup(name)) {
                    verifySnapshots(documents, prepared.snapshots, false);
                    checkedPdf(documents, prepared.pdfName, pdfHash);
                    checkBeforeRename = false;
                }
                return documents.rename(document, name);
            }
        };
        StagedReplacement.Result result = StagedReplacement.replaceCatalog(guarded, "catalog.json", candidate,
                digest(prepared.original), journal -> savePending(pending, journal));
        checkedPdf(documents, prepared.pdfName, pdfHash);
        verifySnapshots(documents, prepared.snapshots, true);
        checkedDocument(documents, result.document, result.journal.outputHash);
        checkedDocument(documents, result.backup, result.journal.originalHash);
        privateBackup(pending.privateBackup, result.journal.originalHash);
        Record record = new Record();
        record.folder = pending.folder; record.pdfName = pending.pdfName; record.pdfHash = pdfHash;
        record.outputUri = result.document.uri; record.outputName = result.document.name;
        record.outputHash = result.journal.outputHash; record.backupUri = result.backup.uri;
        record.backupName = result.backup.name; record.originalHash = result.journal.originalHash;
        record.privateBackup = pending.privateBackup;
        record.discardAfterReplacement = prepared.plan.replacesExisting;
        record.snapshot = JSON.toJson(record);
        String savedPending = prefs.getString(PENDING, null);
        if (!prefs.edit().putString(LAST, record.snapshot).remove(PENDING).commit()) {
            // commit 失败也会先改变内存值；重新保留待恢复记录，不能显示成功。
            if (savedPending != null) prefs.edit().putString(PENDING, savedPending).commit();
            throw new IOException("享做新目录已核验，但同步记录未保存；备份及恢复记录保留。");
        }
        if (prepared.plan.replacesExisting) {
            try { cleanup.discard(record); }
            catch (IOException failure) {
                throw new IOException("享做新目录已替换并核验，但本次旧目录清理未完成：" + failure.getMessage(), failure);
            }
            return "享做目录已保存并核验：用新 " + prepared.plan.totalCount + " 条替换原有 "
                    + prepared.plan.originalCount + " 条，本次旧目录及内部目录恢复副本已删除。请重开享做核对跳转。";
        }
        return "享做目录已保存并核验：保留原有 " + prepared.plan.originalCount + " 条，新增 "
                + prepared.plan.addedCount + " 条，共 " + prepared.plan.totalCount + " 条。请重开享做核对跳转。";
    }

    /** UI 只读取本工具成功记录；不会从文件名前缀猜测其他备份的归属。 */
    public Record recordFor(Uri pdf) {
        if (!recognizes(pdf)) return null;
        String raw = prefs.getString(LAST, null);
        try {
            Record record = JSON.fromJson(raw, Record.class);
            if (!validRecord(record) || !record.pdfName.equals(name(pdf))
                    || !parent(pdf).equals(DocumentsContract.getDocumentId(Uri.parse(record.folder)))) return null;
            record.snapshot = raw;
            return record;
        } catch (RuntimeException invalid) { return null; }
    }

    /** 用户核对成功并确认后，仅删除本工具记录的同目录旧 catalog 备份。 */
    public boolean cleanup(Record shownRecord) throws IOException {
        if (!validRecord(shownRecord) || shownRecord.snapshot == null || hasPending()
                || !shownRecord.snapshot.equals(prefs.getString(LAST, null))) {
            throw new IOException("目录备份记录已变化或尚待恢复，未执行清理。");
        }
        // 重新解析持久快照，不使用可被调用者改动的公开显示字段。
        Record record = JSON.fromJson(shownRecord.snapshot, Record.class);
        File backup = privateBackup(record.privateBackup, record.originalHash);
        StagedDocuments documents = StagedDocuments.forCatalog(context, Uri.parse(record.folder));
        checkedPdf(documents, record.pdfName, record.pdfHash);
        StagedReplacement.Document output = new StagedReplacement.Document(record.outputUri, record.outputName);
        StagedReplacement.Document old = new StagedReplacement.Document(record.backupUri, record.backupName);
        boolean removed = BackupCleanup.remove(new BackupCleanup.Provider() {
            public List<StagedReplacement.Document> find(String name) throws IOException { return documents.find(name); }
            public InputStream openRead(StagedReplacement.Document document) throws IOException { return documents.openRead(document); }
            public boolean delete(StagedReplacement.Document document) throws IOException {
                if (!record.backupName.equals(document.name) || !ownBackup(document.name)) throw new IOException("禁止删除活动目录或未记录的文件。");
                StagedReplacement.Document current = unique(documents, record.backupName, false);
                if (!current.uri.equals(document.uri) || !current.uri.equals(record.backupUri)
                        || current.uri.equals(record.outputUri)) throw new IOException("旧目录备份身份变化，未执行删除。");
                checkedPdf(documents, record.pdfName, record.pdfHash);
                Uri uri = Uri.parse(current.uri);
                if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("旧目录备份没有删除授权。");
                }
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)
                            || (cursor.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) {
                        throw new IOException("文件来源不支持删除旧目录备份。");
                    }
                }
                return DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            }
        }, output, old, record.outputHash, record.originalHash, backup);
        checkedPdf(documents, record.pdfName, record.pdfHash);
        removePersisted(LAST, shownRecord.snapshot);
        return removed;
    }

    /** 统一清理只使用确认过的成功快照；resume 仅用于调用方已持久保存的清理任务。 */
    public BackupCleanup.Item cleanupItem(String raw, boolean resume) throws IOException {
        Record record = cleanupRecord(raw, resume);
        File backup = cleanupPrivatePath(record.privateBackup);
        StagedDocuments documents = StagedDocuments.forCatalog(context, Uri.parse(record.folder));
        verifyCleanupRecord(raw, resume);
        BackupCleanup.Provider provider = new BackupCleanup.Provider() {
            public List<StagedReplacement.Document> find(String name) throws IOException { return documents.find(name); }
            public InputStream openRead(StagedReplacement.Document document) throws IOException { return documents.openRead(document); }
            public boolean delete(StagedReplacement.Document document) throws IOException {
                verifyCleanupRecord(raw, resume);
                if (!record.backupName.equals(document.name) || !ownBackup(document.name)) {
                    throw new IOException("禁止删除活动目录或未记录的文件。");
                }
                StagedReplacement.Document current = unique(documents, record.backupName, false);
                if (!current.uri.equals(document.uri) || !current.uri.equals(record.backupUri)
                        || sameDocument(Uri.parse(current.uri), Uri.parse(record.outputUri))) {
                    throw new IOException("旧目录备份身份变化，未执行删除。");
                }
                Uri uri = Uri.parse(current.uri);
                if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("旧目录备份没有删除授权。");
                }
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)
                            || (cursor.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) {
                        throw new IOException("文件来源不支持删除旧目录备份。");
                    }
                }
                verifyCleanupRecord(raw, resume);
                return DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            }
        };
        return new BackupCleanup.Item(provider,
                new StagedReplacement.Document(record.outputUri, record.outputName),
                new StagedReplacement.Document(record.backupUri, record.backupName),
                record.outputHash, record.originalHash, backup);
    }

    /** 清理前及每个删除阶段重查成功快照、活动 PDF 和活动目录，未知变化立即停止。 */
    public void verifyCleanupRecord(String raw, boolean resume) throws IOException {
        Record record = cleanupRecord(raw, resume);
        StagedDocuments documents = StagedDocuments.forCatalog(context, Uri.parse(record.folder));
        StagedReplacement.Document pdf = checkedPdf(documents, record.pdfName, record.pdfHash);
        if (!sameDocument(Uri.parse(pdf.uri), pdfUri(record.folder, record.pdfName))) {
            throw new IOException("PDF 标识变化，未执行清理。");
        }
        checkedDocument(documents, new StagedReplacement.Document(record.outputUri, record.outputName), record.outputHash);
        checkedPdf(documents, record.pdfName, record.pdfHash);
        cleanupRecord(raw, resume);
    }

    /** 调用方先核验整批删除完成；此处再次确认本目录的两个旧备份已不存在。 */
    public void finishCleanup(String raw) throws IOException {
        Record record = cleanupRecord(raw, true);
        verifyCleanupRecord(raw, true);
        StagedDocuments documents = StagedDocuments.forCatalog(context, Uri.parse(record.folder));
        if (!documents.find(record.backupName).isEmpty() || cleanupPrivatePath(record.privateBackup).exists()) {
            throw new IOException("旧目录备份尚未全部删除，清理记录保留。");
        }
        String current = prefs.getString(LAST, null);
        if (current != null) removePersisted(LAST, raw);
    }

    /** 调用方已持久归档原清单并结束删除；剩余文件与关联记录保留。 */
    public void stopAutomaticCleanup(String raw) throws IOException {
        String current = prefs.getString(LAST, null);
        if (current == null) return;
        if (!current.equals(raw)) throw new IOException("目录清理记录已变化，未结束清理。");
        Record record = cleanupRecord(raw, false);
        if (!record.discardAfterReplacement) return;
        record.discardAfterReplacement = false;
        if (!prefs.edit().putString(LAST, JSON.toJson(record)).commit()) {
            prefs.edit().putString(LAST, raw).commit();
            throw new IOException("旧目录清理状态未保存，仍可按原清单重试。");
        }
    }

    private Record cleanupRecord(String raw, boolean resume) throws IOException {
        String current = prefs.getString(LAST, null);
        if (raw == null || hasPending() || (!raw.equals(current) && (!resume || current != null))) {
            throw new IOException("目录备份记录已变化或尚待恢复，未执行清理。");
        }
        final Record record;
        try { record = JSON.fromJson(raw, Record.class); }
        catch (RuntimeException invalid) { throw new IOException("目录清理快照无效，未执行清理。", invalid); }
        if (!validRecord(record)) throw new IOException("目录清理快照不完整，未执行清理。");
        cleanupPrivatePath(record.privateBackup);
        return record;
    }

    private File cleanupPrivatePath(String path) throws IOException {
        if (path == null) throw new IOException("缺少应用内原目录备份位置。");
        File suppliedFolder = new File(context.getFilesDir(), "enjoy-catalog-backups").getAbsoluteFile();
        File folder = new File(context.getFilesDir().getCanonicalFile(), "enjoy-catalog-backups");
        File saved = new File(path).getAbsoluteFile(), file = saved.getCanonicalFile();
        if (!folder.equals(suppliedFolder.getCanonicalFile())
                || (!suppliedFolder.equals(saved.getParentFile()) && !folder.equals(saved.getParentFile()))
                || !file.equals(new File(folder, saved.getName()))
                || !folder.equals(file.getParentFile())
                || !file.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")) {
            throw new IOException("原目录备份位置无法确认，未执行清理。");
        }
        return file;
    }

    /** 只回退主目录；新 PDF、临时目录及全部页配置都不作为写入目标。 */
    public void recover() throws IOException {
        String raw = prefs.getString(PENDING, null);
        if (raw == null) throw new IOException("没有待恢复的享做目录任务。");
        final Pending pending;
        try { pending = JSON.fromJson(raw, Pending.class); }
        catch (RuntimeException invalid) { throw new IOException("享做目录恢复记录无效，所有备份保留。", invalid); }
        if (!validPending(pending)) {
            throw new IOException("享做目录恢复记录不完整，未执行恢复。");
        }
        privateBackup(pending.privateBackup, pending.journal.originalHash);
        StagedDocuments documents = StagedDocuments.forCatalog(context, Uri.parse(pending.folder));
        checkedPdf(documents, pending.pdfName, pending.pdfHash);
        verifySnapshots(documents, pending.snapshots, true);
        StagedReplacement.Result restored = StagedReplacement.recover(documents, pending.journal, journal -> savePending(pending, journal));
        checkedPdf(documents, pending.pdfName, pending.pdfHash);
        verifySnapshots(documents, pending.snapshots, true);
        checkedDocument(documents, restored.document, pending.journal.originalHash);
        privateBackup(pending.privateBackup, pending.journal.originalHash);
        // 旧成功记录可能引用刚被回退的主目录，不能继续允许清理那个旧备份。
        String lastRaw = prefs.getString(LAST, null);
        Record last = recordFor(pdfUri(documents, pending.pdfName));
        if (last != null) removePersisted(LAST, lastRaw);
        removePersisted(PENDING, prefs.getString(PENDING, null));
    }

    private void savePending(Pending pending, StagedReplacement.Journal journal) throws IOException {
        pending.journal = journal;
        if (!prefs.edit().putString(PENDING, JSON.toJson(pending)).commit()) {
            throw new IOException("无法持久保存享做目录恢复记录，未继续切换名称。");
        }
    }

    private void removePersisted(String key, String raw) throws IOException {
        if (raw == null || !raw.equals(prefs.getString(key, null))) throw new IOException("持久记录已变化，未清除记录。");
        if (!prefs.edit().remove(key).commit()) {
            prefs.edit().putString(key, raw).commit();
            throw new IOException("文件核验完成，但记录未成功清除；备份与记录保留。");
        }
    }

    private static byte[] snapshot(StagedDocuments documents, String name, boolean optional,
                                   List<Snapshot> snapshots) throws IOException {
        StagedReplacement.Document document = unique(documents, name, optional);
        byte[] bytes = document == null ? null : readSmall(documents, document);
        snapshots.add(new Snapshot(name, document, bytes == null ? null : digest(bytes)));
        return bytes;
    }

    private static void verifySnapshots(StagedDocuments documents, List<Snapshot> snapshots,
                                        boolean skipCatalog) throws IOException {
        for (Snapshot snapshot : snapshots) {
            if (skipCatalog && "catalog.json".equals(snapshot.name)) continue;
            StagedReplacement.Document current = unique(documents, snapshot.name, snapshot.uri == null);
            if (snapshot.uri == null ? current != null : current == null || !snapshot.uri.equals(current.uri)
                    || !snapshot.hash.equals(digest(readSmall(documents, current)))) {
                throw new IOException("享做文件在预览后变化，请保存并完全停止享做后重新预览：" + snapshot.name);
            }
        }
    }

    private static StagedReplacement.Document checkedPdf(StagedDocuments documents, String name,
                                                        String expectedHash) throws IOException {
        StagedReplacement.Document pdf = unique(documents, name, false);
        try (InputStream input = documents.openRead(pdf)) {
            if (!validHash(expectedHash).equals(hash(input))) throw new IOException("PDF 在预览或同步后变化，请重新核对。");
        }
        return pdf;
    }

    private static void checkedDocument(StagedDocuments documents, StagedReplacement.Document expected,
                                        String expectedHash) throws IOException {
        StagedReplacement.Document current = unique(documents, expected.name, false);
        if (!sameDocument(Uri.parse(current.uri), Uri.parse(expected.uri))) throw new IOException("目录文件标识在核验期间变化，备份及记录保留。");
        try (InputStream input = documents.openRead(current)) {
            if (!validHash(expectedHash).equals(hash(input))) throw new IOException("目录文件内容在核验期间变化，备份及记录保留。");
        }
    }

    private static StagedReplacement.Document unique(StagedDocuments documents, String name,
                                                     boolean optional) throws IOException {
        List<StagedReplacement.Document> found = documents.find(name);
        if (found.isEmpty() && optional) return null;
        if (found.size() != 1) throw new IOException("享做资源文件不存在或同名不唯一：" + name);
        return found.get(0);
    }

    private static byte[] readSmall(StagedDocuments documents, StagedReplacement.Document document) throws IOException {
        try (InputStream input = documents.openRead(document); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                checkInterrupted();
                if (out.size() + count > 4 * 1024 * 1024) throw new IOException("享做配置超过4MB，未修改目录。");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void writeNew(File file, byte[] bytes) throws IOException {
        if (!file.createNewFile()) throw new IOException("备份或暂存文件已存在，未覆盖。");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
        try (InputStream input = new FileInputStream(file)) {
            if (!digest(bytes).equals(hash(input))) throw new IOException("应用内目录备份或候选未通过核验。");
        }
    }

    private File privateBackup(String path, String expectedHash) throws IOException {
        if (path == null) throw new IOException("缺少应用内原目录备份。");
        File file = new File(path).getCanonicalFile();
        File folder = new File(context.getFilesDir(), "enjoy-catalog-backups").getCanonicalFile();
        if (!folder.equals(file.getParentFile()) || !file.isFile()) throw new IOException("原目录备份位置无法确认。");
        try (InputStream input = new FileInputStream(file)) {
            if (!validHash(expectedHash).equals(hash(input))) throw new IOException("应用内原目录备份校验失败。");
        }
        return file;
    }

    private static boolean validRecord(Record record) {
        try {
            if (record == null || !"catalog.json".equals(record.outputName) || !ownBackup(record.backupName)
                    || record.outputUri == null || record.backupUri == null || record.privateBackup == null
                    || record.folder == null || record.pdfName == null) return false;
            String folder = DocumentsContract.getDocumentId(Uri.parse(record.folder));
            // 使用树 URI 构造同目录 PDF，识别规则同时限制文件夹与 PDF 同名。
            Uri pdf = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(record.folder), folder + "/" + record.pdfName);
            if (!recognizes(pdf) || !sameDocument(pdf, pdfUri(record.folder, record.pdfName))
                    || !sameDocument(Uri.parse(record.outputUri), pdfUri(record.folder, record.outputName))
                    || !sameDocument(Uri.parse(record.backupUri), pdfUri(record.folder, record.backupName))) return false;
            validHash(record.outputHash); validHash(record.originalHash); validHash(record.pdfHash);
            return true;
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private static boolean validPending(Pending pending) {
        try {
            if (pending == null || pending.journal == null || pending.folder == null || pending.pdfName == null
                    || pending.privateBackup == null || pending.snapshots == null) return false;
            StagedReplacement.Journal journal = pending.journal;
            if (!"catalog.json".equals(journal.originalName) || !ownBackup(journal.backupName)
                    || !journal.backupName.replace("-backup.json", "-staged.json").equals(journal.stagingName)
                    || !recognizes(pdfUri(pending.folder, pending.pdfName))) return false;
            for (String[] binding : new String[][]{{journal.originalUri,"catalog.json"},
                    {journal.stagingUri,journal.stagingName},{journal.backupUri,journal.backupName},{journal.documentUri,"catalog.json"}}) {
                if (binding[0] != null && !sameDocument(Uri.parse(binding[0]), pdfUri(pending.folder,binding[1]))) return false;
            }
            if (journal.originalUri == null) return false;
            validHash(pending.pdfHash);validHash(journal.originalHash);validHash(journal.outputHash);
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Snapshot snapshot : pending.snapshots) {
                if (snapshot == null || !names.add(snapshot.name)) return false;
                if (snapshot.uri != null) {
                    if (!sameDocument(Uri.parse(snapshot.uri), pdfUri(pending.folder,snapshot.name))) return false;
                    validHash(snapshot.hash);
                } else if (snapshot.hash != null || !java.util.Arrays.asList("catalog.json.temp","page_usn.json").contains(snapshot.name)) return false;
            }
            return names.equals(new java.util.HashSet<>(java.util.Arrays.asList("catalog.json","doc","pageids.json","page_usn.json","catalog.json.temp")));
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private static Uri pdfUri(StagedDocuments documents, String name) { return pdfUri(documents.folder.toString(), name); }
    private static Uri pdfUri(String folder, String name) {
        Uri uri = Uri.parse(folder);
        return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getDocumentId(uri) + "/" + name);
    }
    private static String name(Uri uri) { String id = DocumentsContract.getDocumentId(uri); return id.substring(id.lastIndexOf('/') + 1); }
    private static String parent(Uri uri) { String id = DocumentsContract.getDocumentId(uri); return id.substring(0, id.lastIndexOf('/')); }
    private static boolean sameDocument(Uri first, Uri second) {
        return first.getAuthority() != null && first.getAuthority().equals(second.getAuthority())
                && DocumentsContract.getDocumentId(first).equals(DocumentsContract.getDocumentId(second));
    }
    private static boolean ownBackup(String name) {
        return name != null && name.matches("pdf-bookmarks-catalog-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-backup\\.json");
    }
    private static String validHash(String value) throws IOException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) throw new IOException("缺少有效文件校验值。");
        return value.toLowerCase(Locale.ROOT);
    }
    private static String digest(byte[] bytes) throws IOException { return hash(new java.io.ByteArrayInputStream(bytes)); }
    private static String hash(InputStream input) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        byte[] buffer = new byte[64 * 1024]; int count;
        while ((count = input.read(buffer)) != -1) { checkInterrupted(); digest.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("享做目录任务已中断，备份与恢复记录保留。");
    }
}
