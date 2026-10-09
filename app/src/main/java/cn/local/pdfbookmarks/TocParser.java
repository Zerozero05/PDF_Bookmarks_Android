package cn.local.pdfbookmarks;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 桌面 version=1 与茉莉花 schema=2 目录解析；不读取或修改 PDF。 */
public final class TocParser {
    private TocParser() { }

    public static final class Row {
        public final int level;
        public final String title;
        public final Integer printedPage;
        public final int pdfPage;

        public Row(int level, String title, Integer printedPage, int pdfPage) {
            this.level = level;
            this.title = title;
            this.printedPage = printedPage;
            this.pdfPage = pdfPage;
        }
    }

    public static final class Parsed {
        public final List<Row> rows;
        public final String sourceKind;

        private Parsed(List<Row> rows, String sourceKind) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.sourceKind = sourceKind;
        }
    }

    /** actualPdfPageCount 必须来自本次所选 PDF，不根据 JSON 元数据猜测。 */
    public static Parsed parse(String text, int actualPdfPageCount) throws IllegalArgumentException {
        if (actualPdfPageCount < 1) throw invalid("PDF 实际总页数必须为正整数");
        if (text == null || text.trim().isEmpty()) throw invalid("目录文件不能为空");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            Object value = readValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("JSON 结束后存在额外内容");
            Map<String, Object> root = object(value, "目录文件");
            return root.containsKey("info") || root.containsKey("outline")
                    ? jasminum(root, actualPdfPageCount) : desktop(root, actualPdfPageCount);
        } catch (IOException | IllegalStateException e) {
            throw invalid("JSON 格式错误：" + e.getMessage());
        }
    }

    /** JsonReader 本身不保留重复键；逐对象检查键名后才加入映射。 */
    private static Object readValue(JsonReader reader, int depth) throws IOException {
        // 过深输入明确报错，避免 Android 线程栈溢出；目录条目数量没有 MVP 限制。
        if (depth > 256) throw invalid("JSON 嵌套超过 256 层，请简化目录结构");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                Map<String, Object> fields = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (fields.containsKey(name)) throw invalid("JSON 字段重复：" + reader.getPath());
                    fields.put(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                return fields;
            case BEGIN_ARRAY:
                List<Object> items = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) items.add(readValue(reader, depth + 1));
                reader.endArray();
                return items;
            case STRING: return reader.nextString();
            case NUMBER: return new NumberToken(reader.nextString());
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return null;
            default: throw invalid("JSON 值不完整：" + reader.getPath());
        }
    }

    private static Parsed desktop(Map<String, Object> root, int pageCount) {
        keys(root, "目录文件", "version", "mapping", "bookmarks");
        if (integer(root.get("version"), "version", false) != 1) throw invalid("version 必须为 1");
        Map<String, Object> mapping = root.containsKey("mapping")
                ? object(root.get("mapping"), "mapping")
                : Collections.<String, Object>singletonMap("offset", new NumberToken("0"));
        keys(mapping, "mapping", "offset", "segments");
        if (mapping.size() != 1) throw invalid("mapping 必须且只能包含 offset 或 segments");
        PageMapping pages = new PageMapping(mapping, pageCount);
        List<Object> nodes = array(root.get("bookmarks"), "bookmarks");
        if (nodes.isEmpty()) throw invalid("bookmarks 必须是非空数组");
        List<Row> rows = new ArrayList<>();
        visitDesktop(nodes, 1, "bookmarks", pages, pageCount, rows);
        return new Parsed(rows, "desktop");
    }

    private static void visitDesktop(List<Object> nodes, int level, String label, PageMapping mapping,
                                     int pageCount, List<Row> rows) {
        for (int i = 0; i < nodes.size(); i++) {
            String location = label + "[" + (i + 1) + "]";
            Map<String, Object> node = object(nodes.get(i), location);
            keys(node, location, "title", "page", "pdf_page", "children");
            String title = title(node.get("title"), location + ".title");
            if (node.containsKey("page") == node.containsKey("pdf_page")) {
                throw invalid(location + " 必须且只能提供 page 或 pdf_page");
            }
            Integer printed = node.containsKey("page") ? integer(node.get("page"), location + ".page", true) : null;
            long target = printed != null ? mapping.map(printed)
                    : integer(node.get("pdf_page"), location + ".pdf_page", true);
            rows.add(new Row(level, title, printed, inPdf(target, pageCount, title)));
            if (node.containsKey("children")) {
                visitDesktop(array(node.get("children"), location + ".children"), level + 1,
                        location + ".children", mapping, pageCount, rows);
            }
        }
    }

    private static Parsed jasminum(Map<String, Object> root, int pageCount) {
        keys(root, "茉莉花目录", "info", "outline");
        Map<String, Object> info = object(root.get("info"), "info");
        keys(info, "info", "schema", "itemID", "jasminumVersion", "baseFontSize");
        if (integer(info.get("schema"), "info.schema", false) != 2) throw invalid("仅支持茉莉花 info.schema=2");
        if (info.containsKey("itemID")) integer(info.get("itemID"), "info.itemID", true);
        if (info.containsKey("jasminumVersion")) title(info.get("jasminumVersion"), "info.jasminumVersion");
        if (info.containsKey("baseFontSize")) number(info.get("baseFontSize"), "info.baseFontSize");
        List<Object> nodes = array(root.get("outline"), "outline");
        if (nodes.isEmpty()) throw invalid("outline 必须是非空数组");
        List<Row> rows = new ArrayList<>();
        visitJasminum(nodes, 1, "outline", pageCount, rows);
        return new Parsed(rows, "jasminum");
    }

    private static void visitJasminum(List<Object> nodes, int level, String label, int pageCount,
                                     List<Row> rows) {
        for (int i = 0; i < nodes.size(); i++) {
            String location = label + "[" + (i + 1) + "]";
            Map<String, Object> node = object(nodes.get(i), location);
            keys(node, location, "level", "title", "page", "children", "x", "y", "collapsed");
            int declaredLevel = integer(node.get("level"), location + ".level", true);
            if (declaredLevel != level) throw invalid(location + ".level 与父子层级不一致，应为 " + level);
            String title = title(node.get("title"), location + ".title");
            int page = integer(node.get("page"), location + ".page", true);
            // 实际用户 schema=2 的 page 已是物理页码，不作 +1 或额外偏移。
            rows.add(new Row(level, title, null, inPdf(page, pageCount, title)));
            if (node.containsKey("x")) number(node.get("x"), location + ".x");
            if (node.containsKey("y")) number(node.get("y"), location + ".y");
            if (node.containsKey("collapsed") && !(node.get("collapsed") instanceof Boolean)) {
                throw invalid(location + ".collapsed 必须是布尔值");
            }
            if (node.containsKey("children")) {
                visitJasminum(array(node.get("children"), location + ".children"), level + 1,
                        location + ".children", pageCount, rows);
            }
        }
    }

    private static final class PageMapping {
        final Integer offset;
        final List<Segment> segments = new ArrayList<>();

        PageMapping(Map<String, Object> mapping, int pageCount) {
            if (mapping.containsKey("offset")) {
                offset = integer(mapping.get("offset"), "mapping.offset", false);
                return;
            }
            offset = null;
            List<Object> raw = array(mapping.get("segments"), "mapping.segments");
            if (raw.isEmpty()) throw invalid("mapping.segments 必须是非空数组");
            for (int i = 0; i < raw.size(); i++) {
                String label = "mapping.segments[" + (i + 1) + "]";
                Map<String, Object> segment = object(raw.get(i), label);
                keys(segment, label, "printed_start", "printed_end", "pdf_start");
                int start = integer(segment.get("printed_start"), label + ".printed_start", true);
                int end = integer(segment.get("printed_end"), label + ".printed_end", true);
                int pdfStart = integer(segment.get("pdf_start"), label + ".pdf_start", true);
                if (end < start) throw invalid(label + " 的 printed_end 小于 printed_start");
                if ((long) pdfStart + end - start > pageCount) throw invalid(label + " 的完整映射范围超出 PDF 总页数");
                segments.add(new Segment(start, end, pdfStart));
            }
            segments.sort(Comparator.comparingInt(s -> s.start));
            for (int i = 1; i < segments.size(); i++) {
                if (segments.get(i).start <= segments.get(i - 1).end) {
                    throw invalid("mapping.segments 的印刷页码范围不能重叠");
                }
            }
        }

        long map(int printed) {
            if (offset != null) return (long) printed + offset;
            for (Segment segment : segments) {
                if (printed >= segment.start && printed <= segment.end) {
                    return (long) segment.pdfStart + printed - segment.start;
                }
            }
            throw invalid("印刷页码 " + printed + " 不在任何分段映射范围内");
        }
    }

    private static final class Segment {
        final int start, end, pdfStart;
        Segment(int start, int end, int pdfStart) { this.start = start; this.end = end; this.pdfStart = pdfStart; }
    }

    private static final class NumberToken {
        final String value;
        NumberToken(String value) { this.value = value; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map)) throw invalid(label + " 必须是 JSON 对象");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List)) throw invalid(label + " 必须是数组");
        return (List<Object>) value;
    }

    private static void keys(Map<String, Object> fields, String label, String... allowed) {
        List<String> names = Arrays.asList(allowed);
        for (String key : fields.keySet()) {
            if (!names.contains(key)) throw invalid(label + " 包含未知字段：" + key);
        }
    }

    private static int integer(Object value, String label, boolean positive) {
        if (!(value instanceof NumberToken) || !((NumberToken) value).value.matches("-?(0|[1-9][0-9]*)")) {
            throw invalid(label + " 必须是" + (positive ? "正" : "") + "整数，不能是文字、布尔值或小数");
        }
        final int result;
        try { result = Integer.parseInt(((NumberToken) value).value); }
        catch (NumberFormatException e) { throw invalid(label + " 超出 32 位整数范围"); }
        if (positive && result < 1) throw invalid(label + " 必须是正整数");
        return result;
    }

    private static void number(Object value, String label) {
        if (!(value instanceof NumberToken)) throw invalid(label + " 必须是数字");
        try { new BigDecimal(((NumberToken) value).value); }
        catch (NumberFormatException e) { throw invalid(label + " 必须是有限数字"); }
    }

    private static String title(Object value, String label) {
        if (!(value instanceof String)) throw invalid(label + " 必须是非空文本");
        String text = (String) value;
        if (text.indexOf('\u0000') >= 0) throw invalid(label + " 不能包含 NUL 空字符");
        boolean visible = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch) && ch != '\u0085') visible = true;
        }
        if (!visible) throw invalid(label + " 必须是非空文本");
        return text;
    }

    private static int inPdf(long page, int count, String title) {
        if (page < 1 || page > count) throw invalid("书签“" + title + "”指向 PDF 第 " + page + " 页，超出 1–" + count);
        return (int) page;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
