package cn.local.pdfbookmarks;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Classifies explicitly shared documents; it never reads a path or trusts a MIME hint as file content. */
public final class SharedImport {
    private SharedImport() { }

    public static final class Document {
        public final String uri, name;
        public final boolean pdf;

        public Document(String uri, String name) {
            if (uri == null || name == null) throw new IllegalArgumentException("分享文件信息不完整。");
            URI parsed;
            try { parsed = URI.create(uri); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("分享地址无效。"); }
            if (!"content".equals(parsed.getScheme()) || parsed.getAuthority() == null
                    || parsed.getAuthority().isEmpty()) {
                throw new IllegalArgumentException("请通过文件应用分享可读取的文档，不接受本地路径或网络链接。");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".pdf") && !lower.endsWith(".json")) {
                throw new IllegalArgumentException("只接受 PDF 和 JSON：" + name);
            }
            this.uri = uri;
            this.name = name;
            this.pdf = lower.endsWith(".pdf");
        }
    }

    public static List<Document> validate(List<Document> documents) {
        if (documents == null || documents.isEmpty() || documents.size() > 2) {
            throw new IllegalArgumentException("每次可分享一个 PDF、一个 JSON，或各一个文件。");
        }
        boolean pdf = false, json = false;
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            if (document == null || (document.pdf ? pdf : json)) {
                throw new IllegalArgumentException("同一次分享不能包含两个 PDF 或两个 JSON。");
            }
            if (document.pdf) pdf = true; else json = true;
            result.add(document);
        }
        return Collections.unmodifiableList(result);
    }
}
