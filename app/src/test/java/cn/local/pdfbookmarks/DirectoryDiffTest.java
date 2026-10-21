package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DirectoryDiffTest {
    private static TocParser.Row row(int level, String title, int page) { return new TocParser.Row(level, title, null, page); }
    private static List<TocParser.Row> rows(TocParser.Row... rows) { return Arrays.asList(rows); }

    @Test public void exactDuplicatesAndReorderAreCountedOnceWithoutEdits() {
        List<TocParser.Row> before = rows(row(1, "甲", 1), row(1, "甲", 1), row(1, "乙", 2));
        List<TocParser.Row> after = rows(row(1, "乙", 2), row(1, "甲", 1), row(1, "甲", 1));
        DirectoryDiff.Summary summary = DirectoryDiff.compare(before, after);
        assertEquals(3, summary.unchanged);
        assertTrue(summary.changes.isEmpty());
        assertEquals(3, before.size());
        assertEquals(3, after.size());
    }

    @Test public void handlesDuplicateCountChangesWithoutLosingOccurrences() {
        List<TocParser.Row> before = rows(row(1, "甲", 1), row(1, "甲", 1), row(1, "甲", 1));
        DirectoryDiff.Summary removed = DirectoryDiff.compare(before, rows(row(1, "甲", 1)));
        assertEquals(1, removed.unchanged);
        assertEquals(2, removed.removed);
        DirectoryDiff.Summary added = DirectoryDiff.compare(rows(row(1, "甲", 1)), before);
        assertEquals(2, added.added);
        assertEquals(1, added.unchanged);
    }

    @Test public void separatesAddedRemovedTitlePageAndHierarchyChanges() {
        List<TocParser.Row> before = rows(row(1, "章", 1), row(2, "页码变", 2), row(2, "旧标题", 3),
                row(2, "移到根", 4), row(1, "消失", 8));
        List<TocParser.Row> after = rows(row(1, "章", 1), row(2, "页码变", 6), row(2, "新标题", 3),
                row(1, "移到根", 4), row(1, "新增", 9));
        DirectoryDiff.Summary summary = DirectoryDiff.compare(before, after);
        assertEquals(1, summary.unchanged);
        assertEquals(1, summary.added);
        assertEquals(1, summary.removed);
        assertEquals(3, summary.changed);
        assertEquals(1, summary.titleChanged);
        assertEquals(1, summary.pageChanged);
        assertEquals(1, summary.hierarchyChanged);
        assertTrue(summary.describe().contains("章 › 旧标题"));
        assertTrue(summary.describe().contains("PDF 第 6 页"));
        assertEquals(before.size(), summary.unchanged + summary.changed + summary.removed);
        assertEquals(after.size(), summary.unchanged + summary.changed + summary.added);
    }

    @Test public void changedParentAtSameLevelIsAHierarchyChange() {
        DirectoryDiff.Summary diff = DirectoryDiff.compare(
                rows(row(1, "甲", 1), row(2, "节", 5), row(1, "乙", 2)),
                rows(row(1, "甲", 1), row(1, "乙", 2), row(2, "节", 5)));
        assertEquals(1, diff.hierarchyChanged);
        assertEquals(0, diff.titleChanged);
        assertEquals("甲 › 节", diff.changes.get(0).beforePath);
        assertEquals("乙 › 节", diff.changes.get(0).afterPath);
    }

    @Test public void unanchoredChangeIsNotInventedAsRenameAndUnknownPdfTargetIsExplicit() {
        DirectoryDiff.Summary diff = DirectoryDiff.compare(rows(row(1, "甲", 0)), rows(row(1, "乙", 5)));
        assertEquals(1, diff.added);
        assertEquals(1, diff.removed);
        assertEquals(0, diff.changed);
        assertTrue(diff.describe().contains("目标页未解析"));
    }

    @Test public void duplicateTitleChangedPageMatchingIsDeterministic() {
        List<TocParser.Row> before = rows(row(1, "参考", 1), row(1, "参考", 2), row(1, "参考", 3));
        List<TocParser.Row> after = rows(row(1, "参考", 3), row(1, "参考", 4), row(1, "参考", 5));
        DirectoryDiff.Summary first = DirectoryDiff.compare(before, after);
        DirectoryDiff.Summary second = DirectoryDiff.compare(before, after);
        assertEquals(1, first.unchanged);
        assertEquals(2, first.changed);
        assertEquals(1, first.changes.get(0).before.pdfPage);
        assertEquals(2, first.changes.get(1).before.pdfPage);
        assertEquals(first.describe(), second.describe());
    }

    @Test public void resultIsImmutableAndEmptyListsWork() {
        assertEquals(0, DirectoryDiff.compare(Collections.emptyList(), Collections.emptyList()).added);
        DirectoryDiff.Summary diff = DirectoryDiff.compare(Collections.emptyList(), rows(row(1, "甲", 1)));
        try { diff.changes.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void unnamedExistingBookmarksAndUnresolvedTargetsHaveDistinctRealIdentity() {
        DirectoryDiff.Summary diff = DirectoryDiff.compare(
                rows(row(1, null, 0), row(1, "未命名书签", 5)),
                rows(row(1, "未命名书签", 0), row(1, null, 5)));
        assertEquals(2, diff.changed);
        assertEquals(2, diff.pageChanged);
        assertEquals(0, diff.unchanged);
        assertEquals(0, diff.titleChanged);
        assertEquals("未命名书签", diff.changes.get(1).beforePath);
        assertNull(diff.changes.get(1).before.title);
        assertTrue(diff.describe().contains("目标页未解析"));
        assertTrue(diff.describe().contains("PDF 第 5 页"));
        assertEquals(2, DirectoryDiff.compare(rows(row(1, null, 0), row(1, null, 0)),
                rows(row(1, null, 0), row(1, null, 0))).unchanged);
    }

    @Test public void blankAndNullExistingTitlesDisplayClearlyWithoutChangingComparisonValues() {
        List<TocParser.Row> before = rows(row(1, null, 0), row(2, "", 0), row(1, " \u3000", 2));
        DirectoryDiff.Summary diff = DirectoryDiff.compare(before, Collections.emptyList());
        assertEquals(3, diff.removed);
        assertEquals("未命名书签", diff.changes.get(0).beforePath);
        assertEquals("未命名书签 › 空标题书签", diff.changes.get(1).beforePath);
        assertEquals("空标题书签", diff.changes.get(2).beforePath);
        assertNull(before.get(0).title);
        assertEquals("", before.get(1).title);
        assertEquals(" \u3000", before.get(2).title);
        assertEquals(1, DirectoryDiff.compare(rows(row(1, null, 0)), rows(row(1, "", 0))).titleChanged);
        try { DirectoryDiff.compare(rows((TocParser.Row) null), Collections.emptyList()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("无效")); }
        try { DirectoryDiff.compare(rows(row(2, null, 0)), Collections.emptyList()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("父级")); }
    }
}
