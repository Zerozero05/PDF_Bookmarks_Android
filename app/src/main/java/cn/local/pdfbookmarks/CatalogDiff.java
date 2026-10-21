package cn.local.pdfbookmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Preview only: compares catalog bytes already validated by EnjoyCatalog, without writing files. */
public final class CatalogDiff {
    private CatalogDiff() { }

    public static final class Summary {
        public final int added, removed, changed, unchanged, titleChanged, pageChanged, hierarchyChanged;
        public final List<String> details;

        private Summary(DirectoryDiff.Summary diff, List<String> details) {
            added = diff.added;
            removed = diff.removed;
            changed = diff.changed;
            unchanged = diff.unchanged;
            titleChanged = diff.titleChanged;
            pageChanged = diff.pageChanged;
            hierarchyChanged = diff.hierarchyChanged;
            this.details = Collections.unmodifiableList(new ArrayList<>(details));
        }

        public String counts() {
            return "新增 " + added + " · 移除 " + removed + " · 变更 " + changed + " · 保留 " + unchanged;
        }

        public String describe() {
            StringBuilder result = new StringBuilder(counts());
            for (String detail : details) result.append("\n\n").append(detail);
            return result.toString();
        }
    }

    /** Physical pages are one-based; IDs absent from knownPhysicalPages remain original note pages. */
    public static Summary compare(byte[] before, byte[] after, Map<String, Integer> knownPhysicalPages) {
        if (before == null || after == null) throw new IllegalArgumentException("享做目录预览需要原目录和新目录");
        Map<String, Integer> targetCodes = new LinkedHashMap<>();
        List<TocParser.Row> oldRows = rows(before, targetCodes), newRows = rows(after, targetCodes);
        Map<Integer, String> ids = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> target : targetCodes.entrySet()) ids.put(target.getValue(), target.getKey());
        DirectoryDiff.Summary diff = DirectoryDiff.compare(oldRows, newRows);
        List<String> details = new ArrayList<>();
        for (DirectoryDiff.Change change : diff.changes) {
            if (change.kind == DirectoryDiff.Kind.ADDED) {
                details.add("新增 · " + change.afterPath + " · " + target(change.after, ids, knownPhysicalPages));
            } else if (change.kind == DirectoryDiff.Kind.REMOVED) {
                details.add("移除 · " + change.beforePath + " · " + target(change.before, ids, knownPhysicalPages));
            } else {
                List<String> fields = new ArrayList<>();
                if (change.titleChanged) fields.add("标题");
                if (change.pageChanged) fields.add("目标页");
                if (change.hierarchyChanged) fields.add("层级／上级目录");
                details.add("变更（" + String.join("、", fields) + "）· " + change.beforePath + " · "
                        + target(change.before, ids, knownPhysicalPages) + " → " + change.afterPath + " · "
                        + target(change.after, ids, knownPhysicalPages));
            }
        }
        return new Summary(diff, details);
    }

    public static Summary compare(byte[] before, byte[] after) {
        return compare(before, after, Collections.emptyMap());
    }

    private static String target(TocParser.Row row, Map<Integer, String> ids, Map<String, Integer> known) {
        String id = ids.get(row.pdfPage);
        Integer physical = known == null ? null : known.get(id);
        return (physical != null && physical > 0 ? "PDF 第 " + physical + " 页" : "原笔记页面") + "（页 ID：" + id + "）";
    }

    private static List<TocParser.Row> rows(byte[] bytes, Map<String, Integer> codes) {
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        List<TocParser.Row> rows = new ArrayList<>();
        visit(root.getAsJsonArray("kids"), 1, codes, rows);
        return rows;
    }

    private static void visit(JsonArray kids, int level, Map<String, Integer> codes, List<TocParser.Row> rows) {
        for (JsonElement child : kids) {
            JsonObject node = child.getAsJsonObject();
            String id = node.get("pageId").getAsString();
            int code = codes.computeIfAbsent(id, ignored -> codes.size() + 1);
            rows.add(new TocParser.Row(level, node.get("title").getAsString(), null, code));
            visit(node.getAsJsonArray("kids"), level + 1, codes, rows);
        }
    }
}
