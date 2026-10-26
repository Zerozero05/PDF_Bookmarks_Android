package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Button;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit, device-only read-only regression. Never applies a plan or accepts an import. */
public final class Import141Smoke {
    private final Instrumentation instrumentation;
    private final Bundle results = new Bundle();

    private Import141Smoke(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public static void verify(Instrumentation instrumentation) {
        new Import141Smoke(instrumentation).run();
    }

    private void run() {
        Activity activity = null;
        Throwable failure = null;
        try {
            activity = instrumentation.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClass(instrumentation.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            final Activity current = activity;
            require(!current.isFinishing() && !current.isDestroyed(), "MainActivity is unavailable");
            onMain(() -> {
                require(!(Boolean) field(current, "busy") && !(Boolean) field(current, "shareLoading")
                        && field(current, "readJob") == null && (Integer) field(current, "systemPickerRequest") == 0,
                        "Existing work must remain undisturbed");
                require(field(current, "parsed") == null && field(current, "workflowDialog") == null,
                        "Existing review or modal must remain undisturbed");
            });
            State before = new State(current);
            Uri pdf = (Uri) field(current, "pdfUri"), json = (Uri) field(current, "jsonUri");
            require(pdf != null && json != null && EnjoyDirectorySync.recognizes(pdf),
                    "Select the intended Enjoy PDF and JSON before this explicit read-only check");
            String originalPdfHash = digest(current, pdf), originalJsonHash = digest(current, json);
            int pages;
            try (ParcelFileDescriptor descriptor = current.getContentResolver().openFileDescriptor(pdf, "r")) {
                require(descriptor != null, "PDF provider returned no readable descriptor");
                try (PdfRenderer renderer = new PdfRenderer(descriptor)) { pages = renderer.getPageCount(); }
            }
            TocParser.Parsed parsed = TocParser.parse(readJson(current, json), pages);
            EnjoyDirectorySync sync = new EnjoyDirectorySync(current);
            require(!sync.hasPending() && sync.automaticCleanupRecord() == null,
                    "Existing catalog recovery must remain undisturbed");
            EnjoyDirectorySync.Prepared merge = sync.prepare(pdf, pages, parsed.rows, originalPdfHash, false);
            require(!merge.plan.replacesExisting && !merge.plan.nativePageIds.isEmpty(),
                    "The intended native PDF catalog shape was not exercised");
            require(merge.plan.totalCount >= merge.plan.originalCount
                            && merge.plan.totalCount <= merge.plan.originalCount + parsed.rows.size(),
                    "Merge produced an inconsistent entry count");
            CatalogDiff.Summary mergedDiff = merge.previewChanges();
            require(mergedDiff.targetPages.size() == mergedDiff.changes.size(), "Merge preview lost targets");
            require(!merge.describePreview().isEmpty(), "Merge preview is empty");
            results.putString("mergePreflight", "PASS: " + mergedDiff.counts());
            EnjoyDirectorySync.Prepared replace = sync.prepare(pdf, pages, parsed.rows, originalPdfHash, true);
            require(replace.plan.replacesExisting && replace.plan.totalCount == parsed.rows.size(),
                    "Replacement preview did not contain the complete imported tree");
            require(!replace.describePreview().isEmpty(), "Replacement preview is empty");
            results.putString("replacePreflight", "PASS: " + replace.previewChanges().counts());
            results.putInt("pdfPages", pages);
            results.putInt("importedRows", parsed.rows.size());
            results.putInt("originalCatalogRows", merge.plan.originalCount);
            results.putInt("nativeTargetMappings", merge.plan.nativePageIds.size());

            verifyJsonViewCancel(current, json);
            before.verify(current);
            verifyDownloadsCancel(current);
            before.verify(current);
            verifyRestrictedPath(current);
            before.verify(current);
            verifyUntrustedAddresses();
            require(originalPdfHash.equals(digest(current, pdf)), "PDF bytes changed during read-only checks");
            require(originalJsonHash.equals(digest(current, json)), "JSON bytes changed during read-only checks");
            before.verify(current);
            results.putString("dataPreserved", "PASS: PDF/JSON hashes, selected inputs, preferences, draft and grants unchanged");
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (activity != null) {
                final Activity current = activity;
                try {
                    onMain(() -> {
                        Object dialog = field(current, "workflowDialog");
                        if (dialog instanceof AlertDialog && ((AlertDialog) dialog).isShowing()) ((AlertDialog) dialog).cancel();
                        if (!current.isFinishing()) current.finish();
                    });
                    instrumentation.waitForIdleSync();
                } catch (Throwable closeError) {
                    if (failure == null) failure = closeError; else failure.addSuppressed(closeError);
                }
            }
        }
        results.putString("import141Smoke", failure == null ? "PASS" : "FAIL");
        results.putString("scope", "Existing authorized PDF/JSON read-only preflight in both modes; existing SAF URI VIEW confirmation canceled; system picker intercepted and canceled; restricted raw path guidance. No PDF/catalog write, backup, deletion, grant change or accepted import. External QQ/file-manager grant delivery is not covered.");
        if (failure != null) results.putString("failure", Log.getStackTraceString(failure));
        results.putString("stream", failure == null ? "\nPASS: v1.4.1 explicit read-only checks.\n" : "\nFAIL: Import141Smoke\n" + Log.getStackTraceString(failure) + "\n");
        instrumentation.finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
    }

    private void verifyJsonViewCancel(Activity activity, Uri json) throws Exception {
        Intent incoming = new Intent(Intent.ACTION_VIEW).setDataAndType(json, "application/json")
                .setPackage(activity.getPackageName()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        require(activity.getPackageManager().resolveActivity(incoming, 0) != null, "JSON VIEW manifest route is missing");
        onMain(() -> invoke(activity, "receiveShare", new Class<?>[]{Intent.class}, incoming));
        long deadline = SystemClock.uptimeMillis() + 20000;
        while ((Boolean) field(activity, "shareLoading") && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50); instrumentation.waitForIdleSync();
        }
        instrumentation.waitForIdleSync();
        onMain(() -> {
            require(!(Boolean) field(activity, "shareLoading"), "VIEW classification timed out");
            AlertDialog dialog = (AlertDialog) field(activity, "workflowDialog");
            require(dialog != null && dialog.isShowing(), "VIEW import confirmation was not shown");
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            require(cancel != null && cancel.isEnabled(), "Import cancellation is unavailable");
            cancel.performClick();
        });
        instrumentation.waitForIdleSync();
        require(field(activity, "workflowDialog") == null, "Import cancellation left a modal open");
        results.putString("jsonViewCancel", "PASS: JSON VIEW route and confirmation; canceled before selection");
    }

    private void verifyDownloadsCancel(Activity activity) throws Exception {
        AtomicReference<Intent> captured = new AtomicReference<>();
        ActivityMonitor monitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null;
                captured.set(new Intent(intent));
                return new ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            onMain(() -> invoke(activity, "pickSystemFile", new Class<?>[]{int.class}, 2));
            instrumentation.waitForIdleSync();
            Intent intent = captured.get();
            require(intent != null, "JSON system document request was not intercepted");
            Uri initial = intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
            require(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download").equals(initial),
                    "JSON picker does not start at Download");
            require(intent.hasCategory(Intent.CATEGORY_OPENABLE), "JSON picker is not a file request");
            onMain(() -> invoke(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class},
                    2, Activity.RESULT_CANCELED, null));
            require((Integer) field(activity, "systemPickerRequest") == 0, "Canceled picker remains pending");
            results.putString("downloadsCancel", "PASS: Download initial URI, blocked external launch and canceled result");
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void verifyRestrictedPath(Activity activity) throws Exception {
        AtomicReference<Intent> unexpectedTree = new AtomicReference<>();
        ActivityMonitor monitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
                unexpectedTree.set(new Intent(intent));
                return new ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            final String path = "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/outline.json";
            for (boolean directAuthorization : new boolean[]{false, true}) {
                onMain(() -> {
                    if (directAuthorization) invoke(activity, "authorizeFolder", new Class<?>[]{String.class}, path);
                    else invoke(activity, "resolvePath", new Class<?>[]{int.class, String.class}, 2, path);
                    AlertDialog dialog = (AlertDialog) field(activity, "workflowDialog");
                    require(dialog != null && dialog.isShowing(), "Restricted path guidance was not shown");
                    Button next = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    require(next != null && next.getText().toString().contains("下载"),
                            "Restricted path still asks for an impossible folder authorization");
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                    require((Integer) field(activity, "pendingPathRequest") == 0,
                            "Restricted path left a folder authorization retry pending");
                });
                instrumentation.waitForIdleSync();
                require(unexpectedTree.get() == null, "Restricted path attempted a system folder authorization");
            }
            results.putString("restrictedPath", "PASS: raw path resolution and direct authorization offer Download; neither launches a folder grant");
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void verifyUntrustedAddresses() {
        for (String uri : new String[]{"file:///sdcard/outline.json", "https://example.invalid/outline.json"}) {
            try { new SharedImport.Document(uri, "outline.json"); throw new AssertionError("Untrusted address accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { new SharedImport.Document("content://example.invalid/document/1", "outline.txt"); throw new AssertionError("Wrong extension accepted"); }
        catch (IllegalArgumentException expected) { }
        results.putString("invalidDocuments", "PASS: file/network addresses and non-JSON/PDF extensions rejected by classifier");
    }

    private static final class State {
        final Map<String, ?> main, enjoy, review, appearance;
        final Object pdf, json;
        final List<String> grants;
        final String draftHash;
        State(Activity activity) throws Exception {
            main = new HashMap<>(activity.getPreferences(Context.MODE_PRIVATE).getAll());
            enjoy = preferences(activity, "enjoy-directory"); review = preferences(activity, "review_ui");
            appearance = preferences(activity, "ui_appearance");
            pdf = field(activity, "pdfUri"); json = field(activity, "jsonUri");
            grants = grants(activity); draftHash = draftHash(activity);
        }
        void verify(Activity activity) throws Exception {
            require(main.equals(activity.getPreferences(Context.MODE_PRIVATE).getAll()), "Main preferences changed");
            require(enjoy.equals(preferences(activity, "enjoy-directory")), "Catalog preferences changed");
            require(review.equals(preferences(activity, "review_ui")), "Review preferences changed");
            require(appearance.equals(preferences(activity, "ui_appearance")), "Appearance preferences changed");
            require(pdf.equals(field(activity, "pdfUri")) && json.equals(field(activity, "jsonUri")), "Selected inputs changed");
            require(grants.equals(grants(activity)), "Persisted grants changed");
            require(draftHash.equals(draftHash(activity)), "Saved draft changed");
        }
    }
    private static Map<String, ?> preferences(Activity activity, String name) {
        return new HashMap<>(activity.getSharedPreferences(name, Context.MODE_PRIVATE).getAll());
    }
    private static List<String> grants(Activity activity) {
        List<String> values = new ArrayList<>();
        for (UriPermission grant : activity.getContentResolver().getPersistedUriPermissions())
            values.add(grant.getUri() + ":" + grant.isReadPermission() + ":" + grant.isWritePermission());
        java.util.Collections.sort(values); return values;
    }
    private static String draftHash(Activity activity) throws Exception {
        File draft = new File(activity.getFilesDir(), "review-draft-v1.json");
        if (!draft.exists()) return "ABSENT";
        try (InputStream input = new FileInputStream(draft)) { return hash(input); }
    }
    private static String digest(Activity activity, Uri uri) throws Exception {
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            require(input != null, "Document provider returned no stream"); return hash(input);
        }
    }
    private static String hash(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
        StringBuilder text = new StringBuilder(); for (byte value : hash.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static String readJson(Activity activity, Uri uri) throws IOException {
        try (InputStream input = activity.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("JSON provider returned no stream");
            byte[] buffer = new byte[8192]; for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private interface Checked { void run() throws Exception; }
    private void onMain(Checked action) throws Exception {
        AtomicReference<Exception> exception = new AtomicReference<>(); AtomicReference<AssertionError> assertion = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Exception error) { exception.set(error); } catch (AssertionError error) { assertion.set(error); } });
        if (assertion.get() != null) throw assertion.get(); if (exception.get() != null) throw exception.get();
    }
    private static Object field(Activity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private static Object invoke(Activity activity, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(activity, arguments); }
        catch (InvocationTargetException wrapped) {
            Throwable error = wrapped.getCause();
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw wrapped;
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
