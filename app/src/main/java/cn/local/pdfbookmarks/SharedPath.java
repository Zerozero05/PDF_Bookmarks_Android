package cn.local.pdfbookmarks;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 共享存储路径提示的纯 Java 解析；只生成文档 ID，不读取文件、不授予权限。 */
public final class SharedPath {
    private static final Set<String> SHARED_FOLDERS = new HashSet<>(Arrays.asList(
            "享做笔记", "Download", "Downloads", "Documents", "Books", "DCIM", "Pictures",
            "Music", "Movies", "Podcasts", "Ringtones", "Alarms", "Notifications", "Audiobooks"));

    private SharedPath() {}

    public static String documentId(String input) {
        if (input == null || input.trim().isEmpty()) throw invalid("请粘贴 PDF 或 JSON 的共享存储路径。");
        String path = input.trim();
        if (path.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                URI file = new URI(path);
                if (file.getRawAuthority() != null && !file.getRawAuthority().isEmpty()
                        || file.getRawQuery() != null || file.getRawFragment() != null
                        || file.getPath() == null) {
                    throw invalid("file:// 路径不能包含远程地址、查询或片段。");
                }
                // URI 解码仅发生于 file://；普通路径中的 % 与 + 是文件名原字符。
                path = file.getPath();
            } catch (URISyntaxException e) {
                throw invalid("file:// 路径格式无效，请重新复制文件路径。");
            }
        } else if (path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw invalid("这里只接受平板共享存储路径；不能使用 Windows 路径或其他地址。");
        }
        if (path.indexOf('\\') >= 0 || path.startsWith("//") || hasControl(path)) {
            throw invalid("路径不能包含反斜杠、网络路径或控制字符。");
        }
        path = path.replaceAll("/+", "/");
        if (path.equals("/storage/emulated/0") || path.startsWith("/storage/emulated/0/")) {
            path = path.substring("/storage/emulated/0".length());
        } else if (path.equals("/sdcard") || path.startsWith("/sdcard/")) {
            path = path.substring("/sdcard".length());
        } else if (path.startsWith("/")) {
            String first = path.substring(1).split("/", 2)[0];
            if (!SHARED_FOLDERS.contains(first)) {
                throw invalid("无法识别这个绝对路径；请使用 /storage/emulated/0/ 路径或不带开头 / 的共享存储相对路径。");
            }
        }
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) throw invalid("路径只指向存储根目录，请粘贴具体文件的路径。");
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid("路径不能包含 . 或 .. 目录跳转。");
            }
        }
        if (segments.length >= 2 && segments[0].equalsIgnoreCase("Android")
                && (segments[1].equalsIgnoreCase("data") || segments[1].equalsIgnoreCase("obb"))) {
            throw invalid("Android/data 和 Android/obb 属于受限制目录，请将文件放到共享文档文件夹后重新选择。");
        }
        return "primary:" + path;
    }

    /** 仅对 ExternalStorageProvider 已知的卷:相对路径结构使用，目录边界必须完整。 */
    static boolean containsDocument(String directoryId, String documentId) {
        String[] directory = parts(directoryId), document = parts(documentId);
        if (directory == null || document == null || !directory[0].equals(document[0])) return false;
        return directory[1].isEmpty() || document[1].equals(directory[1])
                || document[1].startsWith(directory[1] + "/");
    }

    static String parentDocumentId(String documentId) {
        String[] value = parts(documentId);
        if (value == null) throw invalid("共享存储文档标识无效。");
        int slash = value[1].lastIndexOf('/');
        return value[0] + ":" + (slash < 0 ? "" : value[1].substring(0, slash));
    }

    private static String[] parts(String id) {
        if (id == null || hasControl(id) || id.indexOf('\\') >= 0) return null;
        int colon = id.indexOf(':');
        if (colon < 1 || !id.substring(0, colon).matches("[A-Za-z0-9_-]+")) return null;
        String relative = id.substring(colon + 1);
        if (!relative.isEmpty()) {
            for (String segment : relative.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
            }
        }
        return new String[]{id.substring(0, colon), relative};
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
        return false;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
