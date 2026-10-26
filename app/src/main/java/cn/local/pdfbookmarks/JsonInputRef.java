package cn.local.pdfbookmarks;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** An imported JSON keeps its original identity separately from this app's owned working file. */
public final class JsonInputRef {
    public final String id, sourceUri, displayName, sha256;

    public JsonInputRef(String id, String sourceUri, String displayName, String sha256) {
        this.id = checkedId(id);
        if (sourceUri == null || control(sourceUri)) throw invalid("JSON 来源地址无效。");
        final URI uri;
        try { uri = URI.create(sourceUri); }
        catch (IllegalArgumentException invalid) { throw invalid("JSON 来源地址无效。"); }
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getAuthority().isEmpty()) {
            throw invalid("JSON 来源必须是应用明确提供的 content:// 文档地址。");
        }
        if (displayName == null || displayName.trim().isEmpty() || control(displayName)
                || displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0
                || !displayName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw invalid("JSON 文件名称无效。");
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) throw invalid("JSON 校验值无效。");
        this.sourceUri = sourceUri;
        this.displayName = displayName;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
    }

    static String checkedId(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value)) return value;
        } catch (IllegalArgumentException ignored) { }
        throw invalid("JSON 工作文件标识无效。");
    }

    private static boolean control(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
        return false;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof JsonInputRef)) return false;
        JsonInputRef ref = (JsonInputRef) other;
        return id.equals(ref.id) && sourceUri.equals(ref.sourceUri)
                && displayName.equals(ref.displayName) && sha256.equals(ref.sha256);
    }

    @Override public int hashCode() { return Objects.hash(id, sourceUri, displayName, sha256); }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
