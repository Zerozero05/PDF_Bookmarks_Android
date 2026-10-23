package cn.local.pdfbookmarks;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CatalogDiffTest {
    private static String node(String title, String id, String kids) {
        return "{\"title\":\"" + title + "\",\"pageId\":\"" + id + "\",\"kids\":[" + kids + "]}";
    }
    private static byte[] catalog(String nodes) { return ("{\"kids\":[" + nodes + "]}").getBytes(StandardCharsets.UTF_8); }

    @Test public void unknownManualNotePageIsNeverAssignedAGuessedPhysicalPage() {
        byte[] before = catalog(node("手动目录", "handwritten-page-id", ""));
        byte[] after = catalog(node("书籍目录", "pdf-page-id", ""));
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after, Collections.singletonMap("pdf-page-id", 12));
        assertEquals(1, summary.added);
        assertEquals(1, summary.removed);
        assertTrue(summary.describe().contains("PDF 第 12 页（页 ID：pdf-page-id）"));
        assertTrue(summary.describe().contains("原笔记页面（页 ID：handwritten-page-id）"));
        assertFalse(summary.describe().contains("PDF 第 1 页"));
        assertEquals(0, summary.changed);
    }

    @Test public void mergeKeepsManualDirectoryAndShowsOnlyActuallyAddedNodes() {
        String oldNode = node("新建目录", "manual-id", "");
        CatalogDiff.Summary summary = CatalogDiff.compare(catalog(oldNode),
                catalog(oldNode + "," + node("第一章", "physical-id", node("节", "section-id", ""))));
        assertEquals(1, summary.unchanged);
        assertEquals(2, summary.added);
        assertEquals(0, summary.removed);
        assertTrue(summary.describe().contains("第一章 › 节"));
        assertTrue(summary.describe().contains("section-id"));
    }

    @Test public void preservesDuplicateOccurrencesAndIdentifiesActualPageIdChanges() {
        byte[] before = catalog(node("甲", "old-id", "") + "," + node("甲", "old-id", ""));
        byte[] after = catalog(node("甲", "old-id", "") + "," + node("甲", "new-id", ""));
        byte[] saved = before.clone();
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after);
        assertEquals(1, summary.unchanged);
        assertEquals(1, summary.changed);
        assertEquals(1, summary.pageChanged);
        assertTrue(summary.describe().contains("old-id"));
        assertTrue(summary.describe().contains("new-id"));
        assertArrayEquals(saved, before);
        try { summary.details.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void metadataAlignsDetailsWithKnownAddedAndUnknownRemovedPages() {
        byte[] before = catalog(node("手动目录", "manual-id", ""));
        byte[] after = catalog(node("新增目录", "physical-id", ""));
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after, Collections.singletonMap("physical-id", 12));
        assertEquals(summary.details.size(), summary.changes.size());
        assertEquals(summary.details.size(), summary.targetPages.size());
        assertEquals(Arrays.asList(12, 0), summary.targetPages);
        assertEquals(DirectoryDiff.Kind.ADDED, summary.changes.get(0).kind);
        assertEquals(-1, summary.changes.get(0).beforeIndex); assertEquals(0, summary.changes.get(0).afterIndex);
        assertTrue(summary.details.get(0).contains("physical-id"));
        assertEquals(DirectoryDiff.Kind.REMOVED, summary.changes.get(1).kind);
        assertEquals(0, summary.changes.get(1).beforeIndex); assertEquals(-1, summary.changes.get(1).afterIndex);
        assertTrue(summary.details.get(1).contains("manual-id"));
        assertThrows(UnsupportedOperationException.class, () -> summary.changes.clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.targetPages.set(0, 99));
    }

    @Test public void duplicateTitlesUseActualChangedPageIdAndOriginalOccurrence() {
        byte[] before = catalog(node("相同标题", "first-id", "") + "," + node("相同标题", "second-id", ""));
        byte[] after = catalog(node("相同标题", "second-id", "") + "," + node("相同标题", "new-id", ""));
        Map<String, Integer> known = new HashMap<>();
        known.put("first-id", 7); known.put("second-id", 12); known.put("new-id", 25);
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after, known);
        assertEquals(1, summary.unchanged); assertEquals(1, summary.changed);
        assertEquals(Collections.singletonList(25), summary.targetPages);
        assertEquals(0, summary.changes.get(0).beforeIndex); assertEquals(1, summary.changes.get(0).afterIndex);
        assertTrue(summary.details.get(0).contains("PDF 第 7 页（页 ID：first-id）"));
        assertTrue(summary.details.get(0).contains("PDF 第 25 页（页 ID：new-id）"));
        known.put("new-id", 30); assertEquals(Collections.singletonList(25), summary.targetPages);
    }

    @Test public void changedTargetNeverFallsBackFromUnknownNewPageToKnownOldPage() {
        byte[] before = catalog(node("章", "old-id", "")), after = catalog(node("章", "manual-id", ""));
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after, Collections.singletonMap("old-id", 8));
        assertEquals(1, summary.changed); assertEquals(Collections.singletonList(0), summary.targetPages);
        assertTrue(summary.details.get(0).contains("PDF 第 8 页"));
        assertTrue(summary.details.get(0).contains("原笔记页面（页 ID：manual-id）"));
        assertEquals(Collections.singletonList(0), CatalogDiff.compare(before, after).targetPages);
        assertEquals(Collections.singletonList(0), CatalogDiff.compare(before, after, Collections.singletonMap("manual-id", -1)).targetPages);
    }

    @Test public void removalTargetsTheKnownOldPhysicalPage() {
        CatalogDiff.Summary summary = CatalogDiff.compare(catalog(node("旧条目", "old-id", "")), catalog(""),
                Collections.singletonMap("old-id", 19));
        assertEquals(1, summary.removed); assertEquals(Collections.singletonList(19), summary.targetPages);
        assertEquals(0, summary.changes.get(0).beforeIndex); assertEquals(-1, summary.changes.get(0).afterIndex);
    }
}
