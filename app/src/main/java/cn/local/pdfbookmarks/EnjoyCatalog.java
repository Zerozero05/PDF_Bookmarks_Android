package cn.local.pdfbookmarks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按所选模式处理已核实格式的享做目录；不读写文件，不修改笔迹、页配置或同步计数。 */
public final class EnjoyCatalog {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int MAX_DEPTH = 256;

    private EnjoyCatalog() { }

    public static final class Plan {
        public final byte[] json;
        // 替换模式的addedCount表示需要写入的新目录数；完全相同为0，totalCount始终是最终数量。
        public final int originalCount, addedCount, totalCount;
        public final boolean replacesExisting;

        private Plan(byte[] json, int originalCount, int addedCount, int totalCount, boolean replacesExisting) {
            this.json = json;
            this.originalCount = originalCount;
            this.addedCount = addedCount;
            this.totalCount = totalCount;
            this.replacesExisting = replacesExisting;
        }
    }

    /** PDF 页码来自实际文件；享做页标识只能由 document + docIndex 并经 doc 核对获得。 */
    public static Plan merge(byte[] catalog, byte[] pageids, byte[] doc, String androidPdfPath,
                             int actualPageCount, List<TocParser.Row> rows) throws IOException {
        return merge(catalog, pageids, doc, androidPdfPath, actualPageCount, rows, false);
    }

    /** 明确选择替换时只替换根kids；原字节备份和文件切换由调用方按既有流程完成。 */
    public static Plan merge(byte[] catalog, byte[] pageids, byte[] doc, String androidPdfPath,
                             int actualPageCount, List<TocParser.Row> rows, boolean replaceExisting) throws IOException {
        if (actualPageCount < 1) throw invalid("PDF 实际总页数必须为正整数");
        if (rows == null || rows.isEmpty()) throw invalid("待同步目录不能为空");
        final String selected;
        try { selected = SharedPath.documentId(androidPdfPath); }
        catch (IllegalArgumentException e) { throw invalid("无法核实所选 PDF 路径：" + e.getMessage()); }

        JsonObject root = object(parse(catalog, "catalog.json"), "catalog.json");
        int originalCount = validateNode(root, true, "catalog.json");
        Map<Integer, String> targets = targets(parse(pageids, "pageids.json"),
                parse(doc, "doc"), selected, actualPageCount);
        JsonArray incoming = incoming(rows, targets, actualPageCount);
        if (replaceExisting) {
            int total = rows.size();
            boolean changed = !root.getAsJsonArray("kids").equals(incoming);
            if (changed) root.add("kids", incoming);
            return new Plan(changed ? JSON.toJson(root).getBytes(StandardCharsets.UTF_8) : catalog.clone(),
                    originalCount, changed ? total : 0, total, true);
        }
        int added = mergeKids(array(root.get("kids"), "catalog.json.kids"), incoming);
        // 完全相同的重复导入不重写原文件，也不改变它的空白、数值表示或字段顺序。
        return new Plan(added == 0 ? catalog.clone()
                : JSON.toJson(root).getBytes(StandardCharsets.UTF_8), originalCount, added, originalCount + added, false);
    }

    private static Map<Integer, String> targets(JsonElement pageids, JsonElement doc,
                                                 String selected, int pageCount) throws IOException {
        JsonObject document = object(doc, "doc");
        Map<String, Integer> indexes = new HashMap<>();
        for (JsonElement value : array(document.get("pages"), "doc.pages")) {
            JsonObject page = object(value, "doc.pages 条目");
            String id = text(page.get("id"), "doc.pages.id", false);
            int index = integer(page.get("index"), "doc.pages.index");
            if (index < 0 || indexes.put(id, index) != null) throw invalid("doc 包含无效或重复页标识");
        }
        JsonObject ids = object(pageids, "pageids.json");
        text(ids.get("id"), "pageids.json.id", false);
        Map<Integer, String> targets = new HashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (JsonElement value : array(ids.get("infos"), "pageids.json.infos")) {
            JsonObject page = object(value, "pageids.json.infos 条目");
            String id = text(page.get("id"), "pageids.json.infos.id", false);
            if (!seenIds.add(id)) throw invalid("pageids.json 包含重复页标识");
            String path = text(page.get("document"), "pageids.json.infos.document", true);
            bool(page.get("origin"), "pageids.json.infos.origin");
            int index = integer(page.get("docIndex"), "pageids.json.infos.docIndex");
            if (index < 0) throw invalid("享做 PDF 页索引不能为负数");
            if (path.isEmpty()) continue;
            final String normalized;
            try { normalized = SharedPath.documentId(path); }
            catch (IllegalArgumentException e) { throw invalid("享做记录的 PDF 路径格式尚未核实"); }
            if (!selected.equals(normalized)) continue;
            // 实际样本中 origin 两种值都有显式 PDF 关联；只以路径、docIndex 和 doc 页 ID 确认目标。
            if (index >= pageCount || !Integer.valueOf(index).equals(indexes.get(id))) {
                throw invalid("享做 PDF 页索引与实际页数或 doc 记录不一致");
            }
            if (targets.put(index, id) != null) throw invalid("同一 PDF 页对应多个享做页，无法安全同步");
        }
        return targets;
    }

    private static JsonArray incoming(List<TocParser.Row> rows, Map<Integer, String> targets,
                                       int pageCount) throws IOException {
        JsonArray root = new JsonArray();
        List<JsonObject> parents = new ArrayList<>();
        for (TocParser.Row row : rows) {
            if (row == null || row.level < 1 || row.level > MAX_DEPTH / 2 - 1
                    || row.pdfPage < 1 || row.pdfPage > pageCount) {
                throw invalid("目录层级或实际 PDF 页码无效");
            }
            String title = text(row.title == null ? JsonNull.INSTANCE : new JsonPrimitive(row.title),
                    "目录标题", false);
            if (title.trim().isEmpty()) throw invalid("目录标题不能为空白");
            while (parents.size() >= row.level) parents.remove(parents.size() - 1);
            if (parents.size() != row.level - 1) throw invalid("目录层级不能跳过父级");
            String id = targets.get(row.pdfPage - 1);
            if (id == null) throw invalid("PDF 第 " + row.pdfPage + " 页没有唯一且已核实的享做页映射");
            JsonArray siblings = parents.isEmpty() ? root : parents.get(parents.size() - 1).getAsJsonArray("kids");
            if (match(siblings, title, id) != null) throw invalid("导入目录包含无法区分的重复同级条目");
            JsonObject node = new JsonObject();
            node.addProperty("dirty", false);
            node.addProperty("expand", false);
            node.add("kids", new JsonArray());
            node.addProperty("page", -1);
            node.addProperty("pageId", id);
            node.addProperty("title", title);
            node.addProperty("usn", 0);
            siblings.add(node);
            parents.add(node);
        }
        return root;
    }

    private static int mergeKids(JsonArray existing, JsonArray incoming) throws IOException {
        int added = 0;
        for (JsonElement value : incoming) {
            JsonObject node = value.getAsJsonObject();
            JsonObject match = match(existing, node.get("title").getAsString(), node.get("pageId").getAsString());
            if (match == null) {
                existing.add(node);
                added += validateNode(node, false, "新增目录");
            } else {
                added += mergeKids(match.getAsJsonArray("kids"), node.getAsJsonArray("kids"));
            }
        }
        return added;
    }

    private static JsonObject match(JsonArray siblings, String title, String id) throws IOException {
        JsonObject found = null;
        for (JsonElement value : siblings) {
            JsonObject node = value.getAsJsonObject();
            if (title.equals(node.get("title").getAsString()) && id.equals(node.get("pageId").getAsString())) {
                if (found != null) throw invalid("已有目录包含多个相同标题与页标识的同级条目，无法安全合并");
                found = node;
            }
        }
        return found;
    }

    private static int validateNode(JsonObject node, boolean root, String label) throws IOException {
        bool(node.get("dirty"), label + ".dirty");
        bool(node.get("expand"), label + ".expand");
        int page = integer(node.get("page"), label + ".page");
        // 真实旧目录可同时保存 -1 与非负 page；保留原值，目标仍由明确的 pageId 核实。
        if ((root && page != -1) || page < -1) throw invalid(label + ".page 使用了尚未核实的享做目录页码格式");
        String pageId = text(node.get("pageId"), label + ".pageId", root);
        String title = text(node.get("title"), label + ".title", root);
        if (integer(node.get("usn"), label + ".usn") < 0) throw invalid("享做目录同步计数无效");
        if (root && (!pageId.isEmpty() || !title.isEmpty())) throw invalid("尚未核实这个享做目录的根节点格式");
        int count = root ? 0 : 1;
        for (JsonElement child : array(node.get("kids"), label + ".kids")) {
            count += validateNode(object(child, label + ".kids 条目"), false, label + ".kids");
        }
        return count;
    }

    private static JsonElement parse(byte[] bytes, String label) throws IOException {
        if (bytes == null || bytes.length == 0) throw invalid(label + " 不能为空");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw invalid(label + " 不是有效 UTF-8 JSON"); }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid(label + " 结束后存在额外内容");
            return result;
        } catch (IllegalStateException | NumberFormatException e) {
            throw invalid(label + " 格式错误：" + e.getMessage());
        }
    }

    /** 逐键解析，拒绝 Gson 默认读取树时可能丢失的重复字段。 */
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw invalid("享做 JSON 嵌套超过 " + MAX_DEPTH + " 层");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw invalid("享做 JSON 字段重复：" + reader.getPath());
                    object.add(name, read(reader, depth + 1));
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
            // 严格读取器已核实数值语法；单独解析数值以保留精度和原来的数值表示。
            case NUMBER: return JsonParser.parseString(reader.nextString());
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw invalid("享做 JSON 值不完整：" + reader.getPath());
        }
    }

    private static JsonObject object(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonObject()) throw invalid(label + " 必须为对象");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonArray()) throw invalid(label + " 必须为数组");
        return value.getAsJsonArray();
    }

    private static String text(JsonElement value, String label, boolean empty) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(label + " 必须为文本");
        }
        String text = value.getAsString();
        if ((!empty && text.isEmpty()) || text.indexOf('\u0000') >= 0) throw invalid(label + " 无效");
        return text;
    }

    private static boolean bool(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(label + " 必须为布尔值");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) throw invalid(label + " 必须为整数");
        try { return Integer.parseInt(value.getAsString()); }
        catch (NumberFormatException e) { throw invalid(label + " 超出整数范围"); }
    }

    private static IOException invalid(String message) { return new IOException(message); }
}
