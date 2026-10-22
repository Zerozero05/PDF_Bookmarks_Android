package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ReviewDraftTest {
    private static final String PDF = "content://provider/document/primary%3A%E4%B9%A6.pdf";
    private static final String JSON = "content://provider/document/primary%3A%E7%9B%AE%E5%BD%95.json";
    private static final String PDF_SHA = String.join("", Collections.nCopies(64, "a"));
    private static final String JSON_SHA = String.join("", Collections.nCopies(64, "b"));

    private static List<TocParser.Row> rows() {
        return Arrays.asList(new TocParser.Row(1, "章甲", 1, 10),
                new TocParser.Row(2, "节甲", 2, 11), new TocParser.Row(1, "章乙", null, 20));
    }

    private static List<TocParser.Row> adjusted() {
        return Arrays.asList(new TocParser.Row(1, "章甲", 1, 12),
                new TocParser.Row(2, "节甲", 2, 13), new TocParser.Row(1, "章乙", null, 22));
    }

    private static ReviewDraft draft() {
        return ReviewDraft.capture(PDF, JSON, PDF_SHA, JSON_SHA, rows(), adjusted(), 40,
                "章", Collections.singletonList(0), 1, 13, "节甲", 1, -24);
    }

    private static void rejects(Runnable action) {
        try { action.run(); fail("损坏或过期草稿被接受"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void roundTripKeepsOnlyReviewState() {
        ReviewDraft actual = ReviewDraft.fromJson(draft().toJson());
        assertTrue(actual.matches(PDF, JSON, PDF_SHA, JSON_SHA));
        assertEquals("章", actual.query);
        assertEquals(Collections.singletonList(0), actual.collapsedSourceIndices());
        assertEquals(1, actual.selectedSourceIndex);
        assertEquals(13, actual.previewPage);
        assertEquals("节甲", actual.previewTitle);
        assertEquals(1, actual.listFirstPosition);
        assertEquals(-24, actual.listTopOffset);
        assertArrayEquals(new int[]{12, 13, 22}, actual.targetPages());
        assertEquals(draft().toJson(), actual.toJson());
        String json = actual.toJson();
        for (String forbidden : Arrays.asList("confirmed", "deleteJson", "replace", "permission", "task", "running")) {
            assertFalse(forbidden, json.contains("\"" + forbidden + "\""));
        }
    }

    @Test public void restoresTargetsWithoutChangingOriginalTreeOrSourceRows() {
        List<TocParser.Row> original = rows();
        List<TocParser.Row> restored = draft().restoredRows(original, 40);
        assertEquals(3, restored.size());
        for (int i = 0; i < original.size(); i++) {
            TocParser.Row before = original.get(i), after = restored.get(i);
            assertEquals(before.title, after.title);
            assertEquals(before.level, after.level);
            assertEquals(before.printedPage, after.printedPage);
        }
        assertEquals(12, restored.get(0).pdfPage);
        assertEquals(10, original.get(0).pdfPage);
        new PageAdjustments(original, 40).apply(restored);
    }

    @Test public void samePathDoesNotMatchChangedFileContents() {
        ReviewDraft draft = draft();
        assertFalse(draft.matches(PDF, JSON, JSON_SHA, JSON_SHA));
        assertFalse(draft.matches(PDF, JSON, PDF_SHA, PDF_SHA));
        assertFalse(draft.matches(PDF + "1", JSON, PDF_SHA, JSON_SHA));
        assertFalse(draft.matches(PDF, JSON + "1", PDF_SHA, JSON_SHA));
        assertFalse(draft.matches(null, JSON, PDF_SHA, JSON_SHA));
        assertFalse(draft.matches(PDF, JSON, null, JSON_SHA));
        assertTrue(draft.matches(PDF, JSON, PDF_SHA.toUpperCase(), JSON_SHA.toUpperCase()));
    }

    @Test public void rejectsChangedRowCountTitleLevelPrintedPageAndOriginalPage() {
        ReviewDraft draft = draft();
        rejects(() -> draft.validateAgainst(rows().subList(0, 2), 40));
        for (TocParser.Row replacement : Arrays.asList(new TocParser.Row(1, "更名", 1, 10),
                new TocParser.Row(2, "章甲", 1, 10), new TocParser.Row(1, "章甲", 8, 10),
                new TocParser.Row(1, "章甲", 1, 9))) {
            List<TocParser.Row> changed = new ArrayList<>(rows());
            changed.set(0, replacement);
            rejects(() -> draft.validateAgainst(changed, 40));
        }
    }

    @Test public void rejectsOutOfRangeTargetsAndPreviewAgainstActualPageCount() {
        rejects(() -> draft().validateAgainst(rows(), 21));
        String changed = draft().toJson().replace("\"previewPage\":13", "\"previewPage\":41");
        ReviewDraft previewOutside = ReviewDraft.fromJson(changed);
        rejects(() -> previewOutside.validateAgainst(rows(), 40));
        rejects(() -> draft().validateAgainst(rows(), 0));
    }

    @Test public void captureRejectsChangedAdjustedHierarchyAndOutsideTargets() {
        List<TocParser.Row> changed = new ArrayList<>(adjusted());
        changed.set(0, new TocParser.Row(2, "章甲", 1, 12));
        rejects(() -> ReviewDraft.capture(PDF, JSON, PDF_SHA, JSON_SHA, rows(), changed, 40,
                "", Collections.emptyList(), -1, 1, "", 0, 0));
        changed.set(0, new TocParser.Row(1, "章甲", 1, 41));
        rejects(() -> ReviewDraft.capture(PDF, JSON, PDF_SHA, JSON_SHA, rows(), changed, 40,
                "", Collections.emptyList(), -1, 1, "", 0, 0));
    }

    @Test public void rejectsCorruptEmptyTruncatedAndTrailingJson() {
        String valid = draft().toJson();
        for (String corrupt : Arrays.asList("", "{", "null", "[]", valid.substring(0, valid.length() - 2),
                valid + "{}", valid + " // comment")) {
            rejects(() -> ReviewDraft.fromJson(corrupt));
        }
        rejects(() -> ReviewDraft.fromJson(null));
    }

    @Test public void rejectsMissingUnknownDuplicateFieldsAndFutureVersions() {
        String json = draft().toJson();
        rejects(() -> ReviewDraft.fromJson(json.replace("\"version\":1,", "")));
        rejects(() -> ReviewDraft.fromJson(json.replace("\"version\":1", "\"version\":2")));
        rejects(() -> ReviewDraft.fromJson(json.replace("\"version\":1", "\"version\":1,\"version\":1")));
        rejects(() -> ReviewDraft.fromJson(json.replace("\"version\":1", "\"version\":1,\"confirmed\":true")));
        rejects(() -> ReviewDraft.fromJson(json.replace("\"query\":\"章\",", "")));
    }

    @Test public void rejectsCoercedStringFractionExponentNullAndOverflowNumbers() {
        for (String number : Arrays.asList("\"13\"", "13.0", "1.3e1", "null", "true", "2147483648")) {
            rejects(() -> ReviewDraft.fromJson(draft().toJson().replace("\"previewPage\":13", "\"previewPage\":" + number)));
        }
        rejects(() -> ReviewDraft.fromJson(draft().toJson().replace("\"query\":\"章\"", "\"query\":1")));
    }

    @Test public void rejectsInvalidSelectionScrollAndTargetIndices() {
        String valid = draft().toJson();
        for (String field : Arrays.asList("\"selectedSourceIndex\":1", "\"listFirstPosition\":1")) {
            String key = field.substring(0, field.indexOf(':') + 1);
            rejects(() -> ReviewDraft.fromJson(valid.replace(field, key + "3")));
            rejects(() -> ReviewDraft.fromJson(valid.replace(field, key + "-2")));
        }
        rejects(() -> ReviewDraft.fromJson(valid.replace("[12,13,22]", "[0,13,22]")));
        rejects(() -> ReviewDraft.fromJson(valid.replace("[12,13,22]", "[]")));
        rejects(() -> ReviewDraft.fromJson(valid.replace("\"previewPage\":13", "\"previewPage\":-1")));
    }

    @Test public void rejectsDuplicateOutOfBoundsOrLeafCollapsedIndices() {
        String valid = draft().toJson();
        for (String value : Arrays.asList("[-1]", "[3]", "[0,0]", "[null]")) {
            rejects(() -> ReviewDraft.fromJson(valid.replace("\"collapsedSourceIndices\":[0]",
                    "\"collapsedSourceIndices\":" + value)));
        }
        ReviewDraft leaf = ReviewDraft.fromJson(valid.replace("\"collapsedSourceIndices\":[0]", "\"collapsedSourceIndices\":[1]"));
        rejects(() -> leaf.validateAgainst(rows(), 40));
    }

    @Test public void rejectsBadIdentityOrDigest() {
        String valid = draft().toJson();
        rejects(() -> ReviewDraft.fromJson(valid.replace(PDF_SHA, "bad")));
        rejects(() -> ReviewDraft.fromJson(valid.replace("\"pdfUri\":\"" + PDF + "\"", "\"pdfUri\":\"\"")));
        rejects(() -> ReviewDraft.capture(PDF, JSON, PDF_SHA, "", rows(), adjusted(), 40,
                "", Collections.emptyList(), -1, 1, "", 0, 0));
    }

    @Test public void returnedCollectionsCannotChangePersistedDraft() {
        ReviewDraft draft = draft();
        String originalJson = draft.toJson();
        int[] pages = draft.targetPages();
        pages[0] = 999;
        assertEquals(originalJson, draft.toJson());
        try { draft.collapsedSourceIndices().add(2); fail("折叠状态不应可变"); }
        catch (UnsupportedOperationException expected) { assertEquals(originalJson, draft.toJson()); }
    }

    @Test public void emptySearchNoSelectionAndFirstPageAreValid() {
        ReviewDraft draft = ReviewDraft.capture(PDF, JSON, PDF_SHA, JSON_SHA, rows(), rows(), 40,
                "", Collections.emptyList(), -1, 1, "", 0, 0);
        ReviewDraft restored = ReviewDraft.fromJson(draft.toJson());
        restored.validateAgainst(rows(), 40);
        assertEquals(-1, restored.selectedSourceIndex);
        assertEquals(1, restored.previewPage);
        assertEquals(0, restored.listTopOffset);
    }

    @Test public void noPreviewSurvivesRoundTripWithoutInventingPageOne() {
        ReviewDraft before = ReviewDraft.capture(PDF, JSON, PDF_SHA, JSON_SHA, rows(), adjusted(), 40,
                "", Collections.emptyList(), -1, 0, "", 0, 0);
        ReviewDraft restored = ReviewDraft.fromJson(before.toJson());
        restored.validateAgainst(rows(), 40);
        assertEquals(0, restored.previewPage);
        assertEquals("", restored.previewTitle);
        assertEquals(-1, restored.selectedSourceIndex);
        assertArrayEquals(new int[]{12, 13, 22}, restored.targetPages());
        assertEquals(12, restored.restoredRows(rows(), 40).get(0).pdfPage);
    }
}
