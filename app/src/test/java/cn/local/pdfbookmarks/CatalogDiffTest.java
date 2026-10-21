package cn.local.pdfbookmarks;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

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
}
