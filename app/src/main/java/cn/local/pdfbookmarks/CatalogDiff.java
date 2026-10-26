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
        public final List<DirectoryDiff.Change> changes;
        /** Known one-based physical PDF pages, parallel to details and changes; 0 means unknown. */
        public final List<Integer> targetPages;

        private Summary(DirectoryDiff.Summary diff, List<String> details, List<Integer> targetPages) {
            added = diff.added;
            removed = diff.removed;
            changed = diff.changed;
            unchanged = diff.unchanged;
            titleChanged = diff.titleChanged;
            pageChanged = diff.pageChanged;
            hierarchyChanged = diff.hierarchyChanged;
            this.details = Collections.unmodifiableList(new ArrayList<>(details));
            changes = diff.changes;
            this.targetPages = Collections.unmodifiableList(new ArrayList<>(targetPages));
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
        return compare(before, after, knownPhysicalPages, Collections.emptyMap());
    }

    /** Native page indices are usable only with mappings verified by EnjoyCatalog's native-format entry. */
    public static Summary compare(byte[] before, byte[] after, Map<String, Integer> knownPhysicalPages,
                                  Map<Integer, String> nativePageIds) {
        if (before == null || after == null) throw new IllegalArgumentException("享做目录预览需要原目录和新目录");
        Map<String, Integer> targetCodes = new LinkedHashMap<>();
        Map<Integer, Target> targets = new LinkedHashMap<>();
        List<TocParser.Row> oldRows = rows(before, targetCodes, targets, nativePageIds);
        List<TocParser.Row> newRows = rows(after, targetCodes, targets, nativePageIds);
        DirectoryDiff.Summary diff = DirectoryDiff.compare(oldRows, newRows);
        List<String> details = new ArrayList<>();
        List<Integer> targetPages = new ArrayList<>();
        for (DirectoryDiff.Change change : diff.changes) {
            targetPages.add(physicalPage(change.after != null ? change.after : change.before, targets, knownPhysicalPages));
            if (change.kind == DirectoryDiff.Kind.ADDED) {
                details.add("新增 · " + change.afterPath + " · " + target(change.after, targets, knownPhysicalPages));
            } else if (change.kind == DirectoryDiff.Kind.REMOVED) {
                details.add("移除 · " + change.beforePath + " · " + target(change.before, targets, knownPhysicalPages));
            } else {
                List<String> fields = new ArrayList<>();
                if (change.titleChanged) fields.add("标题");
                if (change.pageChanged) fields.add("目标页");
                if (change.hierarchyChanged) fields.add("层级／上级目录");
                details.add("变更（" + String.join("、", fields) + "）· " + change.beforePath + " · "
                        + target(change.before, targets, knownPhysicalPages) + " → " + change.afterPath + " · "
                        + target(change.after, targets, knownPhysicalPages));
            }
        }
        return new Summary(diff, details, targetPages);
    }

    public static Summary compare(byte[] before, byte[] after) {
        return compare(before, after, Collections.emptyMap());
    }

    private static String target(TocParser.Row row, Map<Integer, Target> targets, Map<String, Integer> known) {
        Target target = targets.get(row.pdfPage);
        if (target.id == null) return "原目录页面（原页索引：" + target.nativePage + "；页 ID 为空，未核实物理页）";
        int physical = physicalPage(row, targets, known);
        return (physical > 0 ? "PDF 第 " + physical + " 页" : "原笔记页面") + "（页 ID：" + target.id + "）";
    }

    private static int physicalPage(TocParser.Row row, Map<Integer, Target> targets, Map<String, Integer> known) {
        Target target = targets.get(row.pdfPage);
        Integer physical = known == null || target.id == null ? null : known.get(target.id);
        return physical != null && physical > 0 ? physical : 0;
    }

    private static List<TocParser.Row> rows(byte[] bytes, Map<String, Integer> codes,
                                           Map<Integer, Target> targets, Map<Integer, String> nativePageIds) {
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        List<TocParser.Row> rows = new ArrayList<>();
        visit(root.getAsJsonArray("kids"), 1, codes, targets, nativePageIds, rows);
        return rows;
    }

    private static void visit(JsonArray kids, int level, Map<String, Integer> codes,
                               Map<Integer, Target> targets, Map<Integer, String> nativePageIds,
                               List<TocParser.Row> rows) {
        for (JsonElement child : kids) {
            JsonObject node = child.getAsJsonObject();
            Target target = new Target(node, nativePageIds);
            String key = target.id == null ? "native\u0000" + target.nativePage : "id\u0000" + target.id;
            int code = codes.computeIfAbsent(key, ignored -> codes.size() + 1);
            targets.putIfAbsent(code, target);
            rows.add(new TocParser.Row(level, node.get("title").getAsString(), null, code));
            visit(node.getAsJsonArray("kids"), level + 1, codes, targets, nativePageIds, rows);
        }
    }

    private static final class Target {
        final String id, nativePage;

        Target(JsonObject node, Map<Integer, String> nativePageIds) {
            String explicit = node.get("pageId").getAsString();
            JsonElement page = node.get("page");
            String pageValue = page == null ? "未知" : page.toString();
            Integer index = null;
            if (page != null && page.isJsonPrimitive() && page.getAsJsonPrimitive().isNumber()
                    && pageValue.matches("-?(0|[1-9][0-9]*)")) {
                try { int parsed = Integer.parseInt(pageValue); if (parsed >= 0) index = parsed; }
                catch (NumberFormatException ignored) { /* No verified integer index. */ }
            }
            nativePage = index == null ? pageValue : index.toString();
            String verified = null;
            if (explicit.isEmpty() && index != null && nativePageIds != null) verified = nativePageIds.get(index);
            id = !explicit.isEmpty() ? explicit : verified == null || verified.isEmpty() ? null : verified;
        }
    }
}
