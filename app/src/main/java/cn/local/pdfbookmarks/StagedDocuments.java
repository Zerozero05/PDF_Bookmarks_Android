package cn.local.pdfbookmarks;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在已持久授权的目录中创建暂存文件和改名；绝不打开已有文档的截断写入流。 */
public final class StagedDocuments implements StagedReplacement.Provider {
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS};
    private final ContentResolver resolver;
    private final String stagingMime;
    public final Uri folder;
    private final Set<String> writableNewIds = new HashSet<>();

    public StagedDocuments(Context context, Uri folder) throws IOException {
        this(context, folder, "application/pdf");
    }

    private StagedDocuments(Context context, Uri folder, String stagingMime) throws IOException {
        this.folder = PathDocuments.writableFolder(context, folder);
        resolver = context.getContentResolver();
        this.stagingMime = stagingMime;
        Entry parent = metadata(this.folder);
        if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(parent.mime)
                || !supports(parent, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)) {
            throw new IOException(isCatalog()
                    ? "该文件夹不支持创建暂存文件，不能安全更新享做目录。"
                    : "该文件夹不支持创建暂存文件，不能同名替换；请另存 PDF。");
        }
    }

    /** 与 PDF 改名替换相同的权限及身份检查，只将新建暂存文件声明为 JSON。 */
    public static StagedDocuments forCatalog(Context context, Uri folder) throws IOException {
        return new StagedDocuments(context, folder, "application/json");
    }

    public static StagedDocuments forDocument(Context context, Uri source) throws IOException {
        StagedDocuments provider = forMissingDocument(context, source);
        Entry original = provider.child(source);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(original.mime)
                || !supports(original, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)) {
            throw new IOException("原 PDF 的文件提供者不支持改名，不能同名替换；请另存 PDF。");
        }
        return provider;
    }

    /** 仅用于旧恢复记录中原 URI 已不存在的情况，不会创建或截断原文件。 */
    public static StagedDocuments forMissingDocument(Context context, Uri source) throws IOException {
        return new StagedDocuments(context, PathDocuments.folderForDocument(context, source));
    }

    @Override public List<StagedReplacement.Document> find(String name) throws IOException {
        List<StagedReplacement.Document> found = new ArrayList<>();
        for (Entry item : children()) if (name.equals(item.name)) found.add(document(item));
        return found;
    }

    /** One fresh listing for a read-only group; duplicates and missing names remain explicit. */
    public Map<String, List<StagedReplacement.Document>> findAll(List<String> names) throws IOException {
        Set<String> requested = new HashSet<>(names);
        List<StagedReplacement.Document> listed = new ArrayList<>();
        for (Entry item : children()) if (requested.contains(item.name)) listed.add(document(item));
        return indexByName(listed, names);
    }

    static Map<String, List<StagedReplacement.Document>> indexByName(
            List<StagedReplacement.Document> listed, List<String> names) {
        Map<String, List<StagedReplacement.Document>> grouped = new LinkedHashMap<>();
        for (String name : names) grouped.computeIfAbsent(name, ignored -> new ArrayList<>());
        for (StagedReplacement.Document document : listed) {
            List<StagedReplacement.Document> matches = grouped.get(document.name);
            if (matches != null) matches.add(document);
        }
        Map<String, List<StagedReplacement.Document>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<StagedReplacement.Document>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override public StagedReplacement.Document create(String name) throws IOException {
        Set<String> oldIds = new HashSet<>();
        for (Entry item : children()) {
            oldIds.add(item.id);
            if (name.equals(item.name)) throw new IOException("暂存文件名已存在，停止创建以保护原文件。");
        }
        Uri created = DocumentsContract.createDocument(resolver, folder, stagingMime, name);
        if (created == null) throw new IOException("文件提供者没有返回新建暂存文件。");
        Entry item = child(created);
        if (oldIds.contains(item.id) || !name.equals(item.name)
                || DocumentsContract.Document.MIME_TYPE_DIR.equals(item.mime)
                || !supports(item, DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
                || !supports(item, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)) {
            throw new IOException("暂存文件名称或读写/改名能力不符合要求；未写入原 " + kind() + "。");
        }
        writableNewIds.add(item.id);
        return document(item);
    }

    @Override public InputStream openRead(StagedReplacement.Document document) throws IOException {
        Entry item = child(Uri.parse(document.uri));
        if (!document.name.equals(item.name)) throw new IOException("文件名称在处理期间发生变化，停止读取。");
        InputStream stream = resolver.openInputStream(uri(item.id));
        if (stream == null) throw new IOException("文件提供者未返回读取流。");
        return WorkMetrics.providerRead(stream);
    }

    @Override public OutputStream openWriteNew(StagedReplacement.Document document) throws IOException {
        Entry item = child(Uri.parse(document.uri));
        if (!document.name.equals(item.name) || !writableNewIds.remove(item.id)
                || !supports(item, DocumentsContract.Document.FLAG_SUPPORTS_WRITE)) {
            throw new IOException("只允许写入本次刚创建的暂存文件；不会截断已有 " + kind() + "。");
        }
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri(item.id), "w");
        if (descriptor == null) throw new IOException("文件提供者未返回暂存文件写入流。");
        ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
        return new OutputStream() {
            private boolean closed;
            @Override public void write(int value) throws IOException { stream.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { stream.write(bytes, offset, length); }
            @Override public void flush() throws IOException { stream.flush(); }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try { stream.flush(); descriptor.getFileDescriptor().sync(); }
                finally { stream.close(); }
            }
        };
    }

    @Override public StagedReplacement.Document rename(StagedReplacement.Document document, String name) throws IOException {
        Entry before = child(Uri.parse(document.uri));
        if (!document.name.equals(before.name) || !supports(before, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)) {
            throw new IOException("文件名称已变化或不支持改名，停止替换。");
        }
        if (!find(name).isEmpty()) throw new IOException("目标名称已存在，停止改名以保护已有文件。");
        Uri renamed = DocumentsContract.renameDocument(resolver, uri(before.id), name);
        if (renamed == null) throw new IOException("文件提供者未返回改名后的文档；需要重新核对目录。");
        Entry after = child(renamed);
        if (!name.equals(after.name)) throw new IOException("文件提供者没有保留预期名称；需要恢复原 " + kind() + "。");
        writableNewIds.remove(before.id);
        return document(after);
    }

    private List<Entry> children() throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder));
        List<Entry> items = new ArrayList<>();
        WorkMetrics.directoryQuery();
        try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("无法列出授权文件夹，停止处理。");
            while (cursor.moveToNext()) items.add(entry(cursor));
        }
        return items;
    }

    private Entry child(Uri document) throws IOException {
        if (!folder.getAuthority().equals(document.getAuthority())) throw new IOException("文档不属于授权文件提供者。");
        String id;
        try { id = DocumentsContract.getDocumentId(document); }
        catch (IllegalArgumentException e) { throw new IOException("文档地址无效。", e); }
        Uri exact = uri(id);
        return currentChild(DocumentsContract.getTreeDocumentId(folder),
                DocumentsContract.getDocumentId(folder), id, new ChildLookup() {
                    public List<String> path() throws IOException {
                        DocumentsContract.Path path = DocumentsContract.findDocumentPath(resolver, exact);
                        return path == null ? null : path.getPath();
                    }
                    public Entry metadata() throws IOException { return StagedDocuments.this.metadata(exact); }
                    public Entry listed() throws IOException {
                        for (Entry item : children()) if (id.equals(item.id)) return item;
                        throw new IOException("文档不在授权原文件夹中，或已被移动/删除。");
                    }
                });
    }

    interface ChildLookup {
        List<String> path() throws IOException;
        Entry metadata() throws IOException;
        Entry listed() throws IOException;
    }

    /** Proves the immediate parent afresh; a descendant or cached path is insufficient. */
    static Entry currentChild(String treeId, String folderId, String id, ChildLookup lookup) throws IOException {
        final List<String> path;
        try { path = lookup.path(); }
        catch (UnsupportedOperationException unsupported) { return lookup.listed(); }
        if (path == null) return lookup.listed();
        if (path.isEmpty() || !treeId.equals(path.get(0)) || !id.equals(path.get(path.size() - 1))
                || path.contains(null)) {
            throw new IOException("文件提供者返回的文档路径与授权身份不一致。");
        }
        // A provider may choose another canonical path for a document with several parents.
        if (path.size() < 2 || !folderId.equals(path.get(path.size() - 2))) return lookup.listed();
        Entry current = lookup.metadata();
        if (current == null || !id.equals(current.id)) throw new IOException("文档标识在读取期间发生变化。");
        return current;
    }

    private Entry metadata(Uri document) throws IOException {
        List<Entry> entries = new ArrayList<>(2);
        try (Cursor cursor = resolver.query(document, COLUMNS, null, null, null)) {
            if (cursor != null) while (entries.size() < 2 && cursor.moveToNext()) entries.add(entry(cursor));
        }
        return singleMetadata(DocumentsContract.getDocumentId(document), entries);
    }

    static Entry singleMetadata(String id, List<Entry> entries) throws IOException {
        if (entries.size() != 1 || !id.equals(entries.get(0).id)) {
            throw new IOException("文件提供者未返回唯一且身份一致的文档信息。");
        }
        return entries.get(0);
    }

    private Uri uri(String id) { return DocumentsContract.buildDocumentUriUsingTree(folder, id); }
    private boolean isCatalog() { return "application/json".equals(stagingMime); }
    private String kind() { return isCatalog() ? "目录文件" : "PDF"; }
    private StagedReplacement.Document document(Entry item) {
        return new StagedReplacement.Document(uri(item.id).toString(), item.name);
    }
    private static boolean supports(Entry item, int flag) { return (item.flags & flag) != 0; }
    private static Entry entry(Cursor cursor) throws IOException {
        String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
        if (id == null || name == null || mime == null || cursor.isNull(3)) throw new IOException("文件提供者返回的文档信息不完整。");
        return new Entry(id, name, mime, cursor.getInt(3));
    }
    static final class Entry {
        final String id, name, mime;
        final int flags;
        Entry(String id, String name, String mime, int flags) {
            this.id = id; this.name = name; this.mime = mime; this.flags = flags;
        }
    }
}
