package cn.local.pdfbookmarks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit main-package check: reads original documents in memory, never accepts an import or writes a plan. */
public final class ReadOnly142Smoke {
    private ReadOnly142Smoke() { }

    /** Provider micro-profile only: no review, accepted import, plan, grant or file mutation. */
    public static String profile(Instrumentation instrumentation, Activity activity) throws Exception {
        require("cn.local.pdfbookmarks".equals(activity.getPackageName()), "Read-only profile requires the real main package");
        instrumentation.waitForIdleSync();
        onMain(instrumentation, () -> {
            require(!(Boolean) field(activity, "busy") && !(Boolean) field(activity, "shareLoading")
                            && field(activity, "readJob") == null && (Integer) field(activity, "systemPickerRequest") == 0
                            && field(activity, "workflowDialog") == null,
                    "Existing work or dialog must remain undisturbed");
        });
        State before = new State(activity);
        Uri pdf = (Uri) field(activity, "pdfUri");
        require(pdf != null && EnjoyDirectorySync.recognizes(pdf), "The currently selected Enjoy PDF is required");
        String beforeHash = hash(activity, pdf);
        long start = System.nanoTime();
        Uri folder = PathDocuments.folderForDocument(activity, pdf);
        long parentMs = elapsedMs(start);
        start = System.nanoTime();
        StagedDocuments documents = StagedDocuments.forCatalog(activity, folder);
        long constructorMs = elapsedMs(start);
        Method children = StagedDocuments.class.getDeclaredMethod("children"); children.setAccessible(true);
        Method metadata = StagedDocuments.class.getDeclaredMethod("metadata", Uri.class); metadata.setAccessible(true);
        List<Map<String, Object>> trials = new ArrayList<>();
        for (int trial = 0; trial < 3; trial++) {
            Map<String, Object> timing = new LinkedHashMap<>(); timing.put("trial", trial);
            start = System.nanoTime();
            List<?> entries = (List<?>) reflect(children, documents);
            timing.put("childrenMs", elapsedMs(start)); timing.put("childCount", entries.size());
            start = System.nanoTime();
            Object pdfMetadata = reflect(metadata, documents, pdf);
            timing.put("metadataPdfMs", elapsedMs(start));
            require(pdfMetadata != null, "PDF metadata was absent");
            start = System.nanoTime();
            Object folderMetadata = reflect(metadata, documents, documents.folder);
            timing.put("metadataFolderMs", elapsedMs(start));
            require(folderMetadata != null, "Folder metadata was absent");
            start = System.nanoTime();
            DocumentsContract.Path path = DocumentsContract.findDocumentPath(activity.getContentResolver(), pdf);
            timing.put("findDocumentPathMs", elapsedMs(start));
            require(path != null && !path.getPath().isEmpty(), "Selected PDF had no document path");
            timing.put("pathSegments", path.getPath().size()); trials.add(timing);
        }
        Map<String, Object> stream = new LinkedHashMap<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; long bytes = 0;
        start = System.nanoTime();
        InputStream opened = activity.getContentResolver().openInputStream(pdf);
        long openMs = elapsedMs(start);
        require(opened != null, "PDF provider returned no direct stream");
        long readStart = System.nanoTime();
        try (InputStream input = opened) {
            for (int count; (count = input.read(buffer)) != -1;) { digest.update(buffer, 0, count); bytes += count; }
        }
        stream.put("openMs", openMs); stream.put("readAndSha256Ms", elapsedMs(readStart)); stream.put("bytes", bytes);
        StringBuilder directHash = new StringBuilder(64);
        for (byte value : digest.digest()) directHash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        require(bytes > 0 && beforeHash.equals(directHash.toString()), "Direct stream changed or did not match the selected PDF");
        Map<String, Object> snapshot = snapshotConfig(activity, documents, pdf);
        String afterHash = hash(activity, pdf);
        require(beforeHash.equals(afterHash), "Selected PDF changed during read-only profiling");
        before.verify(activity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS"); result.put("parentResolutionMs", parentMs); result.put("constructorMs", constructorMs);
        result.put("trials", trials); result.put("directStream", stream);
        result.put("snapshotConfig", snapshot);
        result.put("beforeSha256", beforeHash); result.put("directSha256", directHash.toString()); result.put("afterSha256", afterHash);
        result.put("checks", "Selected inputs, four preference groups, persisted grants, draft, private JSON store and selected PDF hash unchanged");
        result.put("scope", "Real selected PDF provider read-only micro-profile: three raw trials each of children, PDF metadata, folder metadata and findDocumentPath; one timed direct stream open and in-memory SHA scan; three raw config/private JSON reads, TocParser calls and in-memory native catalog calls per merge mode. Original configuration, private JSON and PDF hashes are checked before/after; integrity reads are outside operation timings. No disk copy, review, accepted import, plan, generation, synchronization, backup, grant change, cleanup or deletion.");
        return new Gson().toJson(result);
    }

    private static Map<String, Object> snapshotConfig(Activity activity, StagedDocuments documents, Uri pdf) throws Exception {
        JsonInputRef ref = (JsonInputRef) field(activity, "sharedJson");
        JsonInputStore store = (JsonInputStore) field(activity, "jsonInputs");
        require(ref != null && store != null, "An already imported private JSON input is required; the profiler never imports one");
        require(ref.sourceUri.equals(String.valueOf(field(activity, "jsonUri"))), "Private JSON does not match the selected source identity");
        List<String> names = java.util.Arrays.asList("catalog.json", "doc", "pageids.json");
        long start = System.nanoTime();
        Map<String, List<StagedReplacement.Document>> found = documents.findAll(names);
        long lookupMs = elapsedMs(start);
        Map<String, Uri> sources = new LinkedHashMap<>();
        Map<String, byte[]> baseline = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, Integer> lengths = new LinkedHashMap<>();
        for (String name : names) {
            List<StagedReplacement.Document> entries = found.get(name);
            require(entries != null && entries.size() == 1, "Configuration was absent or ambiguous: " + name);
            Uri source = Uri.parse(entries.get(0).uri); byte[] data = readSmall(activity, source);
            sources.put(name, source); baseline.put(name, data); lengths.put(name, data.length);
            hashes.put(name, hash(new ByteArrayInputStream(data)));
        }
        byte[] json = store.read(ref);
        require(json.length <= JsonInputStore.MAX_BYTES && ref.sha256.equals(hash(new ByteArrayInputStream(json))), "Private JSON failed its original hash");
        String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(json)).toString();
        int pages;
        start = System.nanoTime();
        try (ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(pdf, "r")) {
            require(descriptor != null, "Selected PDF has no page-count descriptor");
            try (PdfRenderer renderer = new PdfRenderer(descriptor)) { pages = renderer.getPageCount(); }
        }
        long pageCountMs = elapsedMs(start);
        String id = DocumentsContract.getDocumentId(pdf);
        require(id.startsWith("primary:"), "Native profile requires the verified primary-storage PDF identity");
        String androidPdfPath = "/storage/emulated/0/" + id.substring("primary:".length());
        List<Map<String, Object>> runs = new ArrayList<>();
        TocParser.Parsed parsed = null;
        for (int trial = 0; trial < 3; trial++) {
            Map<String, Object> timing = new LinkedHashMap<>(); timing.put("trial", trial);
            Map<String, Long> reads = new LinkedHashMap<>();
            for (String name : names) {
                start = System.nanoTime(); byte[] data = readSmall(activity, sources.get(name));
                reads.put(name, elapsedMs(start));
                require(hashes.get(name).equals(hash(new ByteArrayInputStream(data))), "Configuration changed during raw read: " + name);
            }
            timing.put("rawConfigReadMs", reads);
            start = System.nanoTime(); byte[] currentJson = store.read(ref);
            timing.put("privateJsonReadMs", elapsedMs(start));
            require(ref.sha256.equals(hash(new ByteArrayInputStream(currentJson))), "Private JSON changed during profile");
            start = System.nanoTime(); parsed = TocParser.parse(raw, pages);
            timing.put("tocParseMs", elapsedMs(start)); timing.put("rows", parsed.rows.size());
            runs.add(timing);
        }
        require(parsed != null, "Profile did not parse the selected JSON");
        List<Map<String, Object>> merges = new ArrayList<>();
        for (int trial = 0; trial < 3; trial++) for (boolean replace : new boolean[]{false, true}) {
            start = System.nanoTime();
            EnjoyCatalog.Plan plan = EnjoyCatalog.mergeNativePdfBookmarks(baseline.get("catalog.json"), baseline.get("pageids.json"),
                    baseline.get("doc"), androidPdfPath, pages, parsed.rows, replace);
            long duration = elapsedMs(start);
            require(plan.replacesExisting == replace && plan.originalCount >= 0
                            && (replace ? plan.totalCount == parsed.rows.size() : plan.totalCount == plan.originalCount + plan.addedCount),
                    "Pure native catalog profile produced inconsistent counts");
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("trial", trial); timing.put("replace", replace); timing.put("nativeMergeMs", duration);
            timing.put("originalRows", plan.originalCount); timing.put("addedRows", plan.addedCount); timing.put("totalRows", plan.totalCount);
            timing.put("nativeMappedPages", plan.nativePageIds.size()); timing.put("candidateBytes", plan.json.length); merges.add(timing);
        }
        for (String name : names) require(hashes.get(name).equals(hash(activity, sources.get(name))), "Original configuration changed after profiling: " + name);
        require(ref.sha256.equals(hash(new ByteArrayInputStream(store.read(ref)))), "Private JSON changed after profiling");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lookupMs", lookupMs); result.put("configBytes", lengths); result.put("privateJsonBytes", json.length);
        result.put("pdfPageCountMs", pageCountMs); result.put("pdfPages", pages); result.put("readAndTocTrials", runs); result.put("nativeMergeTrials", merges);
        result.put("hashesUnchanged", true);
        result.put("scope", "Existing catalog.json/doc/pageids.json read directly with a 4 MiB in-memory cap; existing private JSON read through JsonInputStore; candidate catalog bytes remain only in RAM. Config lookup, raw I/O, private input integrity read, TocParser and pure native merge timings are separate; source contents are omitted.");
        return result;
    }

    private static long elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static Object reflect(Method method, Object target, Object... arguments) throws Exception {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    public static String verify(Instrumentation instrumentation, Activity activity, Uri referenceJson) throws Exception {
        require("cn.local.pdfbookmarks".equals(activity.getPackageName()), "Read-only validation requires the real main package");
        require(referenceJson != null, "An explicitly selected readable reference JSON is required");
        instrumentation.waitForIdleSync();
        onMain(instrumentation, () -> {
            require(!(Boolean) field(activity, "busy") && !(Boolean) field(activity, "shareLoading")
                    && field(activity, "readJob") == null && (Integer) field(activity, "systemPickerRequest") == 0,
                    "Existing work must remain undisturbed");
            require(field(activity, "workflowDialog") == null && field(activity, "parsed") == null,
                    "Existing review or dialog must remain undisturbed");
        });
        State before = new State(activity);
        Uri pdf = (Uri) field(activity, "pdfUri"), selectedJson = (Uri) field(activity, "jsonUri");
        require(pdf != null && selectedJson != null && EnjoyDirectorySync.recognizes(pdf), "The intended Enjoy PDF and selected JSON are required");
        String pdfHash = hash(activity, pdf), referenceHash = hash(activity, referenceJson);
        int pages;
        try (ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(pdf, "r")) {
            require(descriptor != null, "PDF has no readable descriptor");
            try (PdfRenderer renderer = new PdfRenderer(descriptor)) { pages = renderer.getPageCount(); }
        }
        byte[] jsonBytes = readSmall(activity, referenceJson);
        String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(jsonBytes)).toString();
        TocParser.Parsed parsed = TocParser.parse(raw, pages);
        EnjoyDirectorySync sync = new EnjoyDirectorySync(activity);
        require(!sync.hasPending() && sync.automaticCleanupRecord() == null, "Existing recovery or cleanup must remain undisturbed");
        List<Map<String, Object>> timings = new ArrayList<>();
        for (int trial = 0; trial < 3; trial++) for (boolean replace : new boolean[]{false, true}) {
            WorkMetrics metrics = new WorkMetrics(replace ? "readOnlyReplace" : "readOnlyMerge");
            WorkMetrics.attach(metrics); long start = System.nanoTime(); EnjoyDirectorySync.Prepared plan;
            try { plan = sync.prepare(pdf, pages, parsed.rows, pdfHash, replace); }
            finally { WorkMetrics.detach(); }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            require(plan.plan.replacesExisting == replace && plan.plan.originalCount >= 0, "Prepared mode or original count is invalid");
            require(replace ? plan.plan.totalCount == parsed.rows.size()
                    : plan.plan.totalCount == plan.plan.originalCount + plan.plan.addedCount,
                    "Prepared directory counts are inconsistent");
            CatalogDiff.Summary preview = plan.previewChanges();
            require(preview.changes.size() == preview.targetPages.size() && !plan.describePreview().isEmpty(), "Preview lost its target information");
            for (int page : preview.targetPages) require(page >= 0 && page <= pages, "Preview guessed an invalid PDF target");
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("replace", replace); run.put("trial", trial); run.put("elapsedMs", elapsed);
            run.put("providerReads", metrics.providerReads); run.put("providerBytes", metrics.providerBytes);
            run.put("directoryQueries", metrics.directoryQueries); run.put("phasesMs",new LinkedHashMap<>(metrics.phases)); run.put("originalRows", plan.plan.originalCount);
            run.put("addedRows", plan.plan.addedCount); run.put("totalRows", plan.plan.totalCount);
            run.put("nativeMappedPages", plan.plan.nativePageIds.size()); run.put("preview", preview.counts()); timings.add(run);
            Log.i("PdfBookmarks.ReadOnly142", new Gson().toJson(run));
        }
        Probe source = probe(activity, selectedJson);
        AtomicReference<Intent> attemptedTree = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
                attemptedTree.set(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            onMain(instrumentation, () -> invoke(activity, "receiveShare", new Class<?>[]{Intent.class},
                    new Intent(Intent.ACTION_VIEW).setDataAndType(selectedJson, "application/json")));
            long deadline = SystemClock.uptimeMillis() + 20000;
            while ((Boolean) field(activity, "shareLoading") && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(40); instrumentation.waitForIdleSync();
            }
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                require(!(Boolean) field(activity, "shareLoading"), "Share classification timed out");
                AlertDialog dialog = dialog(activity);
                String prompt = text(dialog.getWindow().getDecorView());
                require(!hasAuthorizationAction(dialog.getWindow().getDecorView()), "Share error offers Android/data or folder authorization");
                require(!rawPermissionError(prompt) && !rawPermissionError(((TextView) field(activity, "status")).getText().toString()),
                        "Main error prompt exposes the old raw permission failure");
                if (source.readable) require(prompt.contains("导入分享文件"), "Readable JSON did not reach confirmation");
                else require(prompt.contains("重新分享") || prompt.contains("Download"),
                        "Unreadable source has no useful share/download recovery; prompt=" + prompt
                                + " / status=" + ((TextView) field(activity, "status")).getText());
                cancel(dialog);
            });
            instrumentation.waitForIdleSync(); before.verify(activity);
            onMain(instrumentation, () -> invoke(activity, "authorizeFolder", new Class<?>[]{String.class}, pdf.toString()));
            instrumentation.waitForIdleSync();
            require(attemptedTree.get() == null && (Integer) field(activity, "systemPickerRequest") == 0,
                    "Already authorized PDF attempted another folder picker");
            require(field(activity, "workflowDialog") == null, "Already authorized PDF unexpectedly opened a dialog");
            if (!DocumentsContract.isDocumentUri(activity, selectedJson)) {
                onMain(instrumentation, () -> {
                    invoke(activity, "authorizeFolder", new Class<?>[]{String.class}, selectedJson.toString());
                    AlertDialog dialog = dialog(activity);
                    require(text(dialog.getWindow().getDecorView()).contains("单文件地址")
                                    && !hasAuthorizationAction(dialog.getWindow().getDecorView()),
                            "A provider share address was treated as a folder grant");
                    cancel(dialog);
                });
                instrumentation.waitForIdleSync();
            }
            require(attemptedTree.get() == null, "A read-only check attempted TREE authorization");
            before.verify(activity);
        } finally {
            instrumentation.removeMonitor(monitor);
            onMain(instrumentation, () -> {
                if ((Boolean) field(activity, "shareLoading")) invoke(activity, "cancelShare", new Class<?>[0]);
                Object opened = field(activity, "workflowDialog");
                if (opened instanceof AlertDialog && ((AlertDialog) opened).isShowing()) ((AlertDialog) opened).cancel();
            });
            instrumentation.waitForIdleSync();
        }
        require(pdfHash.equals(hash(activity, pdf)) && referenceHash.equals(hash(activity, referenceJson)), "Original PDF or reference JSON changed");
        before.verify(activity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS"); result.put("pdfPages", pages); result.put("importedRows", parsed.rows.size());
        result.put("sourceGrantFlag", source.grant); result.put("sourceReadable", source.readable); result.put("sourceReadFailure", source.failure);
        result.put("merge", stats(timings, false)); result.put("replace", stats(timings, true)); result.put("prepareRuns", timings);
        result.put("checks", "VIEW canceled; authorized PDF reused; non-document source offered no folder grant; selections, four preference groups, persistent grants, draft, private JSON store and source hashes unchanged");
        result.put("scope", "Real-document read-only prepare timings, three trials per mode. No old APK speed comparison, loadPreview, accepted import, JSON/PDF copy, generation, apply, backup, cleanup or deletion.");
        return new Gson().toJson(result);
    }

    private static Map<String, Long> stats(List<Map<String, Object>> runs, boolean replace) {
        long[] times = runs.stream().filter(value -> Boolean.valueOf(replace).equals(value.get("replace")))
                .mapToLong(value -> ((Number) value.get("elapsedMs")).longValue()).sorted().toArray();
        require(times.length == 3, "Incomplete prepare timing group");
        Map<String, Long> result = new LinkedHashMap<>(); result.put("medianMs", times[1]); result.put("worstMs", times[2]); return result;
    }
    private static final class Probe { int grant; boolean readable; String failure = ""; }
    private static Probe probe(Activity activity, Uri source) {
        Probe probe = new Probe();
        probe.grant = activity.checkUriPermission(source, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try (InputStream input = activity.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("No source stream"); input.read(); probe.readable = true;
        } catch (Exception denied) { probe.failure = denied.getClass().getSimpleName(); }
        return probe;
    }
    private static final class State {
        final Map<String, Map<String, ?>> preferences = new LinkedHashMap<>();
        final Object pdf, json, selectedJson; final List<String> grants; final Map<String, String> ownedFiles;
        State(Activity activity) throws Exception {
            preferences.put("main", new HashMap<>(activity.getPreferences(Context.MODE_PRIVATE).getAll()));
            for (String name : new String[]{"enjoy-directory", "review_ui", "ui_appearance"})
                preferences.put(name, new HashMap<>(activity.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
            pdf = field(activity, "pdfUri"); json = field(activity, "jsonUri"); selectedJson = field(activity, "sharedJson");
            grants = grants(activity); ownedFiles = ownedFiles(activity);
        }
        void verify(Activity activity) throws Exception {
            require(preferences.get("main").equals(activity.getPreferences(Context.MODE_PRIVATE).getAll()), "Selected JSON or main preferences changed");
            for (String name : new String[]{"enjoy-directory", "review_ui", "ui_appearance"})
                require(preferences.get(name).equals(activity.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()), "Original preferences changed: " + name);
            require(java.util.Objects.equals(pdf, field(activity, "pdfUri")) && java.util.Objects.equals(json, field(activity, "jsonUri"))
                    && java.util.Objects.equals(selectedJson, field(activity, "sharedJson")), "Selected inputs changed");
            require(grants.equals(grants(activity)) && ownedFiles.equals(ownedFiles(activity)), "Grant flags, draft or private JSON input files changed");
        }
    }
    private static Map<String, String> ownedFiles(Activity activity) throws Exception {
        Map<String, String> result = new LinkedHashMap<>(); File files = activity.getFilesDir().getCanonicalFile();
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File file = new File(files, "review-draft-v1.json" + suffix);
            result.put(file.getName(), file.isFile() ? hash(file) : "ABSENT");
        }
        File directory = new File(files, "imports/json");
        if (directory.exists()) {
            File[] children = directory.listFiles(); require(children != null, "Cannot inspect private JSON input directory");
            for (File file : children) {
                require(directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()) && file.isFile(), "Unexpected private JSON store entry");
                result.put("imports/json/" + file.getName(), hash(file));
            }
        }
        return result;
    }
    private static List<String> grants(Activity activity) {
        List<String> result = new ArrayList<>();
        for (UriPermission grant : activity.getContentResolver().getPersistedUriPermissions())
            result.add(grant.getUri() + ":" + grant.isReadPermission() + ":" + grant.isWritePermission());
        Collections.sort(result); return result;
    }
    private static AlertDialog dialog(Activity activity) throws Exception {
        Object dialog = field(activity, "workflowDialog");
        require(dialog instanceof AlertDialog && ((AlertDialog) dialog).isShowing(), "Expected confirmation or friendly error dialog is absent");
        return (AlertDialog) dialog;
    }
    private static void cancel(AlertDialog dialog) {
        Button button = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        require(button != null && button.isEnabled(), "Cancel/return action is unavailable"); button.performClick();
    }
    private static boolean rawPermissionError(String value) { return value.contains("Permission Denial") || value.contains("not exported") || value.contains("ProcessRecord{"); }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private static boolean hasAuthorizationAction(View view) {
        if (view instanceof Button) { String text = ((Button) view).getText().toString(); if (text.contains("授权")) return true; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasAuthorizationAction(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }
    private static byte[] readSmall(Activity activity, Uri uri) throws IOException {
        try (InputStream input = activity.getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Reference JSON is unreadable"); byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("Reference JSON exceeds 4 MiB"); bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }
    private static String hash(Activity activity, Uri uri) throws Exception {
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) { if (input == null) throw new IOException("No readable document"); return hash(input); }
    }
    private static String hash(File file) throws Exception { try (InputStream input = new FileInputStream(file)) { return hash(input); } }
    private static String hash(InputStream input) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536];
        for (int count; (count = input.read(bytes)) != -1;) sha.update(bytes, 0, count);
        StringBuilder result = new StringBuilder(64); for (byte b : sha.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
    private interface Checked { void run() throws Exception; }
    private static void onMain(Instrumentation instrumentation, Checked action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        Throwable error = failure.get(); if (error instanceof Exception) throw (Exception) error; if (error instanceof Error) throw (Error) error;
    }
    private static Object field(Activity activity, String name) throws Exception { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
    private static Object invoke(Activity activity, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(activity, arguments); }
        catch (InvocationTargetException failure) { Throwable cause = failure.getCause(); if (cause instanceof Exception) throw (Exception) cause; if (cause instanceof Error) throw (Error) cause; throw failure; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
