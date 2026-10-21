package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** 页码试算只基于有效导入后的物理目标；不改原JSON及导入映射。 */
public class PageAdjustmentsTest {
    private static TocParser.Parsed sample() {
        return TocParser.parse("{\"version\":1,\"mapping\":{\"offset\":9},\"bookmarks\":["
                + "{\"title\":\"章甲\",\"page\":1,\"children\":[{\"title\":\"节甲\",\"page\":2},"
                + "{\"title\":\"直接实际页\",\"pdf_page\":3}]},{\"title\":\"章乙\",\"pdf_page\":20}]}", 40);
    }

    private static List<Integer> pages(List<TocParser.Row> rows) {
        List<Integer> result = new ArrayList<>();
        for (TocParser.Row row : rows) result.add(row.pdfPage);
        return result;
    }

    private static void rejects(Runnable action) {
        try { action.run(); fail("非法页码调整被接受"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void offsetUsesParsedPhysicalPagesAndPreservesSourceMappingAndHierarchy() {
        TocParser.Parsed parsed = sample();
        PageAdjustments mapping = new PageAdjustments(parsed.rows, 40);
        List<TocParser.Row> preview = mapping.previewOffset(5);
        assertEquals(Arrays.asList(15, 16, 8, 25), pages(preview));
        assertEquals(Arrays.asList(10, 11, 3, 20), pages(mapping.rows()));
        assertFalse(mapping.isAdjusted());
        for (int i = 0; i < parsed.rows.size(); i++) {
            assertEquals(parsed.rows.get(i).title, preview.get(i).title);
            assertEquals(parsed.rows.get(i).level, preview.get(i).level);
            assertEquals(parsed.rows.get(i).printedPage, preview.get(i).printedPage);
        }
        assertEquals("desktop", parsed.sourceKind);
        assertEquals(Arrays.asList(10, 11, 3, 20), pages(parsed.rows));
    }

    @Test public void applyIsExplicitAndRepeatingSameOffsetDoesNotAccumulate() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        mapping.apply(mapping.previewOffset(5));
        assertTrue(mapping.isAdjusted());
        assertEquals(Arrays.asList(15, 16, 8, 25), pages(mapping.rows()));
        mapping.apply(mapping.previewOffset(5));
        assertEquals(Arrays.asList(15, 16, 8, 25), pages(mapping.rows()));
        mapping.apply(mapping.previewOffset(1));
        assertEquals(Arrays.asList(11, 12, 4, 21), pages(mapping.rows()));
    }

    @Test public void negativeAndZeroOffsetsRespectPageOneAndCanRestoreOriginalTargets() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        mapping.apply(mapping.previewOffset(-2));
        assertEquals(Arrays.asList(8, 9, 1, 18), pages(mapping.rows()));
        rejects(() -> mapping.previewOffset(-3));
        assertEquals(Arrays.asList(8, 9, 1, 18), pages(mapping.rows()));
        mapping.apply(mapping.previewOffset(0));
        assertFalse(mapping.isAdjusted());
        assertEquals(Arrays.asList(10, 11, 3, 20), pages(mapping.rows()));
    }

    @Test public void failedOffsetDoesNotReplacePriorValidWorkAndResetRestoresImport() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        mapping.apply(mapping.previewOffset(2));
        rejects(() -> mapping.previewOffset(21));
        assertEquals(Arrays.asList(12, 13, 5, 22), pages(mapping.rows()));
        mapping.reset();
        assertFalse(mapping.isAdjusted());
        assertEquals(Arrays.asList(10, 11, 3, 20), pages(mapping.rows()));
    }

    @Test public void partialSegmentsIncludeBoundariesAndKeepUncoveredTargets() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        List<TocParser.Row> preview = mapping.previewSegments(Collections.singletonList(
                new PageAdjustments.Segment(10, 11, 20)));
        assertEquals(Arrays.asList(20, 21, 3, 20), pages(preview));
        assertEquals(Arrays.asList(10, 11, 3, 20), pages(mapping.rows()));
        mapping.apply(preview);
        assertEquals(Arrays.asList(20, 21, 3, 20), pages(mapping.rows()));
    }

    @Test public void segmentsSortWithoutChangingCallerOrderAndDoNotAccumulate() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        List<PageAdjustments.Segment> segments = Arrays.asList(
                new PageAdjustments.Segment(20, 20, 5), new PageAdjustments.Segment(10, 11, 12));
        mapping.apply(mapping.previewSegments(segments));
        assertEquals(Arrays.asList(12, 13, 3, 5), pages(mapping.rows()));
        mapping.apply(mapping.previewSegments(segments));
        assertEquals(Arrays.asList(12, 13, 3, 5), pages(mapping.rows()));
        assertEquals(20, segments.get(0).originalStart);
    }

    @Test public void overlappingOriginalSegmentsAreRejectedAndAdjacentRangesAreAllowed() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        rejects(() -> mapping.previewSegments(Arrays.asList(new PageAdjustments.Segment(1, 10, 1),
                new PageAdjustments.Segment(10, 20, 15))));
        List<TocParser.Row> preview = mapping.previewSegments(Arrays.asList(
                new PageAdjustments.Segment(1, 10, 2), new PageAdjustments.Segment(11, 20, 21)));
        assertEquals(Arrays.asList(11, 21, 4, 30), pages(preview));
        assertFalse(mapping.isAdjusted());
    }

    @Test public void fullSegmentRangeMustFitEvenWhenNoBookmarkUsesItsEnd() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        rejects(() -> mapping.previewSegments(Collections.singletonList(new PageAdjustments.Segment(1, 40, 2))));
        rejects(() -> mapping.previewSegments(Collections.singletonList(new PageAdjustments.Segment(1, 41, 1))));
        rejects(() -> mapping.previewSegments(Collections.singletonList(new PageAdjustments.Segment(30, 40, 31))));
        assertEquals(Arrays.asList(10, 11, 3, 20), pages(mapping.rows()));
    }

    @Test public void integerOverflowNeverWrapsAnInvalidTargetIntoThePdf() {
        List<TocParser.Row> rows = Collections.singletonList(new TocParser.Row(1, "最大页", null, Integer.MAX_VALUE));
        PageAdjustments mapping = new PageAdjustments(rows, Integer.MAX_VALUE);
        rejects(() -> mapping.previewOffset(1));
        rejects(() -> mapping.previewOffset(Integer.MIN_VALUE));
        rejects(() -> mapping.previewSegments(Collections.singletonList(
                new PageAdjustments.Segment(1, Integer.MAX_VALUE, 2))));
        assertEquals(Integer.MAX_VALUE, mapping.rows().get(0).pdfPage);
        assertEquals(Integer.MAX_VALUE, mapping.previewSegments(Collections.singletonList(
                new PageAdjustments.Segment(1, Integer.MAX_VALUE, 1))).get(0).pdfPage);
    }

    @Test public void invalidEmptyAndNullSegmentsAreRejected() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        rejects(() -> new PageAdjustments.Segment(0, 2, 1));
        rejects(() -> new PageAdjustments.Segment(3, 2, 1));
        rejects(() -> new PageAdjustments.Segment(1, 2, 0));
        rejects(() -> mapping.previewSegments(Collections.emptyList()));
        rejects(() -> mapping.previewSegments(null));
        rejects(() -> mapping.previewSegments(Collections.singletonList(null)));
    }

    @Test public void applyRejectsIncompleteChangedOrOutOfRangePreviewWithoutMutation() {
        PageAdjustments mapping = new PageAdjustments(sample().rows, 40);
        mapping.apply(mapping.previewOffset(2));
        rejects(() -> mapping.apply(null));
        rejects(() -> mapping.apply(Collections.emptyList()));
        List<TocParser.Row> bad = new ArrayList<>(sample().rows);
        bad.set(0, new TocParser.Row(1, "其他章", 1, 12));
        rejects(() -> mapping.apply(bad));
        bad.set(0, new TocParser.Row(2, "章甲", 1, 12));
        rejects(() -> mapping.apply(bad));
        bad.set(0, new TocParser.Row(1, "章甲", 2, 12));
        rejects(() -> mapping.apply(bad));
        bad.set(0, new TocParser.Row(1, "章甲", 1, 0));
        rejects(() -> mapping.apply(bad));
        assertEquals(Arrays.asList(12, 13, 5, 22), pages(mapping.rows()));
    }

    @Test public void sourceAndAppliedListsAreIsolatedAndSnapshotsAreReadOnly() {
        List<TocParser.Row> source = new ArrayList<>(sample().rows);
        PageAdjustments mapping = new PageAdjustments(source, 40);
        source.clear();
        List<TocParser.Row> preview = new ArrayList<>(mapping.previewOffset(1));
        mapping.apply(preview);
        preview.clear();
        assertEquals(Arrays.asList(11, 12, 4, 21), pages(mapping.rows()));
        try { mapping.rows().clear(); fail("应用快照应只读"); }
        catch (UnsupportedOperationException expected) { assertEquals(4, mapping.rows().size()); }
        try { mapping.originalRows().clear(); fail("原导入快照应只读"); }
        catch (UnsupportedOperationException expected) { assertEquals(4, mapping.originalRows().size()); }
        try { mapping.previewOffset(0).clear(); fail("试算快照应只读"); }
        catch (UnsupportedOperationException expected) { assertEquals(4, mapping.rows().size()); }
    }

    @Test public void jasminumKeepsNullPrintedPagesAndOriginalHierarchy() {
        TocParser.Parsed original = TocParser.parse("{\"info\":{\"schema\":2},\"outline\":["
                + "{\"title\":\"章\",\"level\":1,\"page\":2,\"children\":["
                + "{\"title\":\"节\",\"level\":2,\"page\":4}]}]}", 10);
        PageAdjustments mapping = new PageAdjustments(original.rows, 10);
        mapping.apply(mapping.previewOffset(2));
        assertEquals(Arrays.asList(4, 6), pages(mapping.rows()));
        assertEquals(2, mapping.rows().get(1).level);
        assertNull(mapping.rows().get(0).printedPage);
        assertNull(mapping.rows().get(1).printedPage);
        assertEquals("jasminum", original.sourceKind);
        assertEquals(Arrays.asList(2, 4), pages(original.rows));
    }

    @Test public void originallyInvalidJsonCannotBeAcceptedByPageAdjustmentUi() {
        rejects(() -> TocParser.parse("{\"version\":1,\"bookmarks\":[{\"title\":\"超界\",\"pdf_page\":11}]}", 10));
        rejects(() -> new PageAdjustments(Collections.singletonList(new TocParser.Row(1, "无效页", null, 0)), 10));
        rejects(() -> new PageAdjustments(sample().rows, 0));
    }
}
