package cn.local.pdfbookmarks;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** A direct path proof replaces only membership enumeration, never fresh identity checks. */
public final class StagedDocumentsIdentityTest {
    private static final String ROOT = "root", FOLDER = "folder", ID = "document";

    @Test public void directParentUsesFreshExactMetadataOnEveryRead() throws Exception {
        Lookup lookup = new Lookup();
        StagedDocuments.Entry first = read(lookup);
        assertSame(lookup.current, first);
        lookup.current = entry(ID, "renamed.pdf");
        assertSame(lookup.current, read(lookup));
        assertNotSame(first, lookup.current);
        assertEquals(2, lookup.paths); assertEquals(2, lookup.metadata); assertEquals(0, lookup.listings);
    }

    @Test public void missingAndUnsupportedPathUseOriginalFreshListing() throws Exception {
        Lookup lookup = new Lookup(); lookup.path = null;
        assertSame(lookup.listed, read(lookup));
        lookup.pathFailure = new UnsupportedOperationException("not implemented");
        lookup.listed = entry(ID, "new-name.pdf");
        assertSame(lookup.listed, read(lookup));
        assertEquals(2, lookup.listings); assertEquals(0, lookup.metadata);
    }

    @Test public void anotherCanonicalParentRequiresFreshMembership() throws Exception {
        Lookup lookup = new Lookup(); lookup.path = Arrays.asList(ROOT, "other-parent", ID);
        assertSame(lookup.listed, read(lookup));
        assertEquals(1, lookup.listings); assertEquals(0, lookup.metadata);
        lookup.listFailure = new IOException("not in original folder");
        assertSame(lookup.listFailure, assertThrows(IOException.class, () -> read(lookup)));
    }

    @Test public void grandchildIsNotAcceptedAsDirectChild() {
        Lookup lookup = new Lookup(); lookup.path = Arrays.asList(ROOT, FOLDER, "nested", ID);
        lookup.listFailure = new IOException("not an immediate child");
        assertSame(lookup.listFailure, assertThrows(IOException.class, () -> read(lookup)));
        assertEquals(1, lookup.listings); assertEquals(0, lookup.metadata);
    }

    @Test public void wrongTreeOrEndpointIsRejectedWithoutFallback() {
        for (List<String> path : Arrays.asList(Arrays.asList("another-root", FOLDER, ID),
                Arrays.asList(ROOT, FOLDER, "another-document"), Collections.<String>emptyList(),
                Arrays.asList(ROOT, null, ID))) {
            Lookup lookup = new Lookup(); lookup.path = path;
            assertThrows(IOException.class, () -> read(lookup));
            assertEquals(0, lookup.listings); assertEquals(0, lookup.metadata);
        }
    }

    @Test public void treeRootCannotBeMistakenForAChild() {
        Lookup lookup = new Lookup(); lookup.path = Collections.singletonList(ROOT);
        lookup.listFailure = new IOException("root is not its own child");
        assertSame(lookup.listFailure, assertThrows(IOException.class,
                () -> StagedDocuments.currentChild(ROOT, ROOT, ROOT, lookup)));
        assertEquals(1, lookup.listings); assertEquals(0, lookup.metadata);
    }

    @Test public void permissionFailureDoesNotFallBackToAListing() {
        Lookup lookup = new Lookup(); lookup.pathFailure = new SecurityException("grant revoked");
        assertSame(lookup.pathFailure, assertThrows(SecurityException.class, () -> read(lookup)));
        assertEquals(0, lookup.listings); assertEquals(0, lookup.metadata);
    }

    @Test public void missingDocumentDoesNotFallBackToAListing() {
        Lookup lookup = new Lookup(); lookup.missing = new FileNotFoundException("moved");
        assertSame(lookup.missing, assertThrows(FileNotFoundException.class, () -> read(lookup)));
        assertEquals(0, lookup.listings); assertEquals(0, lookup.metadata);
    }

    @Test public void identityChangeAfterPathProofIsRejected() {
        Lookup lookup = new Lookup(); lookup.current = entry("other-id", "original.pdf");
        assertThrows(IOException.class, () -> read(lookup));
        assertEquals(1, lookup.metadata); assertEquals(0, lookup.listings);
        lookup.current = null;
        assertThrows(IOException.class, () -> read(lookup));
    }

    @Test public void exactMetadataMustBeUniqueAndReturnTheRequestedId() throws Exception {
        StagedDocuments.Entry expected = entry(ID, "original.pdf");
        assertSame(expected, StagedDocuments.singleMetadata(ID, Collections.singletonList(expected)));
        assertThrows(IOException.class, () -> StagedDocuments.singleMetadata(ID, Collections.emptyList()));
        assertThrows(IOException.class, () -> StagedDocuments.singleMetadata(ID, Arrays.asList(expected, expected)));
        assertThrows(IOException.class, () -> StagedDocuments.singleMetadata(ID,
                Collections.singletonList(entry("other-id", "original.pdf"))));
    }

    @Test public void metadataPermissionAndDisappearanceAreNotDowngraded() {
        Lookup lookup = new Lookup(); lookup.metadataFailure = new SecurityException("revoked after path");
        assertSame(lookup.metadataFailure, assertThrows(SecurityException.class, () -> read(lookup)));
        lookup.metadataFailure = null; lookup.metadataMissing = new FileNotFoundException("moved after path");
        assertSame(lookup.metadataMissing, assertThrows(FileNotFoundException.class, () -> read(lookup)));
        assertEquals(0, lookup.listings);
    }

    private static StagedDocuments.Entry read(Lookup lookup) throws IOException {
        return StagedDocuments.currentChild(ROOT, FOLDER, ID, lookup);
    }

    private static StagedDocuments.Entry entry(String id, String name) {
        return new StagedDocuments.Entry(id, name, "application/pdf", 0);
    }

    private static final class Lookup implements StagedDocuments.ChildLookup {
        List<String> path = Arrays.asList(ROOT, FOLDER, ID);
        StagedDocuments.Entry current = entry(ID, "original.pdf"), listed = current;
        RuntimeException pathFailure, metadataFailure;
        FileNotFoundException missing, metadataMissing;
        IOException listFailure;
        int paths, metadata, listings;
        public List<String> path() throws IOException {
            paths++;
            if (pathFailure != null) throw pathFailure;
            if (missing != null) throw missing;
            return path;
        }
        public StagedDocuments.Entry metadata() throws IOException {
            metadata++;
            if (metadataFailure != null) throw metadataFailure;
            if (metadataMissing != null) throw metadataMissing;
            return current;
        }
        public StagedDocuments.Entry listed() throws IOException {
            listings++;
            if (listFailure != null) throw listFailure;
            return listed;
        }
    }
}
