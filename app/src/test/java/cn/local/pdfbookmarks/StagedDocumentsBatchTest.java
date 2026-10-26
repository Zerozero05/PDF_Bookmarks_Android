package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The batch read must not hide same-name collisions or expand the requested scope. */
public final class StagedDocumentsBatchTest {
    @Test public void keepsEveryDuplicateAndRepresentsMissingOptionalFiles() {
        StagedReplacement.Document first = doc("1", "catalog.json"), second = doc("2", "catalog.json");
        StagedReplacement.Document pages = doc("3", "pageids.json"), unrelated = doc("4", "ink.note");
        Map<String, List<StagedReplacement.Document>> result = StagedDocuments.indexByName(
                Arrays.asList(first, pages, second, unrelated),
                Arrays.asList("catalog.json", "doc", "pageids.json", "catalog.json.temp"));
        assertEquals(4, result.size());
        assertEquals(Arrays.asList(first, second), result.get("catalog.json"));
        assertEquals(Collections.singletonList(pages), result.get("pageids.json"));
        assertTrue(result.get("doc").isEmpty()); assertTrue(result.get("catalog.json.temp").isEmpty());
        assertFalse(result.containsKey("ink.note"));
    }

    @Test public void batchResultsAreImmutableAndDoNotChangeEarlierChecks() {
        StagedReplacement.Document first = doc("1", "catalog.json"), second = doc("2", "catalog.json");
        List<String> names = Collections.singletonList("catalog.json");
        Map<String, List<StagedReplacement.Document>> before = StagedDocuments.indexByName(Collections.singletonList(first), names);
        Map<String, List<StagedReplacement.Document>> after = StagedDocuments.indexByName(Collections.singletonList(second), names);
        assertSame(first, before.get("catalog.json").get(0)); assertSame(second, after.get("catalog.json").get(0));
        assertThrows(UnsupportedOperationException.class, () -> before.remove("catalog.json"));
        assertThrows(UnsupportedOperationException.class, () -> before.get("catalog.json").clear());
    }

    private static StagedReplacement.Document doc(String id, String name) {
        return new StagedReplacement.Document("content://synthetic.test/document/" + id, name);
    }
}
