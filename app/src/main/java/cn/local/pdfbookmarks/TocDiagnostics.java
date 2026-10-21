package cn.local.pdfbookmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only import diagnostics. TocParser remains the authority for accepted imports. */
public final class TocDiagnostics {
    private TocDiagnostics() { }

    public enum Severity { ERROR, WARNING }

    public static final class Issue {
        public final Severity severity;
        public final String path, title, reason;

        private Issue(Severity severity, String path, String title, String reason) {
            this.severity = severity;
            this.path = path;
            this.title = title;
            this.reason = reason;
        }

        public String describe() {
            return (severity == Severity.ERROR ? "错误" : "提示") + " · " + path
                    + (title.isEmpty() ? "" : " · “" + title + "”") + "\n" + reason;
        }
    }

    public static final class Report {
        public final List<Issue> issues;
        /** Null when there is any error; never contains repaired or partially imported rows. */
        public final TocParser.Parsed parsed;
        public final int nodeCount;

        private Report(List<Issue> issues, TocParser.Parsed parsed, int nodeCount) {
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
            this.parsed = parsed;
            this.nodeCount = nodeCount;
        }

        public boolean hasErrors() {
            for (Issue issue : issues) if (issue.severity == Severity.ERROR) return true;
            return false;
        }

        public String describe() {
            StringBuilder result = new StringBuilder();
            for (Issue issue : issues) {
                if (result.length() > 0) result.append("\n\n");
                result.append(issue.describe());
            }
            return result.length() == 0 ? "目录格式与目标页检查通过" : result.toString();
        }
    }

    public static Report inspect(String text, int actualPdfPageCount) {
        Check check = new Check(actualPdfPageCount);
        if (actualPdfPageCount < 1) check.error("PDF", "", "PDF 实际总页数必须为正整数");
        if (text == null || text.trim().isEmpty()) {
            check.error("$", "", "目录文件不能为空");
            return check.report(null);
        }
        JsonElement value;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            try {
                value = read(reader, 0);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IllegalArgumentException("JSON 结束后存在额外内容：" + reader.getPath());
                }
            } catch (IOException | IllegalStateException | IllegalArgumentException failure) {
                check.error(reader.getPath(), "", "JSON 格式错误：" + failure.getMessage());
                return check.report(null);
            }
        } catch (IOException failure) {
            check.error("$", "", "JSON 读取失败：" + failure.getMessage());
            return check.report(null);
        }
        JsonObject root = check.object(value, "$", "");
        if (root != null) {
            if (root.has("info") || root.has("outline")) check.jasminum(root);
            else check.desktop(root);
        }
        TocParser.Parsed parsed = null;
        if (!check.hasErrors()) {
            try { parsed = TocParser.parse(text, actualPdfPageCount); }
            catch (IllegalArgumentException failure) {
                // Keep parser-only restrictions authoritative if its supported format evolves.
                check.error("$", "", failure.getMessage());
            }
        }
        return check.report(parsed);
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 256) throw new IllegalArgumentException("JSON 嵌套超过 256 层，请简化目录结构");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException("JSON 字段重复：" + reader.getPath());
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return JsonParser.parseString(reader.nextString());
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IllegalArgumentException("JSON 值不完整：" + reader.getPath());
        }
    }

    private static final class Check {
        final int pageCount;
        final List<Issue> issues = new ArrayList<>();
        final Map<String, String> duplicateLocations = new HashMap<>();
        int nodeCount;

        Check(int pageCount) { this.pageCount = pageCount; }
        Report report(TocParser.Parsed parsed) { return new Report(issues, parsed, nodeCount); }
        boolean hasErrors() {
            for (Issue issue : issues) if (issue.severity == Severity.ERROR) return true;
            return false;
        }
        void error(String path, String title, String reason) {
            issues.add(new Issue(Severity.ERROR, path, title, reason));
        }
        JsonObject object(JsonElement value, String path, String title) {
            if (value == null || !value.isJsonObject()) {
                error(path, title, value == null ? "缺少必填对象" : "必须是 JSON 对象");
                return null;
            }
            return value.getAsJsonObject();
        }
        JsonArray array(JsonElement value, String path, String title, boolean nonempty) {
            if (value == null || !value.isJsonArray()) {
                error(path, title, value == null ? "缺少必填数组" : "必须是数组");
                return null;
            }
            JsonArray result = value.getAsJsonArray();
            if (nonempty && result.isEmpty()) error(path, title, "必须是非空数组");
            return result;
        }
        void keys(JsonObject value, String path, String title, String... allowed) {
            List<String> names = Arrays.asList(allowed);
            for (String key : value.keySet()) if (!names.contains(key)) error(path + "." + key, title, "未知字段");
        }
        Integer integer(JsonElement value, String path, String title, boolean positive) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
                error(path, title, value == null ? "缺少必填整数" : "必须是" + (positive ? "正" : "") + "整数，不能是文字、布尔值或小数");
                return null;
            }
            final int result;
            try { result = Integer.parseInt(value.getAsString()); }
            catch (NumberFormatException failure) { error(path, title, "超出 32 位整数范围"); return null; }
            if (positive && result < 1) { error(path, title, "必须是正整数"); return null; }
            return result;
        }
        void number(JsonElement value, String path, String title) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                error(path, title, "必须是数字");
            }
        }
        String title(JsonElement value, String path) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                error(path, "", value == null ? "缺少目录标题" : "标题必须是非空文本");
                return null;
            }
            String text = value.getAsString();
            if (text.indexOf('\u0000') >= 0) { error(path, text, "标题不能包含 NUL 空字符"); return null; }
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch) && ch != '\u0085') return text;
            }
            error(path, "", "标题不能为空或全为空白");
            return null;
        }
        void desktop(JsonObject root) {
            keys(root, "$", "", "version", "mapping", "bookmarks");
            Integer version = integer(root.get("version"), "version", "", false);
            if (version != null && version != 1) error("version", "", "仅支持 version=1");
            Mapping mapping = new Mapping();
            if (root.has("mapping")) {
                JsonObject raw = object(root.get("mapping"), "mapping", "");
                if (raw != null) mapping.read(raw);
                else mapping.valid = false;
            }
            visit(array(root.get("bookmarks"), "bookmarks", "", true), 1, "bookmarks", "", mapping, false);
        }
        void jasminum(JsonObject root) {
            keys(root, "$", "", "info", "outline");
            JsonObject info = object(root.get("info"), "info", "");
            if (info != null) {
                keys(info, "info", "", "schema", "itemID", "jasminumVersion", "baseFontSize");
                Integer schema = integer(info.get("schema"), "info.schema", "", false);
                if (schema != null && schema != 2) error("info.schema", "", "仅支持茉莉花 info.schema=2");
                if (info.has("itemID")) integer(info.get("itemID"), "info.itemID", "", true);
                if (info.has("jasminumVersion")) title(info.get("jasminumVersion"), "info.jasminumVersion");
                if (info.has("baseFontSize")) number(info.get("baseFontSize"), "info.baseFontSize", "");
            }
            visit(array(root.get("outline"), "outline", "", true), 1, "outline", "", null, true);
        }
        void visit(JsonArray nodes, int level, String path, String parentKey, Mapping mapping, boolean jasminum) {
            if (nodes == null) return;
            for (int i = 0; i < nodes.size(); i++) {
                nodeCount++;
                String location = path + "[" + (i + 1) + "]";
                JsonObject node = object(nodes.get(i), location, "");
                if (node == null) continue;
                String title = title(node.get("title"), location + ".title");
                String label = title == null ? "" : title;
                Long target = null;
                if (jasminum) {
                    keys(node, location, label, "level", "title", "page", "children", "x", "y", "collapsed");
                    Integer declared = integer(node.get("level"), location + ".level", label, true);
                    if (declared != null && declared != level) error(location + ".level", label, "与父子层级不一致，应为 " + level);
                    Integer page = integer(node.get("page"), location + ".page", label, true);
                    if (page != null) target = page.longValue();
                    if (node.has("x")) number(node.get("x"), location + ".x", label);
                    if (node.has("y")) number(node.get("y"), location + ".y", label);
                    if (node.has("collapsed") && (!node.get("collapsed").isJsonPrimitive()
                            || !node.get("collapsed").getAsJsonPrimitive().isBoolean())) {
                        error(location + ".collapsed", label, "必须是布尔值");
                    }
                } else {
                    keys(node, location, label, "title", "page", "pdf_page", "children");
                    if (node.has("page") == node.has("pdf_page")) {
                        error(location, label, "必须且只能提供 page 或 pdf_page");
                    } else {
                        String field = node.has("page") ? "page" : "pdf_page";
                        Integer page = integer(node.get(field), location + "." + field, label, true);
                        if (page != null) {
                            if (field.equals("pdf_page")) target = page.longValue();
                            else target = mapping.map(page, location + ".page", label);
                        }
                    }
                }
                if (target != null && pageCount > 0 && (target < 1 || target > pageCount)) {
                    error(location + (jasminum || node.has("page") ? ".page" : ".pdf_page"), label,
                            "目标为 PDF 第 " + target + " 页，超出 1–" + pageCount);
                }
                // Length-prefixed titles keep separators occurring inside titles unambiguous.
                String key = parentKey + (title == null ? "!" + location : title.length() + ":" + title);
                if (title != null && target != null && target >= 1 && target <= pageCount) {
                    String first = duplicateLocations.putIfAbsent(key + "#" + target, location);
                    if (first != null) issues.add(new Issue(Severity.WARNING, location, label,
                            "与 " + first + " 的完整目录路径和目标页相同；保留原条目，不自动删除"));
                }
                if (node.has("children")) visit(array(node.get("children"), location + ".children", label, false),
                        level + 1, location + ".children", key, mapping, jasminum);
            }
        }

        final class Mapping {
            Integer offset = 0;
            final List<int[]> segments = new ArrayList<>();
            boolean valid = true;

            void read(JsonObject raw) {
                int initialErrors = issues.size();
                keys(raw, "mapping", "", "offset", "segments");
                if (raw.size() != 1 || raw.has("offset") == raw.has("segments")) {
                    error("mapping", "", "必须且只能包含 offset 或 segments");
                    valid = false;
                    return;
                }
                if (raw.has("offset")) offset = integer(raw.get("offset"), "mapping.offset", "", false);
                else {
                    offset = null;
                    JsonArray values = array(raw.get("segments"), "mapping.segments", "", true);
                    if (values != null) for (int i = 0; i < values.size(); i++) {
                        String location = "mapping.segments[" + (i + 1) + "]";
                        JsonObject segment = object(values.get(i), location, "");
                        if (segment == null) continue;
                        keys(segment, location, "", "printed_start", "printed_end", "pdf_start");
                        Integer start = integer(segment.get("printed_start"), location + ".printed_start", "", true);
                        Integer end = integer(segment.get("printed_end"), location + ".printed_end", "", true);
                        Integer pdf = integer(segment.get("pdf_start"), location + ".pdf_start", "", true);
                        if (start == null || end == null || pdf == null) continue;
                        if (end < start) error(location, "", "printed_end 小于 printed_start");
                        if (pageCount > 0 && (long) pdf + end - start > pageCount) error(location, "", "完整映射范围超出 PDF 总页数");
                        segments.add(new int[]{start, end, pdf});
                    }
                    segments.sort((left, right) -> Integer.compare(left[0], right[0]));
                    for (int i = 1; i < segments.size(); i++) if (segments.get(i)[0] <= segments.get(i - 1)[1]) {
                        error("mapping.segments", "", "印刷页码范围不能重叠");
                    }
                }
                valid = issues.size() == initialErrors;
            }

            Long map(int page, String path, String title) {
                if (!valid) return null;
                if (offset != null) return (long) page + offset;
                for (int[] segment : segments) if (page >= segment[0] && page <= segment[1]) {
                    return (long) segment[2] + page - segment[0];
                }
                error(path, title, "印刷页码 " + page + " 不在任何分段映射范围内");
                return null;
            }
        }
    }
}
