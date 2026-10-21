package cn.local.pdfbookmarks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 只管理目录的显示与选中状态；筛选和折叠不改变实际写入的目录。 */
public final class DirectoryViewModel {
    public static final class VisibleRow {
        public final int sourceIndex;
        public final TocParser.Row row;
        public final boolean hasChildren, expanded, matched, selected;

        private VisibleRow(int sourceIndex, TocParser.Row row, boolean hasChildren,
                           boolean expanded, boolean matched, boolean selected) {
            this.sourceIndex = sourceIndex;
            this.row = row;
            this.hasChildren = hasChildren;
            this.expanded = expanded;
            this.matched = matched;
            this.selected = selected;
        }
    }

    private List<TocParser.Row> rows;
    private final int[] parents, subtreeEnds;
    private final String[] foldedTitles;
    private final Set<Integer> collapsed = new HashSet<>();
    private String query = "", foldedQuery = "";
    private int selectedSourceIndex = -1;

    public DirectoryViewModel(List<TocParser.Row> sourceRows) {
        rows = validatedRows(sourceRows);
        int count = rows.size();
        parents = new int[count];
        subtreeEnds = new int[count];
        foldedTitles = new String[count];
        ArrayDeque<Integer> ancestors = new ArrayDeque<>();
        for (int i = 0; i < count; i++) {
            TocParser.Row row = rows.get(i);
            while (!ancestors.isEmpty() && rows.get(ancestors.peek()).level >= row.level) {
                subtreeEnds[ancestors.pop()] = i;
            }
            parents[i] = ancestors.isEmpty() ? -1 : ancestors.peek();
            foldedTitles[i] = row.title.toLowerCase(Locale.ROOT);
            ancestors.push(i);
        }
        while (!ancestors.isEmpty()) subtreeEnds[ancestors.pop()] = count;
    }

    public int sourceCount() { return rows.size(); }
    public String query() { return query; }
    public boolean isSearching() { return !foldedQuery.isEmpty(); }
    public int selectedSourceIndex() { return selectedSourceIndex; }

    public void setQuery(String value) {
        query = trimQuery(value);
        foldedQuery = query.toLowerCase(Locale.ROOT);
    }

    public int matchCount() {
        if (!isSearching()) return rows.size();
        int count = 0;
        for (String title : foldedTitles) if (title.contains(foldedQuery)) count++;
        return count;
    }

    public boolean hasChildren(int sourceIndex) {
        checkIndex(sourceIndex);
        return subtreeEnds[sourceIndex] > sourceIndex + 1;
    }

    public boolean isExpanded(int sourceIndex) {
        return hasChildren(sourceIndex) && (isSearching() || !collapsed.contains(sourceIndex));
    }

    /** 搜索期间始终呈现命中及其祖先，暂不折叠，清除搜索后恢复之前的折叠状态。 */
    public boolean toggle(int sourceIndex) {
        checkIndex(sourceIndex);
        if (isSearching() || !hasChildren(sourceIndex)) return false;
        if (!collapsed.add(sourceIndex)) collapsed.remove(sourceIndex);
        return true;
    }

    public void expandAll() { collapsed.clear(); }

    public void topLevelOnly() {
        collapsed.clear();
        for (int i = 0; i < rows.size(); i++) {
            if (subtreeEnds[i] > i + 1) collapsed.add(i);
        }
    }

    public Set<Integer> collapsedSourceIndices() {
        return Collections.unmodifiableSet(new HashSet<>(collapsed));
    }

    /** 目录来源索引不会因重复标题、筛选、折叠或页码试算而变化。 */
    public void select(int sourceIndex) {
        checkIndex(sourceIndex);
        selectedSourceIndex = sourceIndex;
        revealSelected();
    }

    public boolean revealSelected() {
        if (selectedSourceIndex < 0) return false;
        if (isSearching() && !includedBySearch()[selectedSourceIndex]) setQuery("");
        for (int parent = parents[selectedSourceIndex]; parent >= 0; parent = parents[parent]) {
            collapsed.remove(parent);
        }
        return true;
    }

    /** 显式页码应用后更新行值；保留原索引对应的标题、层级、印刷页与显示状态。 */
    public void updateRows(List<TocParser.Row> updatedRows) {
        List<TocParser.Row> candidate = validatedRows(updatedRows);
        if (candidate.size() != rows.size()) throw changedSource();
        for (int i = 0; i < rows.size(); i++) {
            TocParser.Row before = rows.get(i), after = candidate.get(i);
            if (before.level != after.level || !before.title.equals(after.title)
                    || !Objects.equals(before.printedPage, after.printedPage)) throw changedSource();
        }
        rows = candidate;
    }

    public List<VisibleRow> visibleRows() {
        List<VisibleRow> visible = new ArrayList<>();
        boolean searching = isSearching();
        boolean[] included = searching ? includedBySearch() : null;
        for (int i = 0; i < rows.size(); i++) {
            if (searching && !included[i]) continue;
            boolean children = subtreeEnds[i] > i + 1;
            boolean expanded = children && (searching || !collapsed.contains(i));
            visible.add(new VisibleRow(i, rows.get(i), children, expanded,
                    searching && foldedTitles[i].contains(foldedQuery), i == selectedSourceIndex));
            if (!searching && children && !expanded) i = subtreeEnds[i] - 1;
        }
        return Collections.unmodifiableList(visible);
    }

    private boolean[] includedBySearch() {
        boolean[] included = new boolean[rows.size()];
        for (int i = rows.size() - 1; i >= 0; i--) {
            included[i] |= foldedTitles[i].contains(foldedQuery);
            if (included[i] && parents[i] >= 0) included[parents[i]] = true;
        }
        return included;
    }

    private void checkIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= rows.size()) {
            throw new IllegalArgumentException("目录索引已失效，请重新核对。");
        }
    }

    private static String trimQuery(String value) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && querySpace(value.charAt(start))) start++;
        while (end > start && querySpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean querySpace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\u0085';
    }

    private static List<TocParser.Row> validatedRows(List<TocParser.Row> source) {
        if (source == null || source.isEmpty()) throw new IllegalArgumentException("目录不能为空。");
        List<TocParser.Row> copy = new ArrayList<>(source);
        int previousLevel = 0;
        for (TocParser.Row row : copy) {
            if (row == null || row.title == null || row.title.isEmpty() || row.pdfPage < 1
                    || row.level < 1 || (long) row.level > (long) previousLevel + 1) {
                throw new IllegalArgumentException("目录标题、层级或PDF目标页无效。");
            }
            previousLevel = row.level;
        }
        return Collections.unmodifiableList(copy);
    }

    private static IllegalArgumentException changedSource() {
        return new IllegalArgumentException("目录来源已变化，请重新建立目录预览。");
    }
}
