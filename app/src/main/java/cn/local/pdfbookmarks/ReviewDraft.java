package cn.local.pdfbookmarks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 可恢复的核对工作副本；不保存授权、核对确认或任何写入任务。 */
public final class ReviewDraft {
    public static final int VERSION = 1;
    public final String pdfUri, jsonUri, pdfSha256, jsonSha256, originalTreeSha256;
    public final String query, previewTitle;
    public final int selectedSourceIndex, previewPage, listFirstPosition, listTopOffset;
    private final int[] targetPages;
    private final List<Integer> collapsedSourceIndices;

    private ReviewDraft(String pdfUri, String jsonUri, String pdfSha256, String jsonSha256,
                        String originalTreeSha256, int[] targetPages, String query,
                        Collection<Integer> collapsed, int selectedSourceIndex, int previewPage,
                        String previewTitle, int listFirstPosition, int listTopOffset) {
        this.pdfUri = nonempty(pdfUri, "PDF 文件标识");
        this.jsonUri = nonempty(jsonUri, "JSON 文件标识");
        this.pdfSha256 = sha256(pdfSha256);
        this.jsonSha256 = sha256(jsonSha256);
        this.originalTreeSha256 = sha256(originalTreeSha256);
        if (targetPages == null || targetPages.length == 0) throw invalid("目录页码不能为空");
        this.targetPages = targetPages.clone();
        for (int page : this.targetPages) if (page < 1) throw invalid("目标页必须为正整数");
        if (query == null || previewTitle == null) throw invalid("界面文字不能缺失");
        this.query = query;
        this.previewTitle = previewTitle;
        if (selectedSourceIndex < -1 || selectedSourceIndex >= targetPages.length) {
            throw invalid("选中目录索引越界");
        }
        this.selectedSourceIndex = selectedSourceIndex;
        if (previewPage < 0) throw invalid("预览页不能为负数");
        this.previewPage = previewPage;
        if (listFirstPosition < 0 || listFirstPosition >= targetPages.length) {
            throw invalid("目录滚动位置越界");
        }
        this.listFirstPosition = listFirstPosition;
        this.listTopOffset = listTopOffset;
        if (collapsed == null) throw invalid("折叠状态不能为空");
        ArrayList<Integer> indices = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Integer index : collapsed) {
            if (index == null || index < 0 || index >= targetPages.length || !seen.add(index)) {
                throw invalid("折叠目录索引越界或重复");
            }
            indices.add(index);
        }
        Collections.sort(indices);
        this.collapsedSourceIndices = Collections.unmodifiableList(indices);
    }

    public static ReviewDraft capture(String pdfUri, String jsonUri, String pdfSha256, String jsonSha256,
                                      List<TocParser.Row> originalRows, List<TocParser.Row> adjustedRows,
                                      int pageCount, String query, Collection<Integer> collapsed,
                                      int selectedSourceIndex, int previewPage, String previewTitle,
                                      int listFirstPosition, int listTopOffset) {
        PageAdjustments checked = new PageAdjustments(originalRows, pageCount);
        checked.apply(adjustedRows);
        int[] pages = new int[adjustedRows.size()];
        for (int i = 0; i < pages.length; i++) pages[i] = adjustedRows.get(i).pdfPage;
        ReviewDraft draft = new ReviewDraft(pdfUri, jsonUri, pdfSha256, jsonSha256,
                treeSha256(originalRows), pages, query, collapsed, selectedSourceIndex,
                previewPage, previewTitle, listFirstPosition, listTopOffset);
        draft.validateAgainst(originalRows, pageCount);
        return draft;
    }

    public int[] targetPages() { return targetPages.clone(); }
    public List<Integer> collapsedSourceIndices() { return collapsedSourceIndices; }

    /** 调用方须重新读取原文件并计算 SHA-256；相同路径不能代替内容核对。 */
    public boolean matches(String pdfUri, String jsonUri, String pdfSha256, String jsonSha256) {
        return this.pdfUri.equals(pdfUri) && this.jsonUri.equals(jsonUri)
                && this.pdfSha256.equalsIgnoreCase(pdfSha256)
                && this.jsonSha256.equalsIgnoreCase(jsonSha256);
    }

    /** 只在文件身份匹配后使用；重新解析的原树、条目数量和实际页数必须仍然有效。 */
    public void validateAgainst(List<TocParser.Row> originalRows, int pageCount) {
        new PageAdjustments(originalRows, pageCount);
        if (targetPages.length != originalRows.size()
                || !originalTreeSha256.equals(treeSha256(originalRows))) {
            throw invalid("原目录结构已变化，请重新核对");
        }
        if (previewPage > pageCount) throw invalid("预览页超出 PDF 实际总页数");
        for (int page : targetPages) {
            if (page > pageCount) throw invalid("草稿目标页超出 PDF 实际总页数");
        }
        for (int index : collapsedSourceIndices) {
            if (index + 1 >= originalRows.size()
                    || originalRows.get(index + 1).level <= originalRows.get(index).level) {
                throw invalid("草稿折叠项不是父目录");
            }
        }
    }

    public List<TocParser.Row> restoredRows(List<TocParser.Row> originalRows, int pageCount) {
        validateAgainst(originalRows, pageCount);
        List<TocParser.Row> restored = new ArrayList<>();
        for (int i = 0; i < targetPages.length; i++) {
            TocParser.Row row = originalRows.get(i);
            restored.add(new TocParser.Row(row.level, row.title, row.printedPage, targetPages[i]));
        }
        return Collections.unmodifiableList(restored);
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        json.addProperty("pdfUri", pdfUri);
        json.addProperty("jsonUri", jsonUri);
        json.addProperty("pdfSha256", pdfSha256);
        json.addProperty("jsonSha256", jsonSha256);
        json.addProperty("originalTreeSha256", originalTreeSha256);
        Gson gson = new Gson();
        json.add("targetPages", gson.toJsonTree(targetPages));
        json.addProperty("query", query);
        json.add("collapsedSourceIndices", gson.toJsonTree(collapsedSourceIndices));
        json.addProperty("selectedSourceIndex", selectedSourceIndex);
        json.addProperty("previewPage", previewPage);
        json.addProperty("previewTitle", previewTitle);
        json.addProperty("listFirstPosition", listFirstPosition);
        json.addProperty("listTopOffset", listTopOffset);
        return gson.toJson(json);
    }

    /** 损坏草稿以可报告异常返回给持久层，不放宽输入类型、不接受未知字段。 */
    public static ReviewDraft fromJson(String text) {
        if (text == null || text.trim().isEmpty()) throw invalid("草稿内容为空");
        String pdfUri = null, jsonUri = null, pdfSha = null, jsonSha = null, treeSha = null;
        String query = null, previewTitle = null;
        int version = -1, selected = -1, previewPage = 0, first = -1, top = 0;
        int[] pages = null;
        List<Integer> collapsed = null;
        Set<String> fields = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!fields.add(key)) throw invalid("草稿字段重复：" + key);
                switch (key) {
                    case "version": version = integer(reader); break;
                    case "pdfUri": pdfUri = string(reader); break;
                    case "jsonUri": jsonUri = string(reader); break;
                    case "pdfSha256": pdfSha = string(reader); break;
                    case "jsonSha256": jsonSha = string(reader); break;
                    case "originalTreeSha256": treeSha = string(reader); break;
                    case "targetPages": {
                        List<Integer> list = integers(reader);
                        pages = new int[list.size()];
                        for (int i = 0; i < pages.length; i++) pages[i] = list.get(i);
                        break;
                    }
                    case "query": query = string(reader); break;
                    case "collapsedSourceIndices": collapsed = integers(reader); break;
                    case "selectedSourceIndex": selected = integer(reader); break;
                    case "previewPage": previewPage = integer(reader); break;
                    case "previewTitle": previewTitle = string(reader); break;
                    case "listFirstPosition": first = integer(reader); break;
                    case "listTopOffset": top = integer(reader); break;
                    default: throw invalid("草稿包含未知字段：" + key);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("草稿末尾有额外内容");
        } catch (IOException | IllegalStateException e) {
            throw invalid("草稿 JSON 格式错误：" + e.getMessage());
        }
        if (fields.size() != 14) throw invalid("草稿字段缺失");
        if (version != VERSION) throw invalid("不支持这个草稿版本");
        return new ReviewDraft(pdfUri, jsonUri, pdfSha, jsonSha, treeSha, pages, query,
                collapsed, selected, previewPage, previewTitle, first, top);
    }

    private static String treeSha256(List<TocParser.Row> rows) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeInt(rows.size());
                for (TocParser.Row row : rows) {
                    data.writeInt(row.level);
                    byte[] title = row.title.getBytes(StandardCharsets.UTF_8);
                    data.writeInt(title.length);
                    data.write(title);
                    data.writeBoolean(row.printedPage != null);
                    if (row.printedPage != null) data.writeInt(row.printedPage);
                    data.writeInt(row.pdfPage);
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("无法计算草稿目录标识", impossible);
        }
    }

    private static List<Integer> integers(JsonReader reader) throws IOException {
        List<Integer> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) values.add(integer(reader));
        reader.endArray();
        return values;
    }

    private static int integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid("草稿数值字段必须为整数");
        String token = reader.nextString();
        if (!token.matches("-?(0|[1-9][0-9]*)")) throw invalid("草稿数值字段必须为整数");
        try { return Integer.parseInt(token); }
        catch (NumberFormatException e) { throw invalid("草稿整数超出有效范围"); }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid("草稿文字字段必须为字符串");
        return reader.nextString();
    }

    private static String sha256(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw invalid("草稿文件校验值无效");
        return value.toLowerCase(Locale.ROOT);
    }

    private static String nonempty(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw invalid(field + "不能为空");
        return value;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail);
    }
}
