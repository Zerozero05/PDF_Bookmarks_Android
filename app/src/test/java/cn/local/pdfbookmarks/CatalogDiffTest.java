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

    private static String nativeNode(String title, int page) {
        return "{\"title\":\"" + title + "\",\"pageId\":\"\",\"page\":" + page + ",\"kids\":[]}";
    }

    @Test public void nativePagesRemainDistinctAndUnknownWithoutVerifiedNativeContext() {
        byte[] before = catalog(nativeNode("章", 8)), after = catalog(nativeNode("章", 9));
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after);
        assertEquals(1, summary.changed); assertEquals(1, summary.pageChanged); assertEquals(0, summary.unchanged);
        assertEquals(Collections.singletonList(0), summary.targetPages);
        assertTrue(summary.describe().contains("原页索引：8")); assertTrue(summary.describe().contains("原页索引：9"));
        assertFalse(summary.describe().contains("PDF 第"));
        Map<String, Integer> known = new HashMap<>(); known.put("", 10); known.put("pg9", 10);
        assertEquals(Collections.singletonList(0), CatalogDiff.compare(before, after, known).targetPages);
    }

    @Test public void verifiedNativeAndExplicitIdsRepresentTheSameTarget() {
        byte[] before = catalog(nativeNode("章", 8)), after = catalog(node("章", "pg8", ""));
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after, Collections.singletonMap("pg8", 9),
                Collections.singletonMap(8, "pg8"));
        assertEquals(1, summary.unchanged); assertEquals(0, summary.changed);
        assertEquals(0, summary.added); assertEquals(0, summary.removed); assertTrue(summary.details.isEmpty());
        CatalogDiff.Summary conservative = CatalogDiff.compare(before, after, Collections.singletonMap("pg8", 9));
        assertEquals(1, conservative.changed); assertEquals(1, conservative.pageChanged);
        assertTrue(conservative.describe().contains("未核实物理页"));
    }

    @Test public void verifiedNativeTargetsKeepOccurrenceIndicesAndKnownPhysicalPages() {
        byte[] before = catalog(nativeNode("甲", 8) + "," + nativeNode("乙", 9));
        byte[] after = catalog(nativeNode("甲", 8) + "," + node("乙", "pg12", ""));
        Map<Integer, String> nativeIds = new HashMap<>(); nativeIds.put(8, "pg8"); nativeIds.put(9, "pg9");
        Map<String, Integer> known = new HashMap<>(); known.put("pg8", 9); known.put("pg9", 10); known.put("pg12", 13);
        CatalogDiff.Summary summary = CatalogDiff.compare(before, after, known, nativeIds);
        assertEquals(1, summary.unchanged); assertEquals(1, summary.changed); assertEquals(1, summary.pageChanged);
        assertEquals(Collections.singletonList(13), summary.targetPages);
        assertEquals(1, summary.changes.get(0).beforeIndex); assertEquals(1, summary.changes.get(0).afterIndex);
        assertTrue(summary.details.get(0).contains("PDF 第 10 页（页 ID：pg9）"));
        assertTrue(summary.details.get(0).contains("PDF 第 13 页（页 ID：pg12）"));
        CatalogDiff.Summary removed = CatalogDiff.compare(before, catalog(""), known, nativeIds);
        assertEquals(Arrays.asList(9, 10), removed.targetPages);
    }

    @Test public void missingNativeContextNeverGuessesAndLiteralIdsCannotCollideWithNativeKeys() {
        CatalogDiff.Summary summary = CatalogDiff.compare(catalog(nativeNode("章", 8)), catalog(node("章", "native:8", "")),
                Collections.singletonMap("native:8", 99), Collections.emptyMap());
        assertEquals(1, summary.changed); assertEquals(1, summary.pageChanged);
        assertEquals(Collections.singletonList(99), summary.targetPages);
        assertTrue(summary.describe().contains("未核实物理页"));
        CatalogDiff.Summary unresolved = CatalogDiff.compare(catalog(nativeNode("章", 8)), catalog(nativeNode("章", 9)),
                Collections.singletonMap("pg8", 9), Collections.singletonMap(8, "pg8"));
        assertEquals(1, unresolved.changed); assertEquals(Collections.singletonList(0), unresolved.targetPages);
        assertTrue(unresolved.describe().contains("PDF 第 9 页（页 ID：pg8）"));
        assertTrue(unresolved.describe().contains("原页索引：9"));
    }
}
