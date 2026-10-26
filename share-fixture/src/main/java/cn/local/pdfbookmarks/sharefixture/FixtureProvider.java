package cn.local.pdfbookmarks.sharefixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test-only, non-exported provider. All bytes are generated here, never taken from user storage. */
public final class FixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "cn.local.pdfbookmarks.sharefixture.files";
    public static final String JSON = "{\"version\":1,\"bookmarks\":[{\"title\":\"Fixture chapter\",\"pdf_page\":1,\"children\":[{\"title\":\"Fixture section\",\"pdf_page\":2}]},{\"title\":\"Fixture end\",\"pdf_page\":3}]}";
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "readonly.json", "deletable.json", "other.json", "broken.json", "slow.json",
            "oversize.json", "malformed.json", "fixture.pdf"));
    private static final Object LOCK = new Object();

    @Override public boolean onCreate() { return true; }

    public static Uri document(String run, String name) {
        validate(run, name);
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("files")
                .appendPath(run).appendPath(name).build();
    }

    static void prepare(Context context, String run, String name) throws IOException {
        validate(run, name);
        File file = file(context, run, name);
        synchronized (LOCK) {
            if (file.isFile()) return;
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create synthetic fixture directory");
            if (!file.createNewFile()) throw new IOException("Synthetic fixture already appeared");
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (name.endsWith(".pdf")) {
                    PdfDocument pdf = new PdfDocument();
                    try {
                        Paint paint = new Paint(); paint.setTextSize(18);
                        for (int page = 1; page <= 3; page++) {
                            PdfDocument.Page sheet = pdf.startPage(new PdfDocument.PageInfo.Builder(420, 594, page).create());
                            sheet.getCanvas().drawText("Share fixture page " + page, 32, 64, paint);
                            pdf.finishPage(sheet);
                        }
                        pdf.writeTo(output);
                    } finally { pdf.close(); }
                } else {
                    String json = "malformed.json".equals(name) ? "{\"version\":"
                            : "other.json".equals(name) ? JSON.replace("Fixture", "Other") : JSON;
                    output.write(json.getBytes(StandardCharsets.UTF_8));
                    if ("oversize.json".equals(name)) {
                        byte[] spaces = new byte[8192]; Arrays.fill(spaces, (byte) ' ');
                        for (int count = 0; count < 513; count++) output.write(spaces);
                    }
                }
                output.getFD().sync();
            }
            preferences(context).edit().putBoolean(key(run, name, "deleted"), false).apply();
        }
    }

    @Override public String getType(Uri uri) {
        Path path = path(uri);
        return path.name.endsWith(".pdf") ? "application/pdf" : "application/json";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return queryFixture(getContext(), uri, projection);
    }

    static Cursor queryFixture(Context context, Uri uri, String[] projection) {
        Path path = path(uri); File file = file(context, path.run, path.name);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1); Object[] row = new Object[columns.length];
        SharedPreferences stats = preferences(context);
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case OpenableColumns.DISPLAY_NAME: row[index] = path.name; break;
                case OpenableColumns.SIZE: row[index] = file.exists() ? file.length() : 0L; break;
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[index] = path.run + "/" + path.name; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: row[index] = path.name.endsWith(".pdf") ? "application/pdf" : "application/json"; break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    row[index] = "deletable.json".equals(path.name) ? DocumentsContract.Document.FLAG_SUPPORTS_DELETE : 0; break;
                case "fixture_open_count": row[index] = stats.getInt(key(path.run, path.name, "open"), 0); break;
                case "fixture_delete_count": row[index] = stats.getInt(key(path.run, path.name, "delete"), 0); break;
                case "fixture_exists": row[index] = file.isFile() ? 1 : 0; break;
                default: row[index] = null;
            }
        }
        result.addRow(row); return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFixture(getContext(), uri, mode);
    }

    static ParcelFileDescriptor openFixture(Context context, Uri uri, String mode) throws FileNotFoundException {
        Path path = path(uri);
        if (!"r".equals(mode)) throw new SecurityException("Fixture document streams are read-only");
        increment(context, path.run, path.name, "open");
        if ("broken.json".equals(path.name)) throw new FileNotFoundException("Deliberate source read failure");
        if ("slow.json".equals(path.name)) {
            try { Thread.sleep(1500); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Slow source interrupted"); }
        }
        return ParcelFileDescriptor.open(file(context, path.run, path.name), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return deleteFixture(getContext(), uri);
    }

    static int deleteFixture(Context context, Uri uri) {
        Path path = path(uri); increment(context, path.run, path.name, "delete");
        if (!"deletable.json".equals(path.name)) throw new SecurityException("Synthetic source does not support deletion");
        File file = file(context, path.run, path.name);
        boolean deleted = file.isFile() && file.delete();
        if (deleted) preferences(context).edit().putBoolean(key(path.run, path.name, "deleted"), true).apply();
        return deleted ? 1 : 0;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("No fixture insert API"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("No fixture update API"); }

    static String stats(Context context, String run, String name) {
        validate(run, name); SharedPreferences prefs = preferences(context);
        return "run=" + run + " file=" + name + " open=" + prefs.getInt(key(run, name, "open"), 0)
                + " delete=" + prefs.getInt(key(run, name, "delete"), 0)
                + " exists=" + file(context, run, name).isFile();
    }

    static Bundle status(Context context, String run, String name) {
        validate(run, name); SharedPreferences prefs = preferences(context); Bundle status = new Bundle();
        status.putString("run", run); status.putString("file", name);
        status.putInt("open", prefs.getInt(key(run, name, "open"), 0));
        status.putInt("delete", prefs.getInt(key(run, name, "delete"), 0));
        status.putBoolean("exists", file(context, run, name).isFile());
        return status;
    }

    /** Changes only a fixed synthetic deletable source, for a post-import identity/hash regression. */
    static void mutate(Context context, String run, String name) throws IOException {
        validate(run, name);
        if (!"deletable.json".equals(name)) throw new IllegalArgumentException("Only the synthetic deletable source can change");
        synchronized (LOCK) {
            File file = file(context, run, name);
            if (!file.isFile()) throw new IOException("Synthetic source is absent");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(JSON.replace("Fixture", "Other").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            increment(context, run, name, "mutate");
        }
    }

    private static void increment(Context context, String run, String name, String event) {
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(context); String key = key(run, name, event);
            int count = prefs.getInt(key, 0) + 1;
            if (!prefs.edit().putInt(key, count).commit()) throw new IllegalStateException("Cannot store fixture event count");
            Log.i("ShareFixture", event + " run=" + run + " file=" + name + " count=" + count);
        }
    }

    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences("fixture_events", Context.MODE_PRIVATE); }
    private static String key(String run, String name, String event) { return run + "/" + name + "/" + event; }
    private static void validate(String run, String name) {
        if (run == null || !run.matches("[A-Za-z0-9_-]{1,64}") || !NAMES.contains(name)) throw new IllegalArgumentException("Unknown synthetic fixture");
    }
    private static File file(Context context, String run, String name) {
        validate(run, name); return new File(new File(new File(context.getCacheDir(), "share-fixture"), run), name);
    }
    private static Path path(Uri uri) {
        List<String> parts = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || parts.size() != 3 || !"files".equals(parts.get(0))) {
            throw new IllegalArgumentException("Unknown fixture document URI");
        }
        validate(parts.get(1), parts.get(2)); return new Path(parts.get(1), parts.get(2));
    }
    private static final class Path {
        final String run, name;
        Path(String run, String name) { this.run = run; this.name = name; }
    }
}
