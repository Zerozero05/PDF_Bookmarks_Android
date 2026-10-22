package cn.local.pdfbookmarks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GrantSelectionTest {
    private static final String ROOT = "content://documents/tree/primary%3A%E4%B9%A6";
    private static final String CHILD = "content://documents/tree/primary%3A%E4%B9%A6%2Fnote";
    private static final String OTHER = "content://documents/tree/primary%3Aother";

    @Test public void refreshKeepsExactUrisRatherThanOldRowPositions() {
        GrantSelection model = new GrantSelection(Arrays.asList(grant(ROOT, 3), grant(CHILD, 1)));
        model.setSelectionMode(true);
        model.toggle(CHILD);
        model.replaceCurrent(Arrays.asList(grant(OTHER, 3), grant(CHILD, 1), grant(ROOT, 3)));
        assertEquals(Collections.singletonList(CHILD), uris(model.selectedSnapshot()));
        model.replaceCurrent(Arrays.asList(grant(ROOT, 3), grant(OTHER, 3)));
        assertTrue(model.selectedSnapshot().isEmpty());
        assertTrue(model.isSelectionMode());
    }

    @Test public void selectAllUsesCurrentSnapshotAndDoesNotAutoSelectLaterAdditions() {
        GrantSelection model = new GrantSelection(Arrays.asList(grant(ROOT, 1), grant(CHILD, 2)));
        model.selectAll();
        assertTrue(model.isSelectionMode());
        assertEquals(Arrays.asList(ROOT, CHILD), uris(model.selectedSnapshot()));
        model.replaceCurrent(Arrays.asList(grant(OTHER, 3), grant(CHILD, 2), grant(ROOT, 1)));
        assertEquals(Arrays.asList(CHILD, ROOT), uris(model.selectedSnapshot()));
        model.clearSelection();
        assertTrue(model.isSelectionMode());
        assertTrue(model.selectedSnapshot().isEmpty());
        model.selectAll();
        model.setSelectionMode(false);
        assertFalse(model.isSelectionMode());
        assertTrue(model.selectedSnapshot().isEmpty());
    }

    @Test public void rejectsForeignSelectionsAndDuplicateRefreshWithoutLosingValidSelection() {
        GrantSelection model = new GrantSelection(Collections.singletonList(grant(ROOT, 3)));
        model.setSelectionMode(true);
        model.toggle(ROOT);
        try { model.toggle(CHILD); fail(); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        try { model.replaceCurrent(Arrays.asList(grant(ROOT, 3), grant(ROOT, 1))); fail(); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        assertEquals(Collections.singletonList(ROOT), uris(model.selectedSnapshot()));
        model.setSelectionMode(false);
        try { model.toggle(ROOT); fail(); }
        catch (IllegalStateException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void snapshotsCannotBeEditedOrChangedByLaterSelection() {
        GrantSelection model = new GrantSelection(Collections.singletonList(grant(ROOT, 3)));
        model.selectAll();
        List<GrantSelection.Grant> snapshot = model.selectedSnapshot();
        model.clearSelection();
        assertEquals(Collections.singletonList(ROOT), uris(snapshot));
        try { snapshot.clear(); fail(); }
        catch (UnsupportedOperationException expected) { assertNotNull(expected); }
        assertEquals(Collections.singletonList(ROOT), uris(model.currentSnapshot()));
    }

    @Test public void revokingChildDoesNotReleaseParentOrSimilarDecodedUri() throws Exception {
        String decoded = "content://documents/tree/primary:书/note";
        FakeBackend backend = new FakeBackend(grant(ROOT, 3), grant(CHILD, 1), grant(decoded, 3));
        GrantSelection.BatchResult result = GrantSelection.revoke(backend,
                Collections.singletonList(grant(CHILD, 1)));
        assertTrue(result.complete());
        assertEquals(Collections.singletonList(CHILD + "|1"), backend.calls);
        assertEquals(Arrays.asList(ROOT, decoded), uris(backend.current()));
        assertEquals(Collections.singletonList(CHILD), uris(result.released));
    }

    @Test public void flagChangesStopEntireConfirmedBatchBeforeAnyRelease() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 1), grant(CHILD, 3));
        GrantSelection.BatchResult result = GrantSelection.revoke(backend,
                Arrays.asList(grant(ROOT, 3), grant(CHILD, 3)));
        assertTrue(result.changed);
        assertFalse(result.complete());
        assertTrue(backend.calls.isEmpty());
        assertEquals(Arrays.asList(ROOT, CHILD), uris(result.unprocessed));
    }

    @Test public void alreadyMissingGrantStopsBatchAndIsReportedSeparately() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 3));
        GrantSelection.BatchResult result = GrantSelection.revoke(backend,
                Arrays.asList(grant(ROOT, 3), grant(CHILD, 1)));
        assertTrue(result.changed);
        assertEquals(Collections.singletonList(CHILD), uris(result.missing));
        assertEquals(Collections.singletonList(ROOT), uris(result.unprocessed));
        assertTrue(result.released.isEmpty());
        assertTrue(result.failed.isEmpty());
        assertTrue(backend.calls.isEmpty());
    }

    @Test public void releaseFailureReportsPartialSuccessAndPreservesExactFlags() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 3), grant(CHILD, 1), grant(OTHER, 2));
        backend.failUri = CHILD;
        GrantSelection.BatchResult result = GrantSelection.revoke(backend,
                Arrays.asList(grant(ROOT, 3), grant(CHILD, 1), grant(OTHER, 2)));
        assertFalse(result.complete());
        assertFalse(result.changed);
        assertEquals(Arrays.asList(ROOT, OTHER), uris(result.released));
        assertEquals(1, result.failed.size());
        assertEquals(CHILD, result.failed.get(0).grant.uri);
        assertEquals("provider denied", result.failed.get(0).message);
        assertEquals(Arrays.asList(ROOT + "|3", CHILD + "|1", OTHER + "|2"), backend.calls);
        assertEquals(Collections.singletonList(CHILD), uris(backend.current()));
    }

    @Test public void batchStopsWhenRemainingPermissionChangesAfterFirstRelease() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 3), grant(CHILD, 3)) {
            @Override public void release(String uri, int flags) throws Exception {
                super.release(uri, flags);
                values.put(CHILD, grant(CHILD, 1));
            }
        };
        GrantSelection.BatchResult result = GrantSelection.revoke(backend,
                Arrays.asList(grant(ROOT, 3), grant(CHILD, 3)));
        assertTrue(result.changed);
        assertEquals(Collections.singletonList(ROOT), uris(result.released));
        assertEquals(Collections.singletonList(CHILD), uris(result.unprocessed));
        assertEquals(Collections.singletonList(ROOT + "|3"), backend.calls);
    }

    @Test public void laterVerificationFailureRetainsResultsWithoutRevokingUnverifiedItems() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 3), grant(CHILD, 1)) {
            @Override public List<GrantSelection.Grant> current() throws Exception {
                if (!calls.isEmpty()) throw new SecurityException("cannot verify grants");
                return super.current();
            }
        };
        GrantSelection.BatchResult result = GrantSelection.revoke(backend,
                Arrays.asList(grant(ROOT, 3), grant(CHILD, 1)));
        assertFalse(result.complete());
        assertEquals("cannot verify grants", result.verificationError);
        assertEquals(Collections.singletonList(ROOT), uris(result.released));
        assertEquals(Collections.singletonList(CHILD), uris(result.unprocessed));
        assertEquals(Collections.singletonList(ROOT + "|3"), backend.calls);
    }

    @Test public void rejectsDuplicateConfirmedUrisBeforeReadingOrReleasing() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 3));
        try {
            GrantSelection.revoke(backend, Arrays.asList(grant(ROOT, 3), grant(ROOT, 3)));
            fail();
        } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        assertEquals(0, backend.reads);
        assertTrue(backend.calls.isEmpty());
    }

    @Test public void emptySelectionDoesNotReadBackendOrReleaseAnything() throws Exception {
        FakeBackend backend = new FakeBackend(grant(ROOT, 3));
        GrantSelection.BatchResult result = GrantSelection.revoke(backend, Collections.emptyList());
        assertTrue(result.complete());
        assertEquals(0, backend.reads);
        assertTrue(backend.calls.isEmpty());
        assertTrue(result.released.isEmpty());
    }

    @Test public void invalidFlagsOrNonContentUrisCannotBecomeReleaseTargets() {
        for (int flags : new int[] {0, -1, 4, 65, 128}) {
            try { grant(ROOT, flags); fail(); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
        for (String uri : Arrays.asList(null, "", "/storage/book", "file:///book", "content://")) {
            try { grant(uri, 1); fail(); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    private static GrantSelection.Grant grant(String uri, int flags) {
        return new GrantSelection.Grant(uri, flags);
    }

    private static List<String> uris(List<GrantSelection.Grant> grants) {
        List<String> result = new ArrayList<>();
        for (GrantSelection.Grant grant : grants) result.add(grant.uri);
        return result;
    }

    private static class FakeBackend implements GrantSelection.Backend {
        final Map<String, GrantSelection.Grant> values = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        int reads;
        String failUri;

        FakeBackend(GrantSelection.Grant... grants) {
            for (GrantSelection.Grant grant : grants) values.put(grant.uri, grant);
        }

        @Override public List<GrantSelection.Grant> current() throws Exception {
            reads++;
            return new ArrayList<>(values.values());
        }

        @Override public void release(String uri, int flags) throws Exception {
            calls.add(uri + "|" + flags);
            if (uri.equals(failUri)) throw new SecurityException("provider denied");
            GrantSelection.Grant actual = values.get(uri);
            if (actual == null || actual.flags != flags) throw new SecurityException("grant changed");
            values.remove(uri);
        }
    }
}
