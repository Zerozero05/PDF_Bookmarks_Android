package cn.local.pdfbookmarks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Read-only, deterministic comparison; it never changes the writer's replacement policy. */
public final class DirectoryDiff {
    private DirectoryDiff() { }

    public enum Kind { ADDED, REMOVED, CHANGED }

    public static final class Change {
        public final Kind kind;
        public final TocParser.Row before, after;
        public final String beforePath, afterPath;
        public final boolean titleChanged, pageChanged, hierarchyChanged;

        private Change(Kind kind, Entry before, Entry after) {
            this.kind = kind;
            this.before = before == null ? null : before.row;
            this.after = after == null ? null : after.row;
            beforePath = before == null ? "" : before.path;
            afterPath = after == null ? "" : after.path;
            titleChanged = kind == Kind.CHANGED && !Objects.equals(this.before.title, this.after.title);
            pageChanged = kind == Kind.CHANGED && this.before.pdfPage != this.after.pdfPage;
            hierarchyChanged = kind == Kind.CHANGED && (this.before.level != this.after.level
                    || !before.parentKey.equals(after.parentKey));
        }

        public String describe() {
            if (kind == Kind.ADDED) return "新增 · " + afterPath + " · " + page(after);
            if (kind == Kind.REMOVED) return "移除 · " + beforePath + " · " + page(before);
            List<String> fields = new ArrayList<>();
            if (titleChanged) fields.add("标题");
            if (pageChanged) fields.add("目标页");
            if (hierarchyChanged) fields.add("层级／上级目录");
            return "变更（" + String.join("、", fields) + "）· " + beforePath + " · " + page(before)
                    + " → " + afterPath + " · " + page(after);
        }

        private static String page(TocParser.Row row) {
            return row.pdfPage > 0 ? "PDF 第 " + row.pdfPage + " 页" : "目标页未解析";
        }
    }

    public static final class Summary {
        public final int added, removed, changed, unchanged, titleChanged, pageChanged, hierarchyChanged;
        public final List<Change> changes;

        private Summary(List<Change> changes, int unchanged) {
            this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
            this.unchanged = unchanged;
            int adds = 0, removes = 0, edits = 0, titles = 0, pages = 0, hierarchy = 0;
            for (Change change : changes) {
                if (change.kind == Kind.ADDED) adds++;
                else if (change.kind == Kind.REMOVED) removes++;
                else {
                    edits++;
                    if (change.titleChanged) titles++;
                    if (change.pageChanged) pages++;
                    if (change.hierarchyChanged) hierarchy++;
                }
            }
            added = adds;
            removed = removes;
            changed = edits;
            titleChanged = titles;
            pageChanged = pages;
            hierarchyChanged = hierarchy;
        }

        public String counts() {
            return "新增 " + added + " · 移除 " + removed + " · 变更 " + changed + " · 保留 " + unchanged;
        }

        public String describe() {
            StringBuilder result = new StringBuilder(counts());
            for (Change change : changes) result.append("\n\n").append(change.describe());
            return result.toString();
        }
    }

    /**
     * Matches exact path + target first, then path, title + target, title, parent + target.
     * Unanchored differences stay additions/removals instead of guessing a rename.
     * Equal duplicates are paired in original order; every occurrence is counted once.
     */
    public static Summary compare(List<TocParser.Row> before, List<TocParser.Row> after) {
        List<Entry> oldEntries = entries(before), newEntries = entries(after);
        match(oldEntries, newEntries, entry -> entry.key + "@" + entry.row.pdfPage);
        match(oldEntries, newEntries, entry -> entry.key);
        match(oldEntries, newEntries, entry -> part(entry.row.title) + "@" + entry.row.pdfPage);
        match(oldEntries, newEntries, entry -> part(entry.row.title));
        match(oldEntries, newEntries, entry -> entry.parentKey + "@" + entry.row.pdfPage);
        List<Change> changes = new ArrayList<>();
        int unchanged = 0;
        for (Entry entry : newEntries) {
            if (entry.match == null) changes.add(new Change(Kind.ADDED, null, entry));
            else {
                Change change = new Change(Kind.CHANGED, entry.match, entry);
                if (change.titleChanged || change.pageChanged || change.hierarchyChanged) changes.add(change);
                else unchanged++;
            }
        }
        for (Entry entry : oldEntries) if (entry.match == null) changes.add(new Change(Kind.REMOVED, entry, null));
        return new Summary(changes, unchanged);
    }

    private static void match(List<Entry> before, List<Entry> after, Function<Entry, String> key) {
        Map<String, ArrayDeque<Entry>> available = new HashMap<>();
        for (Entry entry : before) if (entry.match == null) {
            available.computeIfAbsent(key.apply(entry), ignored -> new ArrayDeque<>()).addLast(entry);
        }
        for (Entry entry : after) if (entry.match == null) {
            ArrayDeque<Entry> candidates = available.get(key.apply(entry));
            if (candidates != null && !candidates.isEmpty()) {
                Entry previous = candidates.removeFirst();
                previous.match = entry;
                entry.match = previous;
            }
        }
    }

    private static List<Entry> entries(List<TocParser.Row> rows) {
        if (rows == null) throw new IllegalArgumentException("待比较目录不能为空引用");
        List<Entry> result = new ArrayList<>();
        List<Entry> parents = new ArrayList<>();
        for (TocParser.Row row : rows) {
            if (row == null || row.level < 1) throw new IllegalArgumentException("待比较目录条目无效");
            while (parents.size() >= row.level) parents.remove(parents.size() - 1);
            if (parents.size() != row.level - 1) throw new IllegalArgumentException("待比较目录缺少父级");
            Entry parent = parents.isEmpty() ? null : parents.get(parents.size() - 1);
            Entry entry = new Entry(row, parent);
            result.add(entry);
            parents.add(entry);
        }
        return result;
    }

    // Existing PDF bookmarks may have no title. Keep that distinct from an empty or literal label.
    private static String part(String value) { return value == null ? "N;" : "S" + value.length() + ":" + value; }

    private static String displayTitle(String value) {
        if (value == null) return "未命名书签";
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch) && ch != '\u0085') return value;
        }
        return "空标题书签";
    }

    private static final class Entry {
        final TocParser.Row row;
        final String key, parentKey, path;
        Entry match;

        Entry(TocParser.Row row, Entry parent) {
            this.row = row;
            parentKey = parent == null ? "" : parent.key;
            key = parentKey + part(row.title);
            path = parent == null ? displayTitle(row.title) : parent.path + " › " + displayTitle(row.title);
        }
    }
}
