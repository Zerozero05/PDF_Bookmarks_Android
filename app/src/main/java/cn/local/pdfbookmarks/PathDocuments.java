package cn.local.pdfbookmarks;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.List;

/** 只在已有 SAF 读授权内解析路径，实际存在及可读性由调用方的读取流程核验。 */
public final class PathDocuments {
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private PathDocuments() {}

    /** 仅表示缺少或失效的文件夹读写授权，不能把格式或文件提供者错误当成授权问题。 */
    public static final class FolderAuthorizationRequiredException extends IOException {
        public FolderAuthorizationRequiredException(String message) { super(message); }
        public FolderAuthorizationRequiredException(String message, Throwable cause) { super(message, cause); }
    }

    public static Uri resolve(Context context, String input) throws IOException {
        String value = input == null ? "" : input.trim();
        if (value.regionMatches(true, 0, "content://", 0, 10)) {
            Uri uri = Uri.parse(value);
            if (uri.getAuthority() == null || uri.getAuthority().isEmpty()) throw new IOException("content:// 地址缺少文件提供者。");
            if (AUTHORITY.equals(uri.getAuthority()) && DocumentsContract.isDocumentUri(context, uri)) {
                try {
                    Uri preferred = resolveGranted(context, DocumentsContract.getDocumentId(uri));
                    if (preferred != null) return preferred;
                } catch (IllegalArgumentException ignored) { }
            }
            // 临时分享的读授权不在持久授权列表中；直接交给既有文件读取流程检查。
            return uri;
        }
        String target = documentId(value);
        Uri granted = resolveGranted(context, target);
        if (granted != null) return granted;
        throw new IOException("还没有这条路径的读取授权。请先在系统选择器授权文件所在文件夹，或直接选择该文件，再使用粘贴路径。");
    }

    private static Uri resolveGranted(Context context, String target) {
        List<UriPermission> grants;
        try { grants = context.getContentResolver().getPersistedUriPermissions(); }
        catch (SecurityException e) { return null; }
        // 新文件夹授权可能排在旧只读文件授权后面，先找可读写授权，再退回只读。
        for (boolean writable : new boolean[]{true, false}) {
            for (UriPermission grant : grants) {
                if (!grant.isReadPermission() || grant.isWritePermission() != writable) continue;
                Uri uri = grant.getUri();
                if (!AUTHORITY.equals(uri.getAuthority())) continue;
                try {
                    // 只有选文件夹获得的纯 tree URI 扩展到后代；精确 document 授权不扩张。
                    List<String> segments = uri.getPathSegments();
                    if (DocumentsContract.isTreeUri(uri) && segments.size() == 2) {
                        if (SharedPath.containsDocument(DocumentsContract.getTreeDocumentId(uri), target)) {
                            return DocumentsContract.buildDocumentUriUsingTree(uri, target);
                        }
                    } else if (DocumentsContract.isDocumentUri(context, uri)
                            && target.equals(DocumentsContract.getDocumentId(uri))) {
                        return uri;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 无法解释的旧授权不能作为路径访问依据，继续查找其他明确授权。
                }
            }
        }
        return null;
    }

    /** 同名替换必须有原文件夹的持久读写 tree 授权，单个文件的写授权不足以创建旁边的暂存文件。 */
    public static Uri folderForDocument(Context context, Uri document) throws IOException {
        if (document == null || !DocumentsContract.isDocumentUri(context, document)) {
            throw new IOException("请先选择原 PDF，并授权原文件所在文件夹。");
        }
        String target;
        try { target = DocumentsContract.getDocumentId(document); }
        catch (IllegalArgumentException e) { throw new IOException("无法确认原 PDF 的文档标识。", e); }
        List<UriPermission> grants = context.getContentResolver().getPersistedUriPermissions();
        IOException lookupFailure = null;
        for (UriPermission grant : grants) {
            Uri tree = grant.getUri();
            if (!grant.isReadPermission() || !grant.isWritePermission()
                    || !isFolderGrant(tree) || !tree.getAuthority().equals(document.getAuthority())) continue;
            try {
                String root = DocumentsContract.getTreeDocumentId(tree);
                if (AUTHORITY.equals(document.getAuthority())) {
                    String parent = SharedPath.parentDocumentId(target);
                    if (SharedPath.containsDocument(root, parent)) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, parent);
                    }
                } else {
                    // 其他提供者的 ID 不一定是路径，只接受其返回的、处于授权树内的真实父目录。
                    DocumentsContract.Path path = DocumentsContract.findDocumentPath(
                            context.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, target));
                    if (path != null) {
                        List<String> ids = path.getPath();
                        if (ids.size() >= 2 && root.equals(ids.get(0)) && target.equals(ids.get(ids.size() - 1))) {
                            return DocumentsContract.buildDocumentUriUsingTree(tree, ids.get(ids.size() - 2));
                        }
                    }
                }
            } catch (IOException e) {
                lookupFailure = e;
            } catch (UnsupportedOperationException e) {
                lookupFailure = new IOException("文件来源不支持核实原 PDF 所在文件夹；请另存 PDF。", e);
            } catch (IllegalArgumentException | SecurityException ignored) {
                // 不把不匹配或失效的 tree 当成访问其他目录的依据。
            }
        }
        if (lookupFailure != null) throw lookupFailure;
        throw new FolderAuthorizationRequiredException("同名替换需要原 PDF 所在文件夹的持久读写授权。请点“授权文件夹”，选择原文件夹并允许访问；不会截断原 PDF。");
    }

    /** 把已授权目录规范为 tree-document URI；只保留实际持久 read+write tree 授权。 */
    static Uri writableFolder(Context context, Uri folder) throws IOException {
        try {
            if (folder == null || !DocumentsContract.isTreeUri(folder)) throw new IllegalArgumentException();
            String root = DocumentsContract.getTreeDocumentId(folder);
            String id = DocumentsContract.isDocumentUri(context, folder)
                    ? DocumentsContract.getDocumentId(folder) : root;
            for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
                Uri tree = grant.getUri();
                if (grant.isReadPermission() && grant.isWritePermission() && isFolderGrant(tree)
                        && tree.getAuthority().equals(folder.getAuthority())
                        && root.equals(DocumentsContract.getTreeDocumentId(tree))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, id);
                }
            }
        } catch (SecurityException e) {
            throw new FolderAuthorizationRequiredException("文件夹授权无效，请重新授权原文件夹。", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("文件夹地址无效，请重新选择原文件夹。", e);
        }
        throw new FolderAuthorizationRequiredException("尚未保存这个文件夹的读写授权，请重新授权原文件夹后再替换或恢复。");
    }

    private static boolean isFolderGrant(Uri uri) {
        return uri.getAuthority() != null && DocumentsContract.isTreeUri(uri) && uri.getPathSegments().size() == 2;
    }

    /** 仅提示选文件夹界面的初始位置；此 URI 本身不授予读写权。 */
    public static Uri initialFolder(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        if (value.regionMatches(true, 0, "content://", 0, 10)) {
            Uri uri = Uri.parse(value);
            try {
                if (!AUTHORITY.equals(uri.getAuthority())) throw new IllegalArgumentException();
                return DocumentsContract.buildDocumentUri(AUTHORITY,
                        SharedPath.parentDocumentId(DocumentsContract.getDocumentId(uri)));
            } catch (IllegalArgumentException e) {
                throw new IOException("这个文件来源不能推断原文件夹，请在选择器中手动选择。", e);
            }
        }
        return DocumentsContract.buildDocumentUri(AUTHORITY,
                SharedPath.parentDocumentId(documentId(input)));
    }

    private static String documentId(String input) throws IOException {
        try { return SharedPath.documentId(input); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
    }
}
