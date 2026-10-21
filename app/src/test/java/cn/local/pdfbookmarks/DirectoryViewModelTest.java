package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 搜索和折叠是显示状态，始终使用原始索引核对实际写入条目。 */
public class DirectoryViewModelTest {
    private static List<TocParser.Row> sample() {
        return Arrays.asList(
                new TocParser.Row(1, "甲章", 1, 10),
                new TocParser.Row(2, "Alpha 第一节", 2, 11),
                new TocParser.Row(3, "深层 Gamma", 3, 12),
                new TocParser.Row(2, "Beta 第二节", 4, 13),
                new TocParser.Row(1, "乙章", 5, 14),
                new TocParser.Row(2, "ALPHA 第二处", 6, 15),
                new TocParser.Row(1, "参考文献", null, 20));
    }

    private static List<Integer> indices(DirectoryViewModel model) {
        List<Integer> result = new ArrayList<>();
        for (DirectoryViewModel.VisibleRow row : model.visibleRows()) result.add(row.sourceIndex);
        return result;
    }

    @Test public void allRowsKeepOriginalIndicesAndLeafStatus() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6), indices(model));
        assertEquals(7, model.sourceCount());
        assertEquals(7, model.matchCount());
        assertTrue(model.hasChildren(0));
        assertTrue(model.hasChildren(1));
        assertFalse(model.hasChildren(2));
        assertFalse(model.toggle(2));
        assertEquals(-1, model.selectedSourceIndex());
    }

    @Test public void searchFindsCaseInsensitiveTitlesAndTheirAncestors() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.setQuery("  alpha  ");
        assertEquals(Arrays.asList(0, 1, 4, 5), indices(model));
        assertEquals(2, model.matchCount());
        assertFalse(model.visibleRows().get(0).matched);
        assertTrue(model.visibleRows().get(1).matched);
        assertEquals("Alpha 第一节", model.visibleRows().get(1).row.title);
        assertEquals(11, model.visibleRows().get(1).row.pdfPage);
    }

    @Test public void searchTemporarilyRevealsCollapsedChildrenAndRestoresState() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.topLevelOnly();
        assertEquals(Arrays.asList(0, 4, 6), indices(model));
        model.setQuery("Gamma");
        assertEquals(Arrays.asList(0, 1, 2), indices(model));
        assertFalse(model.toggle(0));
        model.setQuery("");
        assertEquals(Arrays.asList(0, 4, 6), indices(model));
        assertEquals(3, model.collapsedSourceIndices().size());
    }

    @Test public void emptyWhitespaceAndNullQueriesRestoreFullView() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        for (String query : new String[]{"", " \t\n ", "\u3000\u00a0\u0085", null}) {
            model.setQuery("missing");
            assertTrue(model.visibleRows().isEmpty());
            assertEquals(0, model.matchCount());
            model.setQuery(query);
            assertFalse(model.isSearching());
            assertEquals(7, model.visibleRows().size());
        }
    }

    @Test public void fullWidthSpacesAroundChineseInputDoNotHideValidMatches() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.setQuery("\u3000第一节\u00a0");
        assertEquals("第一节", model.query());
        assertEquals(Arrays.asList(0, 1), indices(model));
        assertEquals(1, model.matchCount());
    }

    @Test public void topLevelAndExpandDoNotChangeSourceRows() {
        List<TocParser.Row> rows = new ArrayList<>(sample());
        DirectoryViewModel model = new DirectoryViewModel(rows);
        model.topLevelOnly();
        assertEquals(Arrays.asList(0, 4, 6), indices(model));
        model.expandAll();
        assertEquals(7, model.visibleRows().size());
        assertEquals(7, rows.size());
        assertEquals(3, rows.get(2).level);
        assertEquals(Integer.valueOf(3), rows.get(2).printedPage);
        assertEquals(12, rows.get(2).pdfPage);
    }

    @Test public void selectingHiddenSourceRevealsAncestorsAndClearsUnrelatedSearch() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.topLevelOnly();
        model.setQuery("参考文献");
        model.select(2);
        assertFalse(model.isSearching());
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 6), indices(model));
        assertEquals(2, model.selectedSourceIndex());
        assertTrue(model.visibleRows().get(2).selected);
        assertEquals("深层 Gamma", model.visibleRows().get(2).row.title);
    }

    @Test public void selectingSearchHitKeepsQueryAndCanRevealAfterExplicitCollapse() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.setQuery("alpha");
        model.select(5);
        assertEquals("alpha", model.query());
        assertEquals(5, model.selectedSourceIndex());
        model.setQuery("");
        assertTrue(model.toggle(4));
        assertFalse(indices(model).contains(5));
        assertTrue(model.revealSelected());
        assertTrue(indices(model).contains(5));
    }

    @Test public void duplicateTitlesStayDistinctAndSelectingOneDoesNotSelectBoth() {
        List<TocParser.Row> rows = Arrays.asList(new TocParser.Row(1, "重复", null, 1),
                new TocParser.Row(1, "重复", null, 2));
        DirectoryViewModel model = new DirectoryViewModel(rows);
        model.setQuery("重复");
        model.select(1);
        assertEquals(Arrays.asList(0, 1), indices(model));
        assertFalse(model.visibleRows().get(0).selected);
        assertTrue(model.visibleRows().get(1).selected);
        assertEquals(2, model.visibleRows().get(1).row.pdfPage);
    }

    @Test public void updatingMappedPagesPreservesSearchFoldSelectionAndPrintedPages() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.topLevelOnly();
        model.select(5);
        model.setQuery("alpha");
        PageAdjustments adjustment = new PageAdjustments(sample(), 100);
        model.updateRows(adjustment.previewOffset(4));
        assertEquals("alpha", model.query());
        assertEquals(5, model.selectedSourceIndex());
        assertEquals(Arrays.asList(0, 1, 4, 5), indices(model));
        assertEquals(19, model.visibleRows().get(3).row.pdfPage);
        assertEquals(Integer.valueOf(6), model.visibleRows().get(3).row.printedPage);
        model.setQuery("");
        assertEquals(Arrays.asList(0, 4, 5, 6), indices(model));
    }

    @Test public void changingSourceStructureRejectsWithoutLosingValidSelection() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        model.select(1);
        List<TocParser.Row> changed = new ArrayList<>(sample());
        changed.set(1, new TocParser.Row(2, "其他标题", 2, 11));
        try { model.updateRows(changed); fail("来源变化应拒绝"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        assertEquals(1, model.selectedSourceIndex());
        assertEquals("Alpha 第一节", model.visibleRows().get(1).row.title);
    }

    @Test public void deepFlatTreeSearchAndRevealUseNoRecursiveTraversal() {
        List<TocParser.Row> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(new TocParser.Row(i + 1, i == 4999 ? "唯一命中" : "层" + i, null, 1));
        }
        DirectoryViewModel model = new DirectoryViewModel(rows);
        model.topLevelOnly();
        assertEquals(1, model.visibleRows().size());
        model.setQuery("唯一命中");
        assertEquals(5000, model.visibleRows().size());
        assertEquals(1, model.matchCount());
        model.select(4999);
        model.setQuery("");
        assertEquals(5000, model.visibleRows().size());
    }

    @Test public void callerListChangesCannotChangeModelAndSnapshotsAreReadOnly() {
        List<TocParser.Row> rows = new ArrayList<>(sample());
        DirectoryViewModel model = new DirectoryViewModel(rows);
        rows.clear();
        assertEquals(7, model.sourceCount());
        try { model.visibleRows().clear(); fail("显示快照应只读"); }
        catch (UnsupportedOperationException expected) { assertEquals(7, model.sourceCount()); }
        model.topLevelOnly();
        try { model.collapsedSourceIndices().clear(); fail("折叠快照应只读"); }
        catch (UnsupportedOperationException expected) { assertEquals(3, model.visibleRows().size()); }
    }

    @Test public void invalidIndexOrSkippedHierarchyDoesNotSilentlySelectAnotherRow() {
        DirectoryViewModel model = new DirectoryViewModel(sample());
        assertFalse(model.revealSelected());
        for (int index : new int[]{-1, 7}) {
            try { model.select(index); fail("失效索引应拒绝"); }
            catch (IllegalArgumentException expected) { assertEquals(-1, model.selectedSourceIndex()); }
        }
        try { new DirectoryViewModel(Arrays.asList(new TocParser.Row(2, "跳级", null, 1))); fail(); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }
}
