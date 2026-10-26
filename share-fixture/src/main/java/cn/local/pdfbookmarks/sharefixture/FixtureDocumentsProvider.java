package cn.local.pdfbookmarks.sharefixture;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.FileNotFoundException;

/** Standard MANAGE_DOCUMENTS-protected provider; exact URI grants are required for client access. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "cn.local.pdfbookmarks.sharefixture.documents";

    public static Uri document(String run, String name) {
        FixtureProvider.document(run, name); // Reuse the fixed fixture whitelist, never accept a path.
        return DocumentsContract.buildDocumentUri(AUTHORITY, run + "/" + name);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        // Fixtures are delivered only by explicit sharing; do not add a browser location to the device UI.
        return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_FLAGS} : projection);
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        String[] selected = projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE} : projection;
        return FixtureProvider.queryFixture(getContext(), source(documentId), selected);
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        throw new FileNotFoundException("Synthetic fixtures expose exact documents only");
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        return FixtureProvider.openFixture(getContext(), source(documentId), mode);
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (FixtureProvider.deleteFixture(getContext(), source(documentId)) != 1)
            throw new FileNotFoundException("Synthetic source was not deleted");
    }

    private static Uri source(String documentId) throws FileNotFoundException {
        String[] parts = documentId == null ? new String[0] : documentId.split("/", -1);
        try {
            if (parts.length == 2) return FixtureProvider.document(parts[0], parts[1]);
        } catch (IllegalArgumentException invalid) { /* Report only a bounded, synthetic document ID failure. */ }
        throw new FileNotFoundException("Unknown synthetic document");
    }
}
