package cn.local.pdfbookmarks;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SharedImportTest {
    @Test public void acceptsPdfAndJsonPairWithoutRequiringSameName() {
        SharedImport.Document pdf = new SharedImport.Document("content://provider/book", "书籍.PDF");
        SharedImport.Document json = new SharedImport.Document("content://other/toc", "jasminum-outline.json");
        assertEquals(2, SharedImport.validate(Arrays.asList(json, pdf)).size());
        assertFalse(json.pdf);
        assertTrue(pdf.pdf);
    }

    @Test public void allowsSingleFileToCompleteExistingSelectionLater() {
        assertEquals(1, SharedImport.validate(Collections.singletonList(
                new SharedImport.Document("content://provider/toc", "目录.json"))).size());
    }

    @Test public void rejectsPathsNetworkAndUnknownContentRatherThanOpeningThem() {
        for (String uri : Arrays.asList("file:///storage/book.pdf", "https://host/book.pdf", "/book.pdf", "content:/book")) {
            try { new SharedImport.Document(uri, "book.pdf"); fail(uri); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
        try { new SharedImport.Document("content://provider/file", "notes.ncc"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("PDF")); }
    }

    @Test public void rejectsAmbiguousPairsAndEmptyOrOversizedShares() {
        SharedImport.Document pdf = new SharedImport.Document("content://provider/a", "a.pdf");
        SharedImport.Document other = new SharedImport.Document("content://provider/b", "b.pdf");
        for (java.util.List<SharedImport.Document> value : Arrays.asList(
                Collections.<SharedImport.Document>emptyList(), Arrays.asList(pdf, other), Arrays.asList(pdf, other, pdf))) {
            try { SharedImport.validate(value); fail(); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }
}
